package app.entity.shield.llm

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * Llama 3.2 1B on ExecuTorch. Runs only for flagged items, one request at a time on its own thread,
 * so detection never waits for it. Loaded on demand, freed after IDLE_MS.
 *
 * Thread priority (measured 2026-11-19, CMF Phone 1): THREAD_PRIORITY_BACKGROUND makes Android move the
 * thread to cpuset:/background, and ExecuTorch's XNNPACK workers inherit it, so the whole model ran on
 * the Cortex-A55s (prefill 25 tok/s instead of ~85). The explanation is only generated while the alert
 * is on screen, so it runs at normal priority; the stay-out-of-the-way rule is enforced by *when* it
 * runs (never in the detection path), not by starving it.
 */
class Explainer private constructor(private val ctx: Context) {
    interface Listener {
        fun onToken(piece: String)
        fun onDone(stats: Stats)
        fun onError(msg: String)
    }

    data class Stats(
        val loadMs: Long, val ttftMs: Long, val totalMs: Long, val tokens: Int, val raw: String,
        val model: String = "", val prewarmed: Boolean = false,
    ) {
        private fun num(key: String): Double? = Regex("\"$key\":([0-9.eE+-]+)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val prefillTps get() = num("prefill_token_per_sec")
        val decodeTps get() = num("decode_token_per_sec")
        val promptTokens get() = num("prompt_tokens")?.toInt()
    }

    private val priority = ctx.getSharedPreferences("shield", 0).getInt("llm_priority", Process.THREAD_PRIORITY_DEFAULT)
    private val thread = HandlerThread("llama", priority).apply { start() }
    private val h = Handler(thread.looper)
    private var module: LlmModule? = null
    private val boost = LlmBoost(ctx)
    private var modelName = ""
    @Volatile var loaded = false; private set
    private var warmPrefix: String? = null   // system prefix already in the KV cache, if any
    private val unload = Runnable { module?.close(); module = null; loaded = false; warmPrefix = null; Log.i(TAG, "unloaded after idle") }

    /**
     * Where to look for the model, in order: Shield's private storage (where Download and Import put
     * it), then app-specific external storage (adb push), then /data/local/tmp/shield (benchmarks).
     */
    private fun dirs(): List<File> = listOfNotNull(modelDir(ctx), ctx.getExternalFilesDir("models"), ctx.getExternalFilesDir(null), File("/data/local/tmp/shield"))

    fun files(): Pair<File, File>? {
        val pref = ctx.getSharedPreferences("shield", 0).getString("llm_model", "qlora") ?: "qlora"
        val dirs = dirs()
        for (d in dirs) {
            // A half-copied or half-downloaded model fails inside LlmModule.load(); skip it here so the
            // UI offers Download / Import again instead of reporting Llama as installed.
            val ptes = d.listFiles { f -> f.name.startsWith("llama") && f.name.endsWith(".pte") && f.canRead() && complete(f) }?.toList().orEmpty()
            val pte = ptes.firstOrNull { pref in it.name } ?: ptes.maxByOrNull { it.name } ?: continue
            val tok = File(d, "tokenizer.model")
            if (tok.canRead() && tok.length() > 0) return pte to tok
        }
        return null
    }

    // The UI asks on every frame it draws; listing external storage there would stall it. Cache the
    // answer (refreshed at most every 30 s, and only ever from a background thread).
    @Volatile private var cachedFiles: Pair<File, File>? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var checking = false
    @Volatile private var generation = 0   // bumped by refreshFiles(): a background check started earlier must not overwrite it
    private fun cached(): Pair<File, File>? {
        val now = System.currentTimeMillis()
        if (now - checkedAt > 30_000 && !checking) {
            checking = true
            if (checkedAt == 0L) { cachedFiles = files(); checkedAt = now; checking = false }   // very first call (startup thread)
            else {
                val gen = generation
                kotlin.concurrent.thread(name = "llm-files") {
                    val f = files()
                    if (gen == generation) { cachedFiles = f; checkedAt = System.currentTimeMillis() }
                    checking = false
                }
            }
        }
        return cachedFiles
    }

    /** One line on what is on the phone, shown when Llama is not usable, so a bad copy can be told apart from none. */
    fun diagnose(): String {
        val seen = dirs().flatMap { d ->
            d.listFiles { f -> (f.name.startsWith("llama") && (f.name.endsWith(".pte") || f.name.endsWith(".part"))) || f.name.startsWith("tokenizer") }
                ?.map { f -> "${f.name} ${f.length() shr 20} MB" + (if (!f.canRead()) " (cannot read)" else if (f.name.endsWith(".pte") && !complete(f)) " (incomplete)" else "") + " in ${d.path}" }
                .orEmpty()
        }
        return if (seen.isEmpty()) "No model files found" else seen.joinToString("; ")
    }

    private fun complete(f: File) = when (f.name) {
        ModelDownload.MODEL.name -> f.length() == ModelDownload.MODEL.bytes
        else -> f.length() > (100L shl 20)
    }

    /** Why the installed model would not load, if it did not; cleared by a new download or import. */
    @Volatile var loadError: String? = null; private set

    /** After a model import or download: look again now, and forget an earlier load failure. */
    fun refreshFiles() {
        h.post { module?.close(); module = null; loaded = false; warmPrefix = null }   // pick up the new files on next use
        loadError = null
        generation++
        val f = files()
        cachedFiles = f; checkedAt = System.currentTimeMillis()
        // Installed in private storage: older copies in the shared folder are only duplicates taking 1.1 GB.
        if (f != null && f.first.parentFile == modelDir(ctx)) ctx.getExternalFilesDir("models")?.listFiles { x ->
            x.name.startsWith("llama") || x.name.startsWith("tokenizer")
        }?.forEach { runCatching { it.delete() } }
    }

    fun available() = cached() != null
    fun modelLabel(): String = cached()?.first?.name?.let { if ("qlora" in it) "Llama 3.2 1B · QAT+LoRA 4-bit" else "Llama 3.2 1B · 8da4w 4-bit" } ?: "not installed"

    private fun ensureLoaded(): Long {
        if (module != null) return 0
        val t0 = System.currentTimeMillis()
        val (pte, tok) = files() ?: throw IllegalStateException("Llama model not found. Install it in More, On-device AI.")
        val m = LlmModule(pte.absolutePath, tok.absolutePath, 0f)
        try { m.load() } catch (e: Throwable) {
            m.close(); loadError = e.message ?: "load failed"
            throw IllegalStateException("Llama could not load ${pte.name} (${e.message}). Reinstall it in More, On-device AI.", e)
        }
        loadError = null
        module = m
        modelName = pte.name
        loaded = true
        return System.currentTimeMillis() - t0
    }

    /**
     * Idle-time optimisation: load the model and put the fixed system prefix into the KV cache before
     * an alert needs it, so the alert pays only for its own tokens. [prefix] must be exactly the start
     * of the prompt that [run] will later get with usePrefix = true.
     */
    fun prewarm(prefix: String) {
        h.removeCallbacks(unload)
        h.post {
            try {
                ensureLoaded()
                if (warmPrefix != prefix) {
                    module!!.resetContext()
                    val t = System.currentTimeMillis()
                    // Experiment switch (see docs/PLAN.md): does prefillPrompt() add its own BOS? If so, the literal
                    // <|begin_of_text|> must be dropped from the prefilled text to match the cold prompt exactly.
                    val stripBos = ctx.getSharedPreferences("shield", 0).getBoolean("prefill_strip_bos", false)
                    module!!.prefillPrompt(if (stripBos) prefix.removePrefix("<|begin_of_text|>") else prefix)
                    warmPrefix = prefix
                    Log.i(TAG, "prewarmed ${prefix.length} chars in ${System.currentTimeMillis() - t} ms")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "prewarm failed: ${e.message}")
                warmPrefix = null
            } finally {
                h.postDelayed(unload, IDLE_MS)
            }
        }
    }

    /** [prompt] is the full prompt. If [prefix] is given and already prefilled, only the remainder is sent. */
    fun run(prompt: String, maxNew: Int, l: Listener, prefix: String? = null) {
        h.removeCallbacks(unload)
        h.post {
            var loadMs = 0L
            try {
                loadMs = ensureLoaded()
                val warm = prefix != null && warmPrefix == prefix && prompt.startsWith(prefix)
                val input = if (warm) prompt.substring(prefix!!.length) else prompt
                if (!warm) module!!.resetContext()
                warmPrefix = null // this run consumes the cache position either way
                val tStart = System.currentTimeMillis()
                var first = 0L
                var n = 0
                var stats = ""
                var ended = false
                val cfg = LlmGenerationConfig.create().echo(false).maxNewTokens(maxNew).temperature(0f).numBos(0).build()
                boost.begin(LlmBoost.DECODE_TARGET_NS)
                module!!.generate(input, cfg, object : LlmCallback {
                    override fun onResult(result: String) {
                        if (ended) return
                        boost.step()
                        if (n == 0) first = System.currentTimeMillis() - tStart
                        n++
                        if (SPECIAL.containsMatchIn(result)) {
                            val clean = SPECIAL.replace(result, "")
                            if (clean.isNotEmpty()) l.onToken(clean)
                            if ("<|eot_id|>" in result || "<|end_of_text|>" in result) { ended = true; module?.stop() }
                            return
                        }
                        l.onToken(result)
                    }
                    override fun onStats(s: String) { stats = s }
                    override fun onError(code: Int, msg: String) = l.onError("Llama error $code: $msg")
                })
                boost.end()
                val st = Stats(loadMs, first, System.currentTimeMillis() - tStart, n, stats, modelName, warm)
                Log.i(TAG, "run model=$modelName warm=$warm prio=$priority loadMs=$loadMs ttftMs=$first tokens=$n prefill=${st.prefillTps} decode=${st.decodeTps}")
                module!!.resetContext()
                l.onDone(st)
            } catch (e: Throwable) {
                boost.end()
                module?.close(); module = null; loaded = false; warmPrefix = null
                l.onError("Llama failed: ${e.message}")
            } finally {
                h.postDelayed(unload, IDLE_MS)
            }
        }
    }

    fun stop() = module?.stop()

    companion object {
        private const val TAG = "ShieldLLM"
        const val IDLE_MS = 90_000L
        private val SPECIAL = Regex("<\\|[a-z_]+\\|>")
        @Volatile private var inst: Explainer? = null

        /**
         * Download and Import save the model here: Shield's private internal storage. Not the shared
         * Android/data folder, where a folder made by adb before the app's first start can end up
         * unreadable by the app, and phone cleaners may delete large files.
         */
        fun modelDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Explainer(ctx.applicationContext).also { inst = it } }
    }
}
