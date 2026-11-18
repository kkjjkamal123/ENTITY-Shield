package app.entity.shield

/**
 * Everything the user is told to DO is fixed, pre-checked text (never generated).
 * Llama only writes the "why" for the specific message, continuing a verdict the app wrote.
 */
object Advice {
    enum class Lang { EN, HI }

    fun steps(f: Family, lang: Lang): List<String> = (if (lang == Lang.HI) HI[f] ?: HI_GENERIC else EN[f] ?: EN_GENERIC)

    private val EN_GENERIC = listOf(
        "Do not reply, call back or open any link in it.",
        "Never share OTP, UPI PIN, CVV or passwords. No bank or government office asks for them.",
        "If you lost money, call 1930 (National Cyber Crime Helpline) at once, or report at cybercrime.gov.in.",
    )
    private val HI_GENERIC = listOf(
        "जवाब न दें, वापस कॉल न करें और कोई लिंक न खोलें।",
        "OTP, UPI PIN, CVV या पासवर्ड कभी न बताएं। कोई बैंक या सरकारी दफ़्तर ये नहीं मांगता।",
        "अगर पैसे कट गए हैं तो तुरंत 1930 (राष्ट्रीय साइबर क्राइम हेल्पलाइन) पर कॉल करें या cybercrime.gov.in पर शिकायत करें।",
    )

