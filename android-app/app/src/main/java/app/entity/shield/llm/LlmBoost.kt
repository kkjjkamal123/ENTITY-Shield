package app.entity.shield.llm

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import java.io.File

/**
 * ADPF deadline hints for Llama (the method ported from ENTITY, docs/OPTIMIZATIONS.md §2b there).
 *
 * An app cannot set CPU affinity or clocks, and ExecuTorch's Java API cannot set the thread count.
 * Android's Performance Hint API is the sanctioned lever: register threads with a target work
 * duration, report how long each step really took, and the vendor's scheduler raises clocks or moves
 * the threads to bigger cores when the target is missed.
 *
 * ENTITY could only register its decode thread, because ggml's worker threads expose no TID. Here
 * the XNNPACK workers can be found: Linux threads inherit their creator's name, so every worker
 * ExecuTorch starts from the "llama" HandlerThread is also called "llama" in /proc/self/task.
 *
 * Inert where the vendor has no hint HAL (session is null). Off with prefs "llm_adpf" = false.
 */
class LlmBoost(private val ctx: Context) {
    private var session: PerformanceHintManager.Session? = null
    private var target = 0L
    private var last = 0L

    /** TIDs of this process's threads named [name]: the llama thread and the workers it started. */
    private fun tids(name: String): IntArray =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
            runCatching { if (File(t, "comm").readText().trim() == name) t.name.toInt() else null }.getOrNull()
        }.toIntArray()

    /** Open a session for one generation. [targetNs] = time we want per decoded token. */
    fun begin(targetNs: Long) {
        end()
        if (!ctx.getSharedPreferences("shield", 0).getBoolean("llm_adpf", true)) return
        try {
            val mgr = ctx.getSystemService(PerformanceHintManager::class.java) ?: return
            val ids = tids(Thread.currentThread().name).let { if (it.isEmpty()) intArrayOf(Process.myTid()) else it }
            session = mgr.createHintSession(ids, targetNs)
            if (session != null && Build.VERSION.SDK_INT >= 35) session?.setPreferPowerEfficiency(false)
            target = targetNs
            steps = 0
            last = System.nanoTime()
            Log.i(TAG, "hint session over ${ids.size} threads, target ${targetNs / 1_000_000} ms: ${if (session != null) "on" else "not supported"}")
        } catch (t: Throwable) {
            session = null
            Log.w(TAG, "no hint session: ${t.message}")
        }
    }

    /** Call once per token from the generating thread: reports the step and retargets with hysteresis. */
    fun step() {
        val s = session ?: return
        val now = System.nanoTime()
        val d = now - last
        last = now
        if (d <= 0) return
        runCatching { s.reportActualWorkDuration(d) }
        // On a cold start ExecuTorch creates its worker pool during the first prefill, after begin():
        // add the workers once they exist.
        if (++steps == 1 && Build.VERSION.SDK_INT >= 34) runCatching {
            val ids = tids(Thread.currentThread().name)
            if (ids.size > 1) s.setThreads(ids)
        }
    }
    private var steps = 0

    fun end() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val TAG = "ShieldLLM"
        /** 50 ms per token = 20 tok/s, about what the A720/A78 cores reach; slower steps ask for a boost. */
        const val DECODE_TARGET_NS = 50_000_000L
    }
}
