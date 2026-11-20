package app.entity.shield.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * Puts the Llama model on the phone without a PC or a network: the user picks the model (.pte) and
 * tokenizer.model from Downloads, a USB stick or anywhere the system file picker reaches, and they are
 * copied into Shield's own storage under the names Explainer looks for.
 *
 * Files are recognised by their content, not their name, because browsers and messengers rename them
 * ("llama…(1).pte", "tokenizer (1).model", a missing extension). They can be picked together or one
 * at a time. The published QAT+LoRA model and tokenizer are checked against their SHA-256, so a
 * half-finished browser download is caught here instead of failing later when Llama loads.
 */
object ModelImport {
    private const val TAG = "ShieldLLM"
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    enum class Kind { MODEL, TOKENIZER, UNKNOWN }

    /** What a file is, from its first bytes (ExecuTorch flatbuffer "ET" id, or tiktoken "base64 rank" lines). */
    fun classify(head: ByteArray, name: String): Kind {
        if (head.size >= 8 && head[4] == 'E'.code.toByte() && head[5] == 'T'.code.toByte() &&
            head[6] in '0'.code.toByte()..'9'.code.toByte()) return Kind.MODEL
        val text = head.toString(Charsets.ISO_8859_1)
        if (Regex("^[A-Za-z0-9+/]+={0,2} 0\\n").containsMatchIn(text)) return Kind.TOKENIZER
        val n = name.lowercase()
        return when {
            n.endsWith(".pte") -> Kind.MODEL
            n.startsWith("tokenizer") -> Kind.TOKENIZER
            else -> Kind.UNKNOWN
        }
    }

    /** Name to save a model under: Explainer needs "llama*.pte"; keep "qlora"/"8da4w" so it can label it. */
    fun modelTarget(name: String, size: Long): String {
        val n = name.lowercase()
        return when {
            size == ModelDownload.HF_MODEL.bytes -> ModelDownload.HF_MODEL.name
            size == ModelDownload.MODEL.bytes || "qlora" in n -> ModelDownload.MODEL.name
            "8da4w" in n -> "llama3_2_1b_instruct_8da4w_ctx2048.pte"
            else -> "llama_imported.pte"
        }
    }

    private fun meta(ctx: Context, u: Uri): Pair<String, Long> =
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
        } ?: (u.lastPathSegment.orEmpty() to -1L)

    private fun head(ctx: Context, u: Uri): ByteArray =
        ctx.contentResolver.openInputStream(u)?.use { i -> val b = ByteArray(64); val r = i.read(b); if (r > 0) b.copyOf(r) else ByteArray(0) }
            ?: throw IllegalStateException("Cannot open the picked file")

    fun start(ctx: Context, uris: List<Uri>) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true; progress = 0f
        status = "Checking files…"
        thread(name = "model-import") {
            try {
                val dir = Explainer.modelDir(app)
                data class Pick(val uri: Uri, val name: String, val size: Long, val kind: Kind)
                val picks = uris.map { u -> val (n, s) = meta(app, u); Pick(u, n, s, classify(head(app, u), n)) }
                picks.forEach { Log.i(TAG, "picked ${it.name} size=${it.size} kind=${it.kind} uri=${it.uri}") }
                val use = picks.filter { it.kind != Kind.UNKNOWN }
                if (use.isEmpty()) throw IllegalArgumentException("That is not a Llama model (.pte) or tokenizer.model")
                if (use.count { it.kind == Kind.MODEL } > 1 || use.count { it.kind == Kind.TOKENIZER } > 1)
                    throw IllegalArgumentException("Pick one model and one tokenizer")
                val total = use.sumOf { it.size.coerceAtLeast(0) }
                if (total > 0 && dir.usableSpace < total + (100L shl 20)) throw IllegalStateException("Not enough free space: need ${(total shr 20) + 100} MB")

                var done = 0L
                for (p in use) {
                    val target = if (p.kind == Kind.MODEL) modelTarget(p.name, p.size) else ModelDownload.TOKENIZER.name
                    val tmp = File(dir, "$target.part")
                    try {
                        app.contentResolver.openInputStream(p.uri)!!.use { i ->
                            tmp.outputStream().use { o ->
                                val buf = ByteArray(1 shl 20); var got = 0L; var r: Int; var shown = -1L
                                while (i.read(buf).also { r = it } > 0) {
                                    o.write(buf, 0, r); got += r
                                    if (got shr 26 != shown) {   // every 64 MB
                                        shown = got shr 26
                                        status = "Copying ${p.name} · ${got shr 20} MB"
                                        if (total > 0) progress = (done + got).toFloat() / total
                                    }
                                }
                                o.fd.sync()
                            }
                        }
                        val known = listOf(ModelDownload.MODEL, ModelDownload.HF_MODEL, ModelDownload.TOKENIZER).firstOrNull { it.name == target && it.bytes == tmp.length() }
                        if (p.kind == Kind.MODEL && tmp.length() < (100L shl 20))
                            throw IllegalStateException("${p.name} is only ${tmp.length() shr 20} MB: the download is incomplete")
                        if (target == ModelDownload.MODEL.name && known == null && p.size == ModelDownload.MODEL.bytes)
                            throw IllegalStateException("${p.name} was not copied completely, try again")
                        if (known != null) {
                            status = "Checking ${p.name}…"
                            if (ModelDownload.sha256(tmp) != known.sha256) throw IllegalStateException("${p.name} is damaged (checksum mismatch): download it again")
                        }
                        val out = File(dir, target)
                        out.delete()
                        if (!tmp.renameTo(out)) throw IllegalStateException("Could not save $target")
                        Log.i(TAG, "saved ${out.path} (${out.length()} bytes)")
                    } finally {
                        tmp.delete()
                    }
                    done += p.size.coerceAtLeast(0)
                }

                val ex = Explainer.get(app)
                ex.refreshFiles()
                val hasModel = dir.listFiles { f -> f.name.startsWith("llama") && f.name.endsWith(".pte") }?.isNotEmpty() == true
                val hasTok = File(dir, ModelDownload.TOKENIZER.name).length() > 0
                progress = 1f
                Log.i(TAG, "after import: available=${ex.available()} files=${ex.diagnose()}")
                status = when {
                    ex.available() -> "Llama installed"
                    !hasModel -> "Tokenizer saved. Now import the model (.pte)."
                    !hasTok -> "Model saved. Now import tokenizer.model."
                    else -> "Files saved but Llama was not found: ${ex.diagnose()}"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "import failed", t)
                status = t.message ?: "Import failed"
            } finally {
                busy = false
            }
        }
    }
}