    private val EN = mapOf(
        Family.KYC to listOf("Do not open the link. Banks never ask for KYC through SMS links.", "Update KYC only at your branch or in the bank's official app.", "Block the sender."),
        Family.POWER to listOf("Do not call the number. Electricity boards do not cut power over SMS or phone calls.", "Check your bill only on the official board website or app.", "Block the sender."),
        Family.PARCEL to listOf("Do not pay any 'fee' or open the link.", "Track parcels only on the courier's official website.", "Block the sender."),
        Family.ARREST to listOf("Hang up. Police, CBI and courts never 'arrest' anyone over a phone or video call.", "Never transfer money to 'verify' or 'clear' your name.", "Call 1930 or report at cybercrime.gov.in."),
        Family.JOB to listOf("Do not join the group or pay any amount. Real jobs never ask you to pay.", "'Like and earn' tasks end with you losing deposits.", "Block the sender."),
        Family.PRIZE to listOf("You did not win anything. No real prize asks for a fee.", "Do not pay, call or click.", "Block the sender."),
        Family.UPI to listOf("You never need a UPI PIN to RECEIVE money. A PIN only sends money.", "Decline any collect request you did not expect.", "Block the sender."),
        Family.OTP to listOf("Never share the OTP or code. It gives them access to your account or money.", "If you already shared it, call your bank's official number now.", "Block the sender."),
        Family.LOAN to listOf("Do not install the app or pay a 'processing fee'.", "Take loans only from RBI-registered banks and NBFCs.", "Block the sender."),
        Family.INVEST to listOf("No real investment guarantees high or quick returns.", "Do not transfer money or join the group.", "Check SEBI registration at sebi.gov.in."),
        Family.FAMILY to listOf("Call your family member on their OLD number before sending anything.", "Do not send money to a new number based on a message.", "Block the sender if it is not them."),
        Family.LINK to listOf("Do not open the link or enter any details.", "Go to the official website or app yourself instead.", "Block the sender."),
        Family.NET_SCAN to listOf("A device is probing your network for open doors.", "Make sure the router and devices use strong, non-default passwords.", "Disconnect the device if you do not recognise it."),
        Family.NET_BEACON to listOf("A device on your network is contacting a server on a fixed timer, like malware does.", "Disconnect that device from Wi-Fi.", "Factory-reset or update it before reconnecting."),
        Family.NET_BRUTE to listOf("Someone is trying many passwords on a device on your network.", "Change that device's password to a strong one.", "Turn off remote login (SSH/Telnet) if you do not use it."),
        Family.NET_ANOMALY to listOf("A device is behaving unusually on your network.", "Check whether you recognise the device.", "Disconnect it if you do not."),
        Family.NET_DOMAIN to listOf("Shield already blocked this site, so nothing was sent to it.", "If you did not open it yourself, check the app named above: uninstall it if you do not trust it.", "Never enter OTPs or passwords on a page you reached from a message."),
        Family.NET_DGA to listOf("The app named above is behaving like malware that hunts for its hidden server.", "Uninstall that app (Settings > Apps), especially if it came from outside the Play Store.", "Then run Play Protect and change passwords you used on this phone."),
        Family.NET_TUNNEL to listOf("The app named above is hiding data inside internet lookups, a way to sneak information out.", "Uninstall that app now, especially if it was installed from a link or APK file.", "Change your banking and email passwords from a trusted device."),
    )
    private val HI = mapOf(
        Family.KYC to listOf("लिंक न खोलें। बैंक कभी SMS लिंक से KYC नहीं मांगते।", "KYC सिर्फ़ ब्रांच में या बैंक के आधिकारिक ऐप में करें।", "भेजने वाले को ब्लॉक करें।"),
        Family.POWER to listOf("नंबर पर कॉल न करें। बिजली विभाग SMS या फ़ोन पर बिजली नहीं काटता।", "बिल सिर्फ़ आधिकारिक वेबसाइट या ऐप पर देखें।", "भेजने वाले को ब्लॉक करें।"),
        Family.PARCEL to listOf("कोई 'फ़ीस' न भरें और लिंक न खोलें।", "पार्सल सिर्फ़ कूरियर की आधिकारिक वेबसाइट पर ट्रैक करें।", "भेजने वाले को ब्लॉक करें।"),
        Family.ARREST to listOf("कॉल काट दें। पुलिस, CBI या कोर्ट फ़ोन या वीडियो कॉल पर किसी को 'गिरफ्तार' नहीं करते।", "नाम 'साफ़' करने के लिए कभी पैसे न भेजें।", "1930 पर कॉल करें या cybercrime.gov.in पर शिकायत करें।"),
        Family.JOB to listOf("ग्रुप में न जुड़ें, कोई पैसा न दें। असली नौकरी पैसे नहीं मांगती।", "'लाइक करो, कमाओ' वाले काम में आख़िर में पैसे डूबते हैं।", "भेजने वाले को ब्लॉक करें।"),
        Family.PRIZE to listOf("आपने कुछ नहीं जीता। असली इनाम फ़ीस नहीं मांगता।", "पैसे न दें, कॉल या क्लिक न करें।", "भेजने वाले को ब्लॉक करें।"),
        Family.UPI to listOf("पैसे पाने के लिए UPI PIN कभी नहीं चाहिए। PIN सिर्फ़ पैसे भेजता है।", "अनजान collect request को मना करें।", "भेजने वाले को ब्लॉक करें।"),
        Family.OTP to listOf("OTP या कोड कभी न बताएं। इससे आपके खाते या पैसे तक पहुंच मिलती है।", "अगर बता दिया है, तो तुरंत बैंक के आधिकारिक नंबर पर कॉल करें।", "भेजने वाले को ब्लॉक करें।"),
        Family.LOAN to listOf("ऐप इंस्टॉल न करें, 'प्रोसेसिंग फ़ीस' न दें।", "लोन सिर्फ़ RBI-पंजीकृत बैंक या NBFC से लें।", "भेजने वाले को ब्लॉक करें।"),
        Family.INVEST to listOf("कोई असली निवेश जल्दी या ज़्यादा मुनाफ़े की गारंटी नहीं देता।", "पैसे न भेजें, ग्रुप में न जुड़ें।", "sebi.gov.in पर SEBI पंजीकरण जांचें।"),
        Family.FAMILY to listOf("कुछ भेजने से पहले परिवार वाले को उनके पुराने नंबर पर कॉल करें।", "सिर्फ़ मैसेज के भरोसे नए नंबर पर पैसे न भेजें।", "अगर वो नहीं हैं तो ब्लॉक करें।"),
        Family.LINK to listOf("लिंक न खोलें, कोई जानकारी न भरें।", "आधिकारिक वेबसाइट या ऐप ख़ुद खोलें।", "भेजने वाले को ब्लॉक करें।"),
        Family.NET_SCAN to listOf("कोई डिवाइस आपके नेटवर्क में खुले रास्ते ढूंढ रहा है।", "राउटर और डिवाइस पर मज़बूत पासवर्ड रखें।", "अनजान डिवाइस हो तो उसे हटा दें।"),
        Family.NET_BEACON to listOf("आपके नेटवर्क का एक डिवाइस तय समय पर किसी सर्वर से बात कर रहा है, जैसा मैलवेयर करता है।", "उस डिवाइस को Wi-Fi से हटा दें।", "दोबारा जोड़ने से पहले उसे रीसेट या अपडेट करें।"),
        Family.NET_BRUTE to listOf("कोई आपके नेटवर्क के एक डिवाइस पर कई पासवर्ड आज़मा रहा है।", "उस डिवाइस का पासवर्ड मज़बूत करें।", "ज़रूरत न हो तो रिमोट लॉगिन (SSH/Telnet) बंद करें।"),
        Family.NET_ANOMALY to listOf("आपके नेटवर्क पर एक डिवाइस असामान्य व्यवहार कर रहा है।", "देखें कि आप उस डिवाइस को पहचानते हैं या नहीं।", "न पहचानें तो उसे हटा दें।"),
        Family.NET_DOMAIN to listOf("Shield ने यह साइट पहले ही ब्लॉक कर दी, इसलिए उसे कुछ नहीं भेजा गया।", "अगर आपने इसे ख़ुद नहीं खोला, तो ऊपर लिखे ऐप की जांच करें; भरोसा न हो तो उसे हटा दें।", "मैसेज से खुले किसी पेज पर कभी OTP या पासवर्ड न डालें।"),
        Family.NET_DGA to listOf("ऊपर लिखा ऐप मैलवेयर की तरह अपना छिपा सर्वर ढूंढ रहा है।", "उस ऐप को हटा दें (Settings > Apps), ख़ासकर अगर वह Play Store के बाहर से आया हो।", "फिर Play Protect चलाएं और इस फ़ोन पर इस्तेमाल किए पासवर्ड बदलें।"),
        Family.NET_TUNNEL to listOf("ऊपर लिखा ऐप इंटरनेट लुकअप के अंदर डेटा छिपाकर बाहर भेज रहा है।", "उस ऐप को अभी हटा दें, ख़ासकर अगर वह किसी लिंक या APK फ़ाइल से आया हो।", "किसी भरोसेमंद डिवाइस से अपने बैंक और ईमेल के पासवर्ड बदलें।"),
    )

