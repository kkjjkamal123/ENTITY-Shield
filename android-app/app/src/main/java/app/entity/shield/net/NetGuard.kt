package app.entity.shield.net

import app.entity.shield.core.Band
import app.entity.shield.core.Detectors
import app.entity.shield.core.Family
import app.entity.shield.core.Featurizer
import app.entity.shield.core.Pipeline
import app.entity.shield.core.Verdict

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.sqrt

/**
 * Network Guard, all on the phone (PS3 C1): what the local DNS VPN and the LAN tripwire see, the
 * behaviour detectors on top of them, and the live state the dashboard draws.
 *
 * Detectors (each one names the app or the device, and becomes a normal alert with fixed steps):
 *  - DOMAIN: DnsPolicy decides per lookup (known-phishing list, popularity, the app's own domains, then
 *            the URL model at a strict threshold). Only BLOCK answers NXDOMAIN and raises an alert.
 *  - BEACON: one app contacts the same non-allow-listed domain on a fixed timer (malware check-in).
 *  - DGA:    one app gets many "does not exist" answers for different names in a minute
 *            (malware searching for its command server among generated domains).
 *  - TUNNEL: many unique or long random subdomains under one domain (data hidden in DNS).
 *  - SCAN / BRUTE / ADB probe: another device on the Wi-Fi touches the tripwire ports.
 */
object NetGuard {
    private const val TAG = "ShieldNet"
    private val main = Handler(Looper.getMainLooper())

    data class DnsEvent(
        val at: Long, val uid: Int, val app: String, val pkg: String, val host: String, val qtype: Int,
        val action: String, val score: Float, val rcode: Int, val ms: Double,
    )

    data class TripEvent(val at: Long, val ip: String, val port: Int, val banner: String)

    // ---------------------------------------------------------------- live state (main thread)
    val dnsEvents = mutableStateListOf<DnsEvent>()
    val tripEvents = mutableStateListOf<TripEvent>()
    val listening = mutableStateListOf<Int>()
    var vpnOn by mutableStateOf(false)
    var queries by mutableIntStateOf(0)
    var blocked by mutableIntStateOf(0)
    var flagged by mutableIntStateOf(0)
    var tripHits by mutableIntStateOf(0)
    var phoneIp by mutableStateOf("")
    var upstream by mutableStateOf("")
    @Volatile var blocking = true

    /** "Stop scam sites" switch on the Network screen; remembered across restarts. */
    fun setBlocking(ctx: Context, on: Boolean) {
        blocking = on
        ctx.getSharedPreferences("shield", 0).edit().putBoolean("dns_block", on).apply()
    }
    private val appsSeen = HashSet<Int>()
    var apps by mutableIntStateOf(0)

    fun setVpn(on: Boolean) = main.post { vpnOn = on; if (!on) listening.clear() }
    fun setListening(ports: List<Int>) = main.post { listening.clear(); listening.addAll(ports) }

    // ---------------------------------------------------------------- app labels and icons
    data class AppId(val label: String, val pkg: String)
    private val labels = HashMap<Int, AppId>()
    private val icons = LruCache<String, Bitmap>(64)

    fun label(ctx: Context, uid: Int): AppId = synchronized(labels) {
        labels.getOrPut(uid) {
            when (uid) {
                -1 -> AppId("Unknown", "")
                0 -> AppId("Android (root)", "")
                1000 -> AppId("Android System", "android")
                2000 -> AppId("Shell (adb)", "com.android.shell")
                else -> {
                    val pm = ctx.packageManager
                    val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: pm.getNameForUid(uid) ?: "uid $uid"
                    val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    AppId(name, pkg)
                }
            }
        }
    }

