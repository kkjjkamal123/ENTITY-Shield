package app.entity.shield

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * Llama 3.2 1B on ExecuTorch. Runs only for flagged items, one request at a time on its own
 * low-priority thread, so detection never waits for it. Loaded on demand, freed after IDLE_MS.
 */
class Explainer private constructor(private val ctx: Context) {
    interface Listener {
        fun onToken(piece: String)
        fun onDone(stats: Stats)
        fun onError(msg: String)
    }

    data class Stats(val loadMs: Long, val ttftMs: Long, val totalMs: Long, val tokens: Int, val raw: String)

    private val thread = HandlerThread("llama", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val h = Handler(thread.looper)
    private var module: LlmModule? = null
    private val unload = Runnable { module?.close(); module = null }

    /** Model files: app-specific external storage (adb push works without permissions), then /data/local/tmp/shield. */
    fun files(): Pair<File, File>? {
        val dirs = listOfNotNull(ctx.getExternalFilesDir(null), File("/data/local/tmp/shield"))
        for (d in dirs) {
            val pte = d.listFiles { f -> f.name.startsWith("llama") && f.name.endsWith(".pte") }?.maxByOrNull { it.name } ?: continue
            val tok = File(d, "tokenizer.model")
            if (tok.canRead() && pte.canRead()) return pte to tok
        }
        return null
    }

    fun available() = files() != null

    fun run(prompt: String, maxNew: Int, l: Listener) {
        h.removeCallbacks(unload)
        h.post {
            val t0 = System.currentTimeMillis()
            var loadMs = 0L
            try {
                if (module == null) {
                    val (pte, tok) = files() ?: return@post l.onError("Llama model not found. Push llama*.pte and tokenizer.model (see README).")
                    module = LlmModule(pte.absolutePath, tok.absolutePath, 0f).also { it.load() }
                    loadMs = System.currentTimeMillis() - t0
                }
                val tStart = System.currentTimeMillis()
                var first = 0L
                var n = 0
                var stats = ""
                val cfg = LlmGenerationConfig.Builder().echo(false).maxNewTokens(maxNew).temperature(0f).build()
                module!!.generate(prompt, cfg, object : LlmCallback {
                    override fun onResult(result: String) {
                        if (n == 0) first = System.currentTimeMillis() - tStart
                        n++
                        l.onToken(result)
                    }
                    override fun onStats(s: String) { stats = s }
                    override fun onError(code: Int, msg: String) = l.onError("Llama error $code: $msg")
                })
                module!!.resetContext()
                l.onDone(Stats(loadMs, first, System.currentTimeMillis() - tStart, n, stats))
            } catch (e: Throwable) {
                module?.close(); module = null
                l.onError("Llama failed: ${e.message}")
            } finally {
                h.postDelayed(unload, IDLE_MS)
            }
        }
    }

    fun stop() = module?.stop()

    companion object {
        const val IDLE_MS = 90_000L
        @Volatile private var inst: Explainer? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Explainer(ctx.applicationContext).also { inst = it } }
    }
}