    // ------------------------------------------------------------ evidence -> words (for the prompt and the UI chips)

    fun phrase(signal: String, v: Verdict): String? = when (signal) {
        "has_link" -> v.worstHost?.let { "contains a link to $it" } ?: "contains a link"
        "urgency" -> "creates urgency"
        "asks_secret" -> "asks for OTP, PIN or password"
        "money_request" -> "asks for a payment"
        "account_threat" -> "threatens to block or cut a service"
        "prize_lottery" -> "promises a prize or reward"
        "job_task" -> "offers easy money for tasks"
        "authority_threat" -> "claims to be police, court or a regulator"
        "kyc_identity" -> "asks about KYC, PAN or Aadhaar"
        "brand_link_mismatch", "brand_not_official" -> v.worstHost?.let { "link domain $it is not the official site of the brand it names" } ?: "uses a fake brand link"
        "shortener_link", "shortener" -> "hides the real link behind a short link"
        "sender_mobile" -> "comes from a personal mobile number, not a registered business sender"
        "call_number" -> "asks you to call a phone number"
        "ip_host" -> "the link is a bare IP address"
        "free_hosting" -> "the site is on a free hosting service anyone can use"
        "suspicious_tld" -> "uses a cheap domain ending often used in scams"
        "lure_word" -> "the address uses words like login, verify or update"
        "many_hyphens" -> "the address has many hyphens"
        "long_host", "many_subdomains" -> "the address is unusually long"
        "punycode" -> "the address uses look-alike characters"
        "dns_blocked" -> v.worstHost?.let { "an app tried to reach $it, which looks like a scam or malware site" } ?: "an app tried to reach a scam site"
        "beacon" -> "an app contacts the same server on a fixed timer, like malware checking in"
        "dga_burst" -> "an app looked up many random names that do not exist, like malware searching for its server"
        "tunnel" -> "an app is hiding data inside long random web addresses"
        "port_scan" -> v.worstHost?.let { "the device $it on this Wi-Fi probed several ports on the phone" } ?: "a device on this Wi-Fi probed the phone"
        "adb_probe" -> "it tried the Android debugging port that phone-hijacking worms use"
        "brute_force" -> "it tried to log in again and again"
        else -> null // sender_dlt, dont_share_otp, digits_in_name, counters: point to safety or are details
    }

    fun signs(v: Verdict): List<String> = v.evidence.mapNotNull { phrase(it, v) }.distinct()

