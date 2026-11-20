package app.entity.shield.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the user has told Shield about messages, applied before the detectors' verdict is shown.
 *
 *  1. Contacts: a message from a saved contact is never called a scam. Only a link the URL model calls
 *     phishing still shows it as "be careful" (a friend's hacked account sends those).
 *  2. Labels: "This is a scam" / "Not a scam" on an alert saves the message's PATTERN, not the message:
 *     digits, amounts, codes and phone numbers become "#", links become their domain, so one missed-call
 *     alert labelled safe covers every missed-call alert; one fake-KYC text labelled scam covers its
 *     variants. A pattern matches messages of the same sender kind (the same business ID, or any phone
 *     number) whose word 3-grams overlap it by at least [MATCH] (Jaccard).
 *
 * Cost per message: one indexed contacts lookup (cached) and a scan of the saved patterns of the same
 * sender kind, each a sorted IntArray of shingle hashes: microseconds for the [MAX] patterns kept.
 */
class SmsTrust private constructor(private val ctx: Context) {
    private class Pattern(val kind: String, val text: String, val shingles: IntArray, val scam: Boolean, val at: Long)

    private val file = File(ctx.filesDir, "sms_labels.json")
    private val byKind = HashMap<String, MutableList<Pattern>>()
    private val contactCache = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>) = size > 256
    }

    init { load() }

    // ---------------------------------------------------------------- contacts

    fun contactsAllowed() = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Is [sender] a saved contact? Only phone numbers can be; the lookup is indexed by the provider. */
    fun isContact(sender: String): Boolean {
        val n = sender.filter { it.isDigit() || it == '+' }
        if (n.count { it.isDigit() } < 7 || !contactsAllowed()) return false
        synchronized(contactCache) { contactCache[n]?.let { return it } }
        val hit = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        synchronized(contactCache) { contactCache[n] = hit }
        return hit
    }

    fun forgetContacts() = synchronized(contactCache) { contactCache.clear() }

    // ---------------------------------------------------------------- labels

    /** The user's label for a message like this one: true = scam, false = not a scam, null = none. */
    fun label(text: String, sender: String): Boolean? {
        val list = synchronized(byKind) { byKind[kind(sender)]?.toList() } ?: return null
        if (list.isEmpty()) return null
        val sh = shingles(pattern(text))
        var best: Pattern? = null
        var bestJ = 0.0
        for (p in list) {
            val j = jaccard(sh, p.shingles)
            if (j > bestJ || (j == bestJ && best != null && p.at > best.at)) { bestJ = j; best = p }
        }
        return if (bestJ >= MATCH) best!!.scam else null
    }

    fun save(text: String, sender: String, scam: Boolean) {
        val k = kind(sender)
        val pt = pattern(text)
        val p = Pattern(k, pt, shingles(pt), scam, System.currentTimeMillis())
        synchronized(byKind) {
            val l = byKind.getOrPut(k) { mutableListOf() }
            l.removeAll { jaccard(it.shingles, p.shingles) >= SAME }   // relabelling replaces, never piles up
            l += p
            trim()
            store()
        }
    }

    fun forget(text: String, sender: String) = synchronized(byKind) {
        val sh = shingles(pattern(text))
        byKind[kind(sender)]?.removeAll { jaccard(it.shingles, sh) >= MATCH }
        store()
    }

    fun count() = synchronized(byKind) { byKind.values.sumOf { it.size } }

    fun clear() = synchronized(byKind) { byKind.clear(); store() }

    private fun trim() {
        val all = byKind.values.flatten()
        if (all.size <= MAX) return
        val keep = all.sortedByDescending { it.at }.take(MAX).toSet()
        byKind.values.forEach { l -> l.retainAll(keep) }
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val a = JSONArray(file.readText())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = o.getString("p")
            byKind.getOrPut(o.getString("k")) { mutableListOf() } += Pattern(o.getString("k"), t, shingles(t), o.getBoolean("s"), o.getLong("t"))
        }
    }

    private fun store() {
        val a = JSONArray()
        byKind.values.flatten().forEach { a.put(JSONObject().put("k", it.kind).put("p", it.text).put("s", it.scam).put("t", it.at)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MATCH = 0.6   // same template with a different name, amount or a few changed words
        const val SAME = 0.9    // relabelling the same pattern
        const val MAX = 500

        @Volatile private var inst: SmsTrust? = null
        fun get(ctx: Context): SmsTrust = inst ?: synchronized(this) { inst ?: SmsTrust(ctx.applicationContext).also { inst = it } }

        private val HEADER = Regex("^[A-Z]{2}-")
        private val SUFFIX = Regex("-[A-Z]$")
        private val SPACE = Regex("\\s+")
        private val DIGITS = Regex("[0-9०-९]+")

        /** Sender kind: the business ID without its route prefix/suffix ("JT-JioPay-S" -> "JIOPAY"), "PHONE", or "OTHER". */
        fun kind(sender: String): String {
            val s = sender.trim().uppercase()
            return when {
                Featurizer.isBusinessSender(s) -> s.replace(HEADER, "").replace(SUFFIX, "")
                s.count { it.isDigit() } >= 7 -> "PHONE"
                else -> "OTHER"
            }
        }

        /** The message with everything message-specific taken out: links -> their domain, numbers -> "#". */
        fun pattern(text: String): String {
            var t = text.lowercase()
            t = Featurizer.URL_RE.replace(t) { m -> " link:" + Featurizer.registrable(Featurizer.urlHost(if ("://" in m.value) m.value else "http://${m.value}")) + " " }
            t = DIGITS.replace(t, "#")
            return SPACE.replace(t, " ").trim().take(400)
        }

        /** Sorted, distinct hashes of the pattern's word 3-grams (single words for very short texts). */
        fun shingles(p: String): IntArray {
            val w = p.split(' ').filter { it.isNotEmpty() }
            val n = if (w.size >= 3) 3 else 1
            val out = HashSet<Int>()
            for (i in 0..w.size - n) out += w.subList(i, i + n).joinToString(" ").hashCode()
            return out.toIntArray().also { it.sort() }
        }

        fun jaccard(a: IntArray, b: IntArray): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var i = 0; var j = 0; var inter = 0
            while (i < a.size && j < b.size) when {
                a[i] == b[j] -> { inter++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}
