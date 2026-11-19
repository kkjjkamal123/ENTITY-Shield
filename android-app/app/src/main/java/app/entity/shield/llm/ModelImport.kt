package app.entity.shield.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * Puts the Llama model on the phone without a PC: the user picks llama*.pte and tokenizer.model (from
 * Downloads, a USB stick, anywhere the system file picker reaches) and they are copied into Shield's own
 * storage, where Explainer finds them. 1.1 GB takes about 10-20 s on UFS storage.
 */
object ModelImport {
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    private fun name(ctx: Context, u: Uri): String =
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: u.lastPathSegment.orEmpty()

    fun start(ctx: Context, uris: List<Uri>) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true
        status = "Checking files…"
        thread(name = "model-import") {
            try {
                val dir = app.getExternalFilesDir("models") ?: throw IllegalStateException("no storage")
                val picked = uris.map { it to name(app, it) }.filter { (_, n) -> (n.startsWith("llama") && n.endsWith(".pte")) || n == "tokenizer.model" }
                if (picked.none { it.second.endsWith(".pte") } || picked.none { it.second == "tokenizer.model" })
                    throw IllegalArgumentException("Pick both llama…pte and tokenizer.model")
                for ((u, n) in picked) {
                    val tmp = File(dir, "$n.part")
                    app.contentResolver.openInputStream(u)!!.use { i ->
                        tmp.outputStream().buffered(1 shl 20).use { o ->
                            val buf = ByteArray(1 shl 20); var total = 0L; var r: Int
                            while (i.read(buf).also { r = it } > 0) {
                                o.write(buf, 0, r); total += r
                                if (total % (64L shl 20) < r) status = "Copying $n · ${total shr 20} MB"
                            }
                        }
                    }
                    if (!tmp.renameTo(File(dir, n))) throw IllegalStateException("could not save $n")
                }
                Explainer.get(app).refreshFiles()
                status = "Llama installed"
            } catch (t: Throwable) {
                status = t.message ?: "Import failed"
            } finally {
                busy = false
            }
        }
    }
}