    fun icon(ctx: Context, pkg: String): Bitmap? {
        if (pkg.isEmpty()) return null
        icons.get(pkg)?.let { return it }
        val d = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val bmp = if (d is BitmapDrawable && d.bitmap != null) d.bitmap else {
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c); b
        }
        icons.put(pkg, bmp)
        return bmp
    }

    // ---------------------------------------------------------------- per-domain verdicts (cached)
    private val verdicts = LruCache<String, DnsPolicy.Decision>(2048)

    fun judge(ctx: Context, host: String, uid: Int): DnsPolicy.Decision {
        val key = "$uid|$host"
        return verdicts.get(key) ?: DnsPolicy.decide(ctx, host, label(ctx, uid).pkg).also { verdicts.put(key, it) }
    }

    fun forgetVerdicts() = verdicts.evictAll()

    /** The user said a blocked site is fine: never block or flag it again. */
    fun allowSite(ctx: Context, host: String) {
        DnsPolicy.allow(ctx, host)
        verdicts.evictAll()
    }

    /** Called by the VPN for every answered (or blocked) query. */
    fun record(ctx: Context, uid: Int, host: String, qtype: Int, d: DnsPolicy.Decision, blockedIt: Boolean, rcode: Int, ms: Double) {
        val v = d.v
        val app = label(ctx, uid)
        val action = when { blockedIt -> "BLOCK"; d.action != DnsPolicy.Action.ALLOW -> "FLAG"; else -> "ALLOW" }
        val ev = DnsEvent(System.currentTimeMillis(), uid, app.label, app.pkg, host, qtype, action, v.score, rcode, ms)
        main.post {
            queries++
            if (blockedIt) blocked++
            if (action == "FLAG") flagged++
            if (appsSeen.add(uid)) apps = appsSeen.size
            dnsEvents.add(0, ev)
            if (dnsEvents.size > 400) dnsEvents.removeRange(400, dnsEvents.size)
        }
        // One alert per app and site every 6 hours (a malware burst would otherwise post dozens);
        // every block is still counted and listed live on the Network screen. FLAG never alerts.
        if (blockedIt) alertOnce(ctx, "dom|$uid|${Featurizer.registrable(host)}", 6 * 60 * 60_000L) {
            val known = d.reason == "known_phish"
            val t = Verdict("net", Band.SCAM, v.score, Family.NET_DOMAIN, listOf("dns_blocked") + v.evidence + "pkg:${app.pkg}", host, v.millis)
            Pipeline.onNet(ctx, t, "${app.label} tried to open $host. " +
                (if (known) "It is on a public list of reported phishing sites, " else "It looks like a scam site, ") +
                "so Shield stopped it before any connection was made. If you trust this site, tap Allow this site.", app.label)
        }
        if (!v.allowlisted) behaviour(ctx, uid, app, host, rcode, v.band)
    }

    // ---------------------------------------------------------------- behaviour detectors
    private val times = HashMap<String, ArrayDeque<Long>>()                 // uid|registrable -> query times
    private val nx = HashMap<Int, ArrayDeque<Pair<Long, String>>>()          // uid -> NXDOMAIN (time, host)
    private val subs = HashMap<String, ArrayDeque<Pair<Long, String>>>()     // uid|registrable -> (time, label)
    private val lastAlert = HashMap<String, Long>()

    @Synchronized
    private fun behaviour(ctx: Context, uid: Int, app: AppId, host: String, rcode: Int, band: Band) {
        val now = System.currentTimeMillis()
        val reg = Featurizer.registrable(host)
        val key = "$uid|$reg"

        // BEACON: >= 6 lookups of one domain at a near-constant interval (3 s .. 30 min)
        val ts = times.getOrPut(key) { ArrayDeque() }
        ts.addLast(now); while (ts.size > 12) ts.removeFirst()
        // Only for domains that already look risky: plenty of honest apps poll their own server on a
        // timer, and calling that "malware" would be a false alarm.
        if (ts.size >= 6 && band != Band.SAFE) {
            val gaps = ts.zipWithNext { a, b -> (b - a).toDouble() }
            val mean = gaps.average()
            val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
            if (mean in 3_000.0..1_800_000.0 && sd / mean < 0.25) alertOnce(ctx, "beacon|$key", 15 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.9f, Family.NET_BEACON, listOf("beacon", "interval_${(mean / 1000).toInt()}s", "pkg:${app.pkg}"), reg, 0.0)
                Pipeline.onNet(ctx, v, "${app.label} contacts $reg every ${(mean / 1000).toInt()} seconds, ${ts.size} times in a row, like malware checking in with its server.", app.label)
            }
        }

        // DGA: >= 8 different non-existent names for one app within 60 s
        if (rcode == 3) {
            val q = nx.getOrPut(uid) { ArrayDeque() }
            q.addLast(now to host); while (q.isNotEmpty() && now - q.first().first > 60_000) q.removeFirst()
            val distinct = q.map { it.second }.toSet().size
            if (distinct >= 8) alertOnce(ctx, "dga|$uid", 15 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.85f, Family.NET_DGA, listOf("dga_burst", "nxdomain_$distinct", "pkg:${app.pkg}"), host, 0.0)
                Pipeline.onNet(ctx, v, "${app.label} looked up $distinct names that do not exist in one minute (e.g. $host), the way malware searches for its hidden server.", app.label)
            }
        }

        // TUNNEL: many unique subdomains under one domain, or long random labels
        val label = host.removeSuffix(reg).trimEnd('.')
        if (label.isNotEmpty()) {
            val s = subs.getOrPut(key) { ArrayDeque() }
            s.addLast(now to label); while (s.isNotEmpty() && now - s.first().first > 60_000) s.removeFirst()
            val unique = s.map { it.second }.toSet()
            val longRandom = unique.count { it.replace(".", "").length >= 30 && entropy(it) > 3.5 }
            if (unique.size >= 15 || longRandom >= 3) alertOnce(ctx, "tunnel|$key", 15 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.85f, Family.NET_TUNNEL, listOf("tunnel", "subdomains_${unique.size}", "pkg:${app.pkg}"), reg, 0.0)
                Pipeline.onNet(ctx, v, "${app.label} sent ${unique.size} different encoded names under $reg in one minute. Data can be smuggled out this way.", app.label)
            }
        }
    }

    fun entropy(s: String): Double = DnsPacket.entropy(s)

    // ---------------------------------------------------------------- LAN tripwire
    private val trip = HashMap<String, ArrayDeque<Pair<Long, Int>>>()

    @Synchronized
    fun onTrip(ctx: Context, ip: String, port: Int, banner: String) {
        val now = System.currentTimeMillis()
        main.post {
            tripHits++
            tripEvents.add(0, TripEvent(now, ip, port, banner))
            if (tripEvents.size > 200) tripEvents.removeRange(200, tripEvents.size)
        }
        Log.i(TAG, "tripwire ip=$ip port=$port banner=${banner.take(40)}")
        val q = trip.getOrPut(ip) { ArrayDeque() }
        q.addLast(now to port); while (q.isNotEmpty() && now - q.first().first > 60_000) q.removeFirst()
        val ports = q.filter { now - it.first <= 30_000 }.map { it.second }.toSortedSet()
        val samePort = q.count { it.second == port }
        when {
            port == 5555 -> alertOnce(ctx, "adb|$ip", 5 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.95f, Family.NET_SCAN, listOf("adb_probe", "port_5555"), ip, 0.0)
                Pipeline.onNet(ctx, v, "$ip on this Wi-Fi tried to connect to your phone's Android debugging port (5555), which worms like ADB.Miner use to take over devices.", ip)
            }
            ports.size >= 3 -> alertOnce(ctx, "scan|$ip", 5 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.9f, Family.NET_SCAN, listOf("port_scan", "ports_${ports.joinToString("_")}"), ip, 0.0)
                Pipeline.onNet(ctx, v, "$ip on this Wi-Fi probed ${ports.size} ports on your phone (${ports.joinToString(", ")}) within 30 seconds. That is a port scan.", ip)
            }
            samePort >= 5 -> alertOnce(ctx, "brute|$ip|$port", 5 * 60_000L) {
                val v = Verdict("net", Band.SCAM, 0.85f, Family.NET_BRUTE, listOf("brute_force", "port_$port"), ip, 0.0)
                Pipeline.onNet(ctx, v, "$ip on this Wi-Fi connected to port $port $samePort times in a minute, like a password-guessing attack.", ip)
            }
        }
    }

    private fun alertOnce(ctx: Context, key: String, windowMs: Long, fire: () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lastAlert) {
            if (now - (lastAlert[key] ?: 0L) < windowMs) return
            lastAlert[key] = now
        }
        runCatching(fire).onFailure { Log.w(TAG, "alert failed: ${it.message}") }
    }

    /** Tests only: forget behaviour history and alert de-duplication, so each run starts clean. */
    fun resetForTest() {
        synchronized(this) { times.clear(); nx.clear(); subs.clear(); trip.clear() }
        synchronized(lastAlert) { lastAlert.clear() }
        verdicts.evictAll()
        reset()
    }

    fun reset() = main.post {
        dnsEvents.clear(); tripEvents.clear(); queries = 0; blocked = 0; flagged = 0; tripHits = 0; apps = 0; appsSeen.clear()
    }
}
