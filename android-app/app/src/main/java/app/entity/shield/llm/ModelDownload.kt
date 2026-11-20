package app.entity.shield.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
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
 * Mobile data: off by default (1.1 GB). The user can allow it once from the Llama card or keep it on
 * in the same place. A dropped connection (common on mobile data) is retried with backoff and resumes
 * where it stopped; the download runs under a foreground service so leaving the app does not kill it.
 *
 * Why not inside the APK: ExecuTorch loads the model from a file path, so a bundled model would be
 * copied out on first launch and occupy 2.2 GB (APK + copy) instead of 1.1 GB.
 */
object ModelDownload {
    const val BASE = "https://github.com/kkjjkamal123/ENTITY-Shield/releases/download/v0.4.0/"
    internal data class Item(val name: String, val sha256: String, val bytes: Long)
    internal val TOKENIZER = Item("tokenizer.model", "82e9d31979e92ab929cd544440f129d9ecd797b69e327f80f17e1c50d5551b55", 2_183_982)
    internal val MODEL = Item("llama3_2_1b_instruct_qlora_ctx2048.pte", "dc3a0369b5b57742e46e46d63ccd90def99e6e47747389c5152caa2afb629b15", 1_120_643_584)
    private val FILES = listOf(TOKENIZER, MODEL)
    val totalBytes = FILES.sumOf { it.bytes }

    private const val PREF_MOBILE = "llm_dl_mobile"
    private const val RETRIES = 8

    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    /** Mobile data allowed: the saved setting, or a one-off "download on mobile data anyway". */
    var mobileAllowed by mutableStateOf<Boolean?>(null); private set
    @Volatile private var cancel = false
    @Volatile private var oneOffMobile = false

    fun allowMobile(ctx: Context): Boolean =
        mobileAllowed ?: ctx.getSharedPreferences("shield", 0).getBoolean(PREF_MOBILE, false).also { mobileAllowed = it }

    fun setAllowMobile(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("shield", 0).edit().putBoolean(PREF_MOBILE, on).apply()
        mobileAllowed = on
    }

    /** True on mobile data (or any metered network). Shield's own VPN is skipped: it is not metered. */
    fun onMobileData(ctx: Context): Boolean = ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun stop() { cancel = true }

    /** [mobileOnce]: the user tapped "download on mobile data" for this download only. */
    fun start(ctx: Context, mobileOnce: Boolean = false) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true; cancel = false; oneOffMobile = mobileOnce; status = "Starting…"
        ModelDownloadService.start(app)
        thread(name = "model-download") {
            try {
                val dir = app.getExternalFilesDir("models") ?: throw IllegalStateException("No storage for the model")
                val need = FILES.sumOf { f -> if (File(dir, f.name).length() == f.bytes) 0L else f.bytes - File(dir, f.name + ".part").length().coerceAtMost(f.bytes) }
                if (dir.usableSpace < need + (200L shl 20)) throw IllegalStateException("Not enough free space: need ${(need shr 20) + 200} MB")
                var doneBefore = 0L
                for (f in FILES) {
                    val out = File(dir, f.name)
                    if (out.length() == f.bytes) { doneBefore += f.bytes; continue }
                    val part = File(dir, f.name + ".part")
                    if (part.length() > f.bytes) part.delete()
                    fetchWithRetry(app, BASE + f.name, part, f.bytes) { got ->
                        progress = (doneBefore + got).toFloat() / totalBytes
                        status = "${(doneBefore + got) shr 20} / ${totalBytes shr 20} MB"
                        ModelDownloadService.update(app, progress)
                    }
                    status = "Checking ${f.name}…"
                    if (sha256(part) != f.sha256) { part.delete(); throw IllegalStateException("${f.name} failed its checksum, tap Download to try again") }
                    out.delete()
                    if (!part.renameTo(out)) throw IllegalStateException("Could not save ${f.name}")
                    doneBefore += f.bytes
                }
                Explainer.get(app).refreshFiles()
                progress = 1f; status = "Llama installed"
            } catch (t: Throwable) {
                status = if (cancel) "Paused: tap Download to continue" else (t.message ?: "Download failed")
            } finally {
                busy = false
                ModelDownloadService.stop(app)
            }
        }
    }

    private class Blocked(msg: String) : Exception(msg)

    /** Download into [part], retrying dropped connections with backoff. Each attempt resumes. */
    private fun fetchWithRetry(ctx: Context, url: String, part: File, expect: Long, onProgress: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            if (cancel) throw InterruptedException("paused")
            if (onMobileData(ctx) && !allowMobile(ctx) && !oneOffMobile)
                throw Blocked("Paused: on mobile data. Allow mobile data below, or connect to Wi-Fi.")
            try {
                download(url, part, onProgress)
                if (part.length() == expect) return
                throw IOException("connection closed early")
            } catch (e: IOException) {
                if (cancel) throw InterruptedException("paused")
                if (++attempt > RETRIES) throw IllegalStateException("Network keeps dropping (${e.message}). Tap Download to resume.")
                val waitS = minOf(30, 2 shl minOf(attempt, 4))   // 4, 8, 16, 32→30 … s
                for (s in waitS downTo 1) {
                    if (cancel) throw InterruptedException("paused")
                    status = if (online(ctx)) "Connection dropped, retrying in $s s ($attempt/$RETRIES)" else "Waiting for a network… ($attempt/$RETRIES)"
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun download(url: String, part: File, onProgress: (Long) -> Unit) {
        var u = URL(url)
        repeat(6) {   // follow GitHub's redirect to its storage host (a fresh signed URL on every attempt)
            val have = if (part.exists()) part.length() else 0L
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 45_000
                setRequestProperty("User-Agent", "ENTITY-Shield")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); return@repeat }
                if (code == 416) return   // already complete
                if (code == 404 || code == 403 || code == 401) throw IllegalStateException(
                    "The download link is not reachable (HTTP $code): the v0.4.0 release must be public. Get the two files another way and use Import.")
                if (code != 200 && code != 206) throw IOException("server answered $code")
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
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); var r: Int; while (i.read(b).also { r = it } > 0) md.update(b, 0, r) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
