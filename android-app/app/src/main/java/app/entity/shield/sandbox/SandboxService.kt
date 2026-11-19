package app.entity.shield.sandbox

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import app.entity.shield.core.Featurizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The link sandbox's reading room. Declared android:isolatedProcess="true": Android runs it as a
 * throw-away UID (u0_i...) with no permissions, no access to Shield's files or anyone else's, and no
 * network (no INTERNET permission, so the kernel refuses to open sockets). Untrusted page bytes are only
 * ever parsed here; if a crafted page ever broke the parser, it would wake up in an empty room.
 *
 * The page arrives as read-only shared memory, the report goes back as JSON over Binder.
 * PROBE proves the isolation on the device: it tries to read Shield's own files and to open a socket.
 */
class SandboxService : Service() {
    private val worker = HandlerThread("sandbox").apply { start() }
    private val messenger = Messenger(object : Handler(worker.looper) {
        override fun handleMessage(m: Message) {
            val out = Message.obtain(null, m.what)
            out.data = runCatching {
                when (m.what) {
                    ANALYZE -> analyze(m.data)
                    PROBE -> probe()
                    else -> Bundle()
                }
            }.getOrElse { Bundle().apply { putString("error", "${it.javaClass.simpleName}: ${it.message}") } }
            runCatching { m.replyTo?.send(out) }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() { worker.quitSafely(); super.onDestroy() }

    private fun analyze(b: Bundle): Bundle {
        val shm = if (Build.VERSION.SDK_INT >= 33) b.getParcelable("body", SharedMemory::class.java) else @Suppress("DEPRECATION") b.getParcelable("body")
        val len = b.getInt("len")
        val body = ByteArray(len)
        if (shm != null && len > 0) {
            val bb = shm.mapReadOnly()
            bb.get(body, 0, len)
            SharedMemory.unmap(bb)
            shm.close()
        }
        val t0 = System.nanoTime()
        val r = PageAnalyzer.analyze(
            PageInput(b.getString("url").orEmpty(), b.getString("ct").orEmpty(), b.getString("cd").orEmpty(), body, b.getStringArrayList("hops").orEmpty()),
            Brands.ALL, Featurizer.FREE_HOSTS, Featurizer::registrable,
        )
        return Bundle().apply {
            putString("report", Codec.report(r).toString())
            putDouble("ms", (System.nanoTime() - t0) / 1e6)
            putInt("uid", Process.myUid())
        }
    }

    private fun probe(): Bundle = Bundle().apply {
        putInt("uid", Process.myUid())
        putBoolean("isolated", Process.isIsolated())
        val dataDir = "/data/data/app.entity.shield"
        putBoolean("files_denied", File("$dataDir/files").list() == null && runCatching { File("$dataDir/shared_prefs/shield.xml").readBytes() }.isFailure)
        putBoolean("net_denied", runCatching {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 443), 1500) }
        }.isFailure)
    }

    companion object {
        const val ANALYZE = 1
        const val PROBE = 2
    }
}

/** What the isolated process proved about itself (shown on every sandbox report). */
data class Isolation(val uid: Int, val isolated: Boolean, val filesDenied: Boolean, val netDenied: Boolean)

/** Main-process side: binds the isolated service on demand and talks to it; unbinds after a minute idle. */
object SandboxClient {
    private val replies = HandlerThread("sandbox-reply").apply { start() }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var service: Messenger? = null
    private var conn: ServiceConnection? = null
    private var app: Context? = null
    private val unbind = Runnable { synchronized(this) { conn?.let { c -> runCatching { app?.unbindService(c) } }; conn = null; service = null } }
    @Volatile var isolation: Isolation? = null; private set

    @Synchronized
    private fun connect(ctx: Context): Messenger? {
        service?.let { return it }
        val latch = CountDownLatch(1)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) { service = b?.let { Messenger(it) }; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        app = ctx.applicationContext
        if (!ctx.applicationContext.bindService(Intent(ctx, SandboxService::class.java), c, Context.BIND_AUTO_CREATE)) return null
        conn = c
        latch.await(4, TimeUnit.SECONDS)
        return service
    }