    /** Hindi versions of the warning signs. Giving the 1B the evidence already in Hindi (instead of
     *  asking it to translate English signs while answering) is measured in training/llm/eval_explanations.py. */
    fun phraseHi(signal: String, v: Verdict): String? = when (signal) {
        "has_link" -> v.worstHost?.let { "इसमें $it का लिंक है" } ?: "इसमें एक लिंक है"
        "urgency" -> "यह जल्दबाज़ी करवाता है"
        "asks_secret" -> "यह OTP, PIN या पासवर्ड मांगता है"
        "money_request" -> "यह पैसे भेजने को कहता है"
        "account_threat" -> "यह खाता या सेवा बंद करने की धमकी देता है"
        "prize_lottery" -> "यह इनाम या लॉटरी का लालच देता है"
        "job_task" -> "यह छोटे कामों के बदले आसान कमाई का वादा करता है"
        "authority_threat" -> "यह पुलिस, कोर्ट या सरकारी अफ़सर होने का दावा करता है"
        "kyc_identity" -> "यह KYC, PAN या आधार के बारे में पूछता है"
        "brand_link_mismatch", "brand_not_official" -> v.worstHost?.let { "लिंक $it उस ब्रांड की असली वेबसाइट नहीं है" } ?: "यह नकली ब्रांड लिंक है"
        "shortener_link", "shortener" -> "यह असली लिंक को छोटे लिंक के पीछे छिपाता है"
        "sender_mobile" -> "यह किसी निजी मोबाइल नंबर से आया है, किसी रजिस्टर्ड कंपनी से नहीं"
        "call_number" -> "यह किसी फ़ोन नंबर पर कॉल करने को कहता है"
        "ip_host" -> "लिंक सिर्फ़ एक IP पता है"
        "free_hosting" -> "साइट मुफ़्त होस्टिंग पर है, जिसे कोई भी बना सकता है"
        "suspicious_tld" -> "पते का अंत सस्ते डोमेन जैसा है जो धोखे में आम है"
        "lure_word" -> "पते में login, verify या update जैसे शब्द हैं"
        "dns_blocked" -> v.worstHost?.let { "एक ऐप ने $it खोलने की कोशिश की, जो धोखे या मैलवेयर की साइट लगती है" } ?: "एक ऐप ने धोखे की साइट खोलने की कोशिश की"
        "beacon" -> "एक ऐप तय समय पर बार-बार एक ही सर्वर से संपर्क करता है, जैसे मैलवेयर करता है"
        "dga_burst" -> "एक ऐप ने बहुत सारे बेतरतीब नाम खोजे जो मौजूद ही नहीं हैं"
        "tunnel" -> "एक ऐप लंबे बेतरतीब पतों में डेटा छिपाकर भेज रहा है"
        "port_scan" -> v.worstHost?.let { "इस Wi-Fi पर डिवाइस $it ने फ़ोन के कई पोर्ट टटोले" } ?: "इस Wi-Fi पर किसी डिवाइस ने फ़ोन को टटोला"
        "adb_probe" -> "उसने Android डिबगिंग पोर्ट आज़माया, जिसे फ़ोन पर कब्ज़ा करने वाले वर्म इस्तेमाल करते हैं"
        "brute_force" -> "उसने बार-बार लॉगिन करने की कोशिश की"
        else -> null
    }

    fun signsFor(v: Verdict, lang: Lang): List<String> =
        if (lang == Lang.HI) v.evidence.mapNotNull { phraseHi(it, v) }.distinct() else signs(v)

    // ------------------------------------------------------------ Llama prompt (v4 design: fact sheet + verdict prefill)

    private const val FACTS = "Facts: KYC means Know Your Customer. Banks never ask for KYC, OTP, PIN or passwords through SMS links. " +
        "A UPI PIN is only needed to send money, never to receive it. Police and courts never arrest anyone over a phone or video call. " +
        "The cyber crime helpline in India is 1930."

