package app.entity.shield

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * What silicon this phone has, read from the kernel (same approach as ENTITY's DeviceProbe):
 * SoC name, core clusters by CPU part, Arm ISA features that matter to XNNPACK/KleidiAI, RAM.
 */
object DeviceInfo {
    data class Info(
        val soc: String, val cores: String, val features: List<Pair<String, Boolean>>,
        val ramTotalGb: Double, val ramFreeGb: Double, val backends: List<String>, val model: String,
    )

    private val PARTS = mapOf(
        "0xd03" to "A53", "0xd05" to "A55", "0xd07" to "A57", "0xd08" to "A72", "0xd09" to "A73", "0xd0a" to "A75",
        "0xd0b" to "A76", "0xd0d" to "A77", "0xd41" to "A78", "0xd44" to "X1", "0xd46" to "A510", "0xd47" to "A710",
        "0xd48" to "X2", "0xd4d" to "A715", "0xd4e" to "X3", "0xd80" to "A520", "0xd81" to "A720", "0xd82" to "X4",
        "0xd84" to "A725", "0xd85" to "X925", "0xd87" to "A725", "0xd8e" to "A320",
    )
    private val SOCS = mapOf(
        "MT6878" to "MediaTek Dimensity 7300", "SM7635" to "Snapdragon 7s Gen 3", "MT6897" to "MediaTek Dimensity 8300",
        "SM7550" to "Snapdragon 7 Gen 3", "SM8650" to "Snapdragon 8 Gen 3", "SM8750" to "Snapdragon 8 Elite",
        "MT6989" to "MediaTek Dimensity 9300", "MT6991" to "MediaTek Dimensity 9400", "MT6789" to "MediaTek Helio G99",
        "SM6375" to "Snapdragon 695", "MT6855" to "MediaTek Dimensity 7020",
    )

    @Volatile private var cached: Info? = null

    fun get(ctx: Context): Info = cached ?: probe(ctx).also { cached = it }

    private fun probe(ctx: Context): Info {
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val parts = Regex("CPU part\\s*:\\s*(0x[0-9a-f]+)").findAll(cpuinfo).map { it.groupValues[1] }.toList()
        val cores = parts.groupingBy { PARTS[it] ?: it }.eachCount().entries
            .sortedByDescending { e -> listOf("X925", "X4", "X3", "X2", "X1", "A725", "A720", "A715", "A710", "A78", "A77", "A76").indexOf(e.key).let { if (it < 0) -1 else 100 - it } }
            .joinToString(" + ") { "${it.value}× Cortex-${it.key}" }
        val feats = Regex("Features\\s*:\\s*(.*)").find(cpuinfo)?.groupValues?.get(1)?.split(" ")?.toSet().orEmpty()
        val features = listOf("dotprod" to ("asimddp" in feats), "i8mm" to ("i8mm" in feats), "bf16" to ("bf16" in feats),
            "fp16" to ("asimdhp" in feats), "SVE2" to ("sve2" in feats), "SME" to ("sme" in feats || "sme2" in feats))
        val raw = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val soc = SOCS[raw.uppercase()] ?: if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else raw
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val backends = runCatching { org.pytorch.executorch.ExecuTorchRuntime.getRegisteredBackends().toList() }.getOrDefault(emptyList())
        return Info(soc, cores.ifEmpty { "${Runtime.getRuntime().availableProcessors()} cores" }, features,
            mi.totalMem / 1e9, mi.availMem / 1e9, backends, "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun refreshRam(ctx: Context): Double {
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return mi.availMem / 1e9
    }
}
