package app.entity.shield.llm

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * One tap to put Llama on the phone: downloads the ExecuTorch model and tokenizer from the project's
 * GitHub release into Shield's own storage. Resumable (HTTP Range into a .part file), checked against
 * the published SHA-256 before it is used, and it never sends anything about the user: it is a plain
 * file download, once. Everything else in Shield stays offline.
 *
 * Why not inside the APK: ExecuTorch loads the model from a file path, so a bundled model would be
 * copied out on first launch and occupy 2.2 GB (APK + copy) instead of 1.1 GB.
 */
object ModelDownload {
    const val BASE = "https://github.com/kkjjkamal123/ENTITY-Shield/releases/download/v0.4.0/"
    private data class Item(val name: String, val sha256: String, val bytes: Long)
    private val FILES = listOf(
        Item("tokenizer.model", "82e9d31979e92ab929cd544440f129d9ecd797b69e327f80f17e1c50d5551b55", 2_183_982),
        Item("llama3_2_1b_instruct_qlora_ctx2048.pte", "dc3a0369b5b57742e46e46d63ccd90def99e6e47747389c5152caa2afb629b15", 1_120_643_584),
    )
    val totalBytes = FILES.sumOf { it.bytes }

    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    @Volatile private var cancel = false

    fun onMobileData(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    fun stop() { cancel = true }

    fun start(ctx: Context) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true; cancel = false; status = "Starting…"
        thread(name = "model-download") {
            try {
                val dir = app.getExternalFilesDir("models") ?: throw IllegalStateException("no storage")
                if (dir.usableSpace < totalBytes + (200L shl 20)) throw IllegalStateException("Not enough free space: need ${totalBytes shr 20} MB")
                var doneBefore = 0L
                for (f in FILES) {
                    val out = File(dir, f.name)
                    if (out.length() == f.bytes) { doneBefore += f.bytes; continue }
                    val part = File(dir, f.name + ".part")
                    download(BASE + f.name, part) { got -> progress = (doneBefore + got).toFloat() / totalBytes; status = "${(doneBefore + got) shr 20} / ${totalBytes shr 20} MB" }
                    status = "Checking ${f.name}…"
                    if (sha256(part) != f.sha256) { part.delete(); throw IllegalStateException("${f.name} failed its checksum, try again") }
                    if (!part.renameTo(out)) throw IllegalStateException("could not save ${f.name}")
                    doneBefore += f.bytes
                }
                Explainer.get(app).refreshFiles()
                progress = 1f; status = "Llama installed"
            } catch (t: Throwable) {
                status = if (cancel) "Paused: tap Download to continue" else (t.message ?: "Download failed")
            } finally {
                busy = false
            }
        }
    }

    private fun download(url: String, part: File, onProgress: (Long) -> Unit) {
        var u = URL(url)
        repeat(6) {   // follow GitHub's redirect to its storage host
            val have = if (part.exists()) part.length() else 0L
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code == 404) throw IllegalStateException("The model is not published yet (release v0.4.0)")
            if (code != 200 && code != 206) throw IllegalStateException("Server answered $code")
            val resume = code == 206
            RandomAccessFile(part, "rw").use { raf ->
                if (!resume) raf.setLength(0) else raf.seek(have)
                var got = if (resume) have else 0L
                c.inputStream.use { i ->
                    val buf = ByteArray(1 shl 16); var r: Int; var last = 0L
                    while (i.read(buf).also { r = it } > 0) {
                        if (cancel) throw InterruptedException("paused")
                        raf.write(buf, 0, r); got += r
                        if (got - last > (4L shl 20)) { onProgress(got); last = got }
                    }
                }
                onProgress(got)
            }
            return
        }
        throw IllegalStateException("too many redirects")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { i -> val b = ByteArray(1 shl 20); var r: Int; while (i.read(b).also { r = it } > 0) md.update(b, 0, r) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