    /** Short title for notifications and the verdict banner. */
    fun headline(v: Verdict, lang: Lang): String {
        val hi = lang == Lang.HI
        if (v.band == Band.SAFE) return if (hi) "✅ कोई ख़तरा नहीं मिला" else "✅ No threat found"
        return when (v.family) {
            Family.NET_DOMAIN -> if (hi) "🛡️ धोखे वाली साइट ब्लॉक की गई" else "🛡️ Scam site blocked"
            Family.NET_BEACON -> if (hi) "⚠️ ऐप मैलवेयर जैसा व्यवहार कर रहा है" else "⚠️ App behaving like malware"
            Family.NET_DGA -> if (hi) "⚠️ ऐप छिपा सर्वर ढूंढ रहा है" else "⚠️ App hunting for a hidden server"
            Family.NET_TUNNEL -> if (hi) "⚠️ लुकअप में छिपाकर डेटा भेजा जा रहा है" else "⚠️ Data hidden in web lookups"
            Family.NET_SCAN -> if (hi) "⚠️ इस Wi-Fi पर कोई आपके फ़ोन को टटोल रहा है" else "⚠️ Someone on this Wi-Fi is probing your phone"
            Family.NET_BRUTE -> if (hi) "⚠️ पासवर्ड अनुमान वाला हमला" else "⚠️ Password-guessing attack"
            Family.NET_ANOMALY -> if (hi) "⚠️ असामान्य नेटवर्क गतिविधि" else "⚠️ Unusual network activity"
            else -> prefill(v, lang).substringBefore(if (hi) " कारण:" else " Why:")
        }
    }

    fun prefill(v: Verdict, lang: Lang): String = when {
        v.kind == "net" && lang == Lang.HI -> if (v.band == Band.SCAM) "⚠️ यह असली ख़तरा है। कारण:" else "⚠️ यह संदिग्ध लगता है। कारण:"
        v.kind == "net" -> if (v.band == Band.SCAM) "⚠️ This is a real threat. Why:" else "⚠️ This looks suspicious. Why:"
        lang == Lang.HI && v.band == Band.SCAM -> "⚠️ नहीं, यह ${noun(v, lang)} एक धोखा है। कारण:"
        lang == Lang.HI -> "⚠️ यह ${noun(v, lang)} संदिग्ध लगता है। कारण:"
        v.band == Band.SCAM -> "⚠️ No, this ${noun(v, lang)} is a scam. Why:"
        else -> "⚠️ This ${noun(v, lang)} looks suspicious. Why:"
    }

    private fun noun(v: Verdict, lang: Lang) = when (v.kind) {
        "url" -> if (lang == Lang.HI) "लिंक" else "link"
        "net" -> if (lang == Lang.HI) "नेटवर्क गतिविधि" else "network activity"
        else -> "SMS"
    }

    /** The fixed system part of every explanation prompt. It is the same for every alert, so it can be
     *  pre-filled into the KV cache while nothing else is happening (Explainer.prewarm). */
    fun explainPrefix(lang: Lang): String {
        val hindi = if (lang == Lang.HI) " Answer in simple Hindi (Devanagari)." else ""
        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
            "You protect people in India from fraud. Explain warnings in very simple words. $FACTS " +
            "Write at most 2 short sentences about this specific case.$hindi<|eot_id|>"
    }

    fun explainPrompt(v: Verdict, message: String, lang: Lang): String {
        val label = if (v.band == Band.SCAM) "FRAUD" else "SUSPICIOUS"
        val what = when (v.kind) { "url" -> "I was about to open this link. Is it safe?"; "net" -> "My phone's network guard raised this alert. What does it mean?"; else -> "I got this SMS. Is it safe?" }
        return explainPrefix(lang) +
            "<|start_header_id|>user<|end_header_id|>\n\n$what\n\"${message.take(600)}\"\n" +
            "Safety check: $label (${(v.score * 100).toInt()}%). Signs: ${signsFor(v, lang).joinToString(", ").ifEmpty { if (lang == Lang.HI) "जाने-पहचाने धोखे जैसा पैटर्न" else "pattern matches known scams" }}." +
            "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n${prefill(v, lang)}"
    }

    /** Follow-up questions: Llama answers with the fixed steps in the prompt, so it repeats checked advice. */
    fun followUpPrompt(v: Verdict, message: String, question: String, lang: Lang): String {
        val hindi = if (lang == Lang.HI) " Answer in simple Hindi (Devanagari)." else ""
        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
            "You protect people in India from fraud. A message was flagged as a scam. $FACTS " +
            "Correct steps for this scam: ${steps(v.family, Lang.EN).joinToString(" ")} If money was lost: call 1930 at once and the bank's official number. " +
            "Answer the user's question in at most 3 short sentences, only using these steps.$hindi<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\nThe message: \"${message.take(400)}\"\n$question<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
    }
}