    /** Never call on the main thread (binding completes there). */
    private fun call(ctx: Context, what: Int, data: Bundle, timeoutMs: Long): Bundle? {
        val m = connect(ctx) ?: return null
        val latch = CountDownLatch(1)
        var out: Bundle? = null
        val replyTo = Messenger(object : Handler(replies.looper) {
            override fun handleMessage(r: Message) { out = r.data; latch.countDown() }
        })
        runCatching { m.send(Message.obtain(null, what).apply { this.data = data; this.replyTo = replyTo }) }.onFailure { service = null; return null }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.removeCallbacks(unbind)
        main.postDelayed(unbind, 60_000)
        return out
    }

    fun probe(ctx: Context): Isolation? {
        isolation?.let { return it }
        val b = call(ctx, SandboxService.PROBE, Bundle(), 4000) ?: return null
        return Isolation(b.getInt("uid"), b.getBoolean("isolated"), b.getBoolean("files_denied"), b.getBoolean("net_denied")).also { isolation = it }
    }

    /** Parses the page inside the isolated process. null if the sandbox could not be reached. */
    fun analyze(ctx: Context, input: PageInput): Pair<PageReport, Double>? {
        val len = input.body.size
        val shm = SharedMemory.create("page", maxOf(1, len))
        val bb = shm.mapReadWrite()
        bb.put(input.body)
        SharedMemory.unmap(bb)
        shm.setProtect(OsConstants.PROT_READ)
        val data = Bundle().apply {
            putParcelable("body", shm); putInt("len", len)
            putString("url", input.finalUrl); putString("ct", input.contentType); putString("cd", input.contentDisposition)
            putStringArrayList("hops", ArrayList(input.hopUrls))
        }
        val out = call(ctx, SandboxService.ANALYZE, data, 6000)
        shm.close()
        val json = out?.getString("report") ?: return null
        return Codec.report(JSONObject(json)) to out.getDouble("ms")
    }
}

/** PageReport <-> JSON (crosses the process boundary, and is stored with the alert). */
object Codec {
    private fun arr(l: List<String>) = JSONArray().apply { l.forEach { put(it) } }
    private fun list(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    fun report(r: PageReport): JSONObject = JSONObject()
        .put("title", r.title).put("text", r.textSample)
        .put("fields", JSONArray().apply { r.fields.forEach { put(JSONObject().put("kind", it.kind).put("label", it.label)) } })
        .put("forms", r.forms).put("formActions", arr(r.formActions)).put("brands", arr(r.brands))
        .put("metaRefresh", r.metaRefresh ?: "").put("jsRedirect", r.jsRedirect ?: "")
        .put("scripts", r.scripts).put("externalScripts", r.externalScripts).put("iframes", r.iframes).put("links", r.links)
        .put("externalLinkRatio", r.externalLinkRatio.toDouble()).put("signals", arr(r.signals)).put("isHtml", r.isHtml)
        .put("bytes", r.bytes).put("devanagari", r.devanagari)

    fun report(o: JSONObject) = PageReport(
        title = o.optString("title"), textSample = o.optString("text"),
        fields = o.optJSONArray("fields")?.let { a -> List(a.length()) { a.getJSONObject(it).let { f -> Field(f.optString("kind"), f.optString("label")) } } } ?: emptyList(),
        forms = o.optInt("forms"), formActions = list(o.optJSONArray("formActions")), brands = list(o.optJSONArray("brands")),
        metaRefresh = o.optString("metaRefresh").ifEmpty { null }, jsRedirect = o.optString("jsRedirect").ifEmpty { null },
        scripts = o.optInt("scripts"), externalScripts = o.optInt("externalScripts"), iframes = o.optInt("iframes"), links = o.optInt("links"),
        externalLinkRatio = o.optDouble("externalLinkRatio", 0.0).toFloat(), signals = list(o.optJSONArray("signals")),
        isHtml = o.optBoolean("isHtml"), bytes = o.optInt("bytes"), devanagari = o.optBoolean("devanagari"),
    )
}
