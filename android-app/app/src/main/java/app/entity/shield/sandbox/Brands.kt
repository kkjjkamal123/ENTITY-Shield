package app.entity.shield.sandbox

import app.entity.shield.core.Featurizer

/**
 * Brands the page sandbox recognises, and look-alike domains of them. The Indian list is the one the
 * URL model was trained with (Featurizer.BRANDS, unchanged so model features stay identical); the global
 * list is used only for page analysis and look-alike checks, because phishing kits copy them all the time
 * (OpenPhish 2026-11-19: Roblox, Adobe, Microsoft, Shopee, crypto wallets).
 */
object Brands {
    private val GLOBAL: Map<String, List<String>> = mapOf(
        "google" to listOf("google.com", "google.co.in", "youtube.com", "gmail.com"),
        "gmail" to listOf("google.com", "gmail.com"),
        "microsoft" to listOf("microsoft.com", "live.com", "office.com", "outlook.com", "microsoftonline.com", "sharepoint.com"),
        "outlook" to listOf("outlook.com", "live.com", "microsoft.com", "office.com"),
        "office365" to listOf("office.com", "microsoft.com", "office365.com"),
        "onedrive" to listOf("live.com", "microsoft.com", "onedrive.com"),
        "icloud" to listOf("icloud.com", "apple.com"),
        "facebook" to listOf("facebook.com", "fb.com", "meta.com"),
        "instagram" to listOf("instagram.com"),
        "adobe" to listOf("adobe.com"),
        "docusign" to listOf("docusign.com", "docusign.net"),
        "dropbox" to listOf("dropbox.com"),
        "roblox" to listOf("roblox.com"),
        "steam" to listOf("steampowered.com", "steamcommunity.com"),
        "coinbase" to listOf("coinbase.com"),
        "binance" to listOf("binance.com"),
        "metamask" to listOf("metamask.io"),
        "trezor" to listOf("trezor.io"),
        "ledger" to listOf("ledger.com"),
        "telegram" to listOf("telegram.org", "t.me"),
        "discord" to listOf("discord.com", "discord.gg"),
        "linkedin" to listOf("linkedin.com"),
        "usps" to listOf("usps.com"),
        "wellsfargo" to listOf("wellsfargo.com"),
        "chase" to listOf("chase.com"),
        "bradesco" to listOf("bradesco.com.br"),
        "santander" to listOf("santander.com", "santander.com.br", "santander.co.uk"),
        "shopee" to listOf("shopee.com", "shopee.co.id", "shopee.com.my", "shopee.sg", "shopee.ph", "shopee.vn", "shopee.co.th", "shopee.com.br", "shopee.tw"),
        "spotify" to listOf("spotify.com"),
        "tiktok" to listOf("tiktok.com"),
        "myntra" to listOf("myntra.com"),
        "meesho" to listOf("meesho.com"),
        "swiggy" to listOf("swiggy.com"),
        "zomato" to listOf("zomato.com"),
        "bharatpe" to listOf("bharatpe.com"),
        "mobikwik" to listOf("mobikwik.com"),
        "lic" to listOf("licindia.in"),
        "sbicard" to listOf("sbicard.com"),
    )

    /** Indian (model) brands plus global brands, for PageAnalyzer. */
    val ALL: Map<String, List<String>> = LinkedHashMap(Featurizer.BRANDS).apply {
        GLOBAL.forEach { (k, v) -> merge(k, v) { a, b -> (a + b).distinct() } }
    }

    /**
     * Registry-restricted suffixes: only verified government bodies, banks and universities can register
     * these, so a page there is not a fresh phishing domain (it can still be hacked: strong page signals
     * such as a UPI PIN field or a Telegram bot still count).
     */
    val RESTRICTED = listOf(".gov.in", ".nic.in", ".bank.in", ".ac.in", ".edu.in", ".res.in", ".gov", ".mil", ".edu", ".gov.uk")

    fun restricted(host: String) = RESTRICTED.any { host.endsWith(it) }

    private fun on(host: String, d: String) = host == d || host.endsWith(".$d")

    private fun norm(s: String) = s.replace("vv", "w").replace("rn", "m").replace('0', 'o').replace('1', 'l').replace('i', 'l')

    private fun lev1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when { a.length > b.length -> i++; a.length < b.length -> j++; else -> { i++; j++ } }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /**
     * The brand a domain imitates, if any: a label equal to a brand name (paypal-login.xyz), or one typo
     * or look-alike swap away (robiox, rnicrosoft, wvvw--roblox) on a domain the brand does not own.
     */
    fun lookalike(host: String): String? {
        val toks = host.lowercase().split('.', '-').filter { it.length >= 3 }
        for ((b, official) in ALL) {
            if (official.any { on(host, it) }) continue
            val nb = norm(b)
            for (t in toks) {
                val nt = norm(t)
                // short names (sbi, lic, jio) only as the exact word: after look-alike swaps they collide
                if (t == b || (b.length >= 5 && nt == nb) || (b.length >= 6 && (lev1(t, b) || lev1(nt, nb)))) return b
            }
        }
        return null
    }
}
