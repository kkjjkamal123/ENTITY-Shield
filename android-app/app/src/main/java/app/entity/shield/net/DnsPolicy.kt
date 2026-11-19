package app.entity.shield.net

import app.entity.shield.core.Band
import app.entity.shield.core.Detectors
import app.entity.shield.core.Family
import app.entity.shield.core.Featurizer
import app.entity.shield.core.Verdict

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Network Guard does with one DNS lookup. Blocking breaks the app that asked, so a block needs
 * real evidence; the URL model alone is never enough.
 *
 * v1 (until 2026-11-19) blocked whenever the URL model said "scam". The model was trained on bare
 * domains (flipkart.com), but apps look up hostnames (connekt.flipkart.net, api.adapty.io), and it
 * blocked 21.5% of real long-tail lookups (scripts/eval_dns_policy.py). v2, in order:
 *
 *  1. local names, OS/CDN infrastructure, sites the user allowed  -> ALLOW
 *  2. a hostname on the bundled PhishTank/OpenPhish list           -> BLOCK (known phishing)
 *  3. official/top-10k allow-list, any Tranco top-1M domain,
 *     or a domain that belongs to the app asking (Flipkart -> *.flipkart.net) -> ALLOW
 *  4. model >= the DNS threshold (0.1% false alarms on real DNS names)  -> BLOCK, except a subdomain
 *     of a free host (firebaseapp.com, pages.dev, github.io...) looked up by an app that is not a
 *     browser: many apps run their backend there, so that is only flagged
 *  5. model >= the link threshold                                  -> FLAG (listed, never blocked or notified)
 *
 * Measured (scripts/eval_dns_policy.py, benchmarks/results/dns_policy_eval_2026-11-19.json): wrong
 * blocks on held-out real DNS names 10.5% -> 0.12%, long-tail domains 21.5% -> 0.03%; still blocks 87%
 * of held-out OpenPhish domains by the model alone, plus every host on the bundled feed list.
 */
object DnsPolicy {
    enum class Action { ALLOW, FLAG, BLOCK }
    data class Decision(val action: Action, val reason: String, val v: Verdict)

    private val LOCAL = listOf(".arpa", ".local", ".lan", ".home", ".internal", ".localdomain")
    @Volatile private var popular: IntArray? = null
    @Volatile private var phish: IntArray? = null
    private val userAllowed = HashSet<String>()
    @Volatile private var browsers: Set<String> = emptySet()
    @Volatile private var loadedUser = false

    private fun loadHashes(ctx: Context, name: String): IntArray = runCatching {
        val b = ctx.assets.open(name).use { it.readBytes() }
        val ib = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        IntArray(ib.remaining()).also { ib.get(it) }
    }.getOrDefault(IntArray(0))

    /** Loads the 4 MB popularity list and the phishing list once (about 20 ms), off the main thread. */
    fun warm(ctx: Context) {
        if (popular == null) synchronized(this) {
            if (popular == null) {
                phish = loadHashes(ctx, "phish.bin")
                popular = loadHashes(ctx, "popular.bin")
                browsers = runCatching {
                    ctx.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
                        android.content.pm.PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.filter { it != ctx.packageName }.toSet()
                }.getOrDefault(emptySet())
            }
        }
        if (!loadedUser) synchronized(userAllowed) {
            if (!loadedUser) {
                userAllowed += ctx.getSharedPreferences("shield", 0).getStringSet("dns_allow", emptySet()).orEmpty()
                loadedUser = true
            }
        }
    }

    private fun h32(s: String) = Featurizer.fnv1a32(s.toByteArray(Charsets.UTF_8)).toInt()
    private fun inList(a: IntArray?, s: String) = a != null && a.isNotEmpty() && java.util.Arrays.binarySearch(a, h32(s)) >= 0
    private fun on(host: String, d: String) = host == d || host.endsWith(".$d")

    fun isPopular(host: String) = inList(popular, Featurizer.registrable(host))
    fun isKnownPhish(host: String) = inList(phish, host)

    /** The app's own infrastructure: the domain's name is one of the package name's parts. */
    fun ownDomain(host: String, pkg: String): Boolean {
        val name = Featurizer.registrable(host).substringBefore('.')
        return name.length >= 3 && pkg.isNotEmpty() && pkg.lowercase().replace('_', '.').split('.').contains(name)
    }

    fun allow(ctx: Context, host: String) {
        warm(ctx)
        val reg = Featurizer.registrable(host)
        synchronized(userAllowed) {
            userAllowed += reg
            ctx.getSharedPreferences("shield", 0).edit().putStringSet("dns_allow", HashSet(userAllowed)).apply()
        }
    }

    fun allowed(): List<String> = synchronized(userAllowed) { userAllowed.sorted() }

    fun disallow(ctx: Context, reg: String) = synchronized(userAllowed) {
        userAllowed -= reg
        ctx.getSharedPreferences("shield", 0).edit().putStringSet("dns_allow", HashSet(userAllowed)).apply()
    }

    private fun isUserAllowed(host: String) = synchronized(userAllowed) { userAllowed.any { on(host, it) } }

    fun decide(ctx: Context, name: String, pkg: String): Decision {
        warm(ctx)
        val det = Detectors.get(ctx)
        val host = name.trim('.').lowercase()
        val safe = { why: String -> Decision(Action.ALLOW, why, Verdict("url", Band.SAFE, 0f, Family.LINK, emptyList(), host, 0.0, true)) }
        if ('.' !in host || LOCAL.any { host.endsWith(it) }) return safe("local")
        if (det.isInfra(host)) return safe("infra")
        if (isUserAllowed(host)) return safe("user")
        if (isKnownPhish(host)) {
            val v = det.checkUrl(host)
            return Decision(Action.BLOCK, "known_phish", v.copy(band = Band.SCAM, score = maxOf(v.score, 0.99f), evidence = listOf("known_phish") + v.evidence))
        }
        val reg = Featurizer.registrable(host)
        val free = Featurizer.FREE_HOSTS.any { on(host, it) }
        if (Featurizer.allowlisted(host, det.allow)) return safe("allowlist")
        if (!free && !Featurizer.isShortener(reg) && isPopular(host)) return safe("popular")
        if (ownDomain(host, pkg)) return safe("own_domain")
        val v = det.checkUrl(host)
        return when {
            v.score >= det.dnsBlockThreshold && (!free || pkg in browsers) -> Decision(Action.BLOCK, "model", v)
            v.band != Band.SAFE -> Decision(Action.FLAG, "model", v)
            else -> Decision(Action.ALLOW, "model", v)
        }
    }
}
