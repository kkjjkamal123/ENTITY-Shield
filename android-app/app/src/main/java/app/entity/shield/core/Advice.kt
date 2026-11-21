package app.entity.shield.core

import app.entity.shield.llm.Explainer

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
        Family.PROMO to listOf("This reads like marketing from a registered business, not a scam.", "Use offers only in the brand's own app or website.", "To stop these, reply STOP or register on DND (call 1909)."),
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
        Family.NET_DOMAIN to listOf("Shield stopped this site before anything was sent to it.", "If you did not open it yourself, check the app named above: uninstall it if you do not trust it.", "If you know and trust this site, tap 'Allow this site'."),
        Family.NET_DGA to listOf("The app named above is behaving like malware that hunts for its hidden server.", "Uninstall that app (Settings > Apps), especially if it came from outside the Play Store.", "Then run Play Protect and change passwords you used on this phone."),
        Family.NET_TUNNEL to listOf("The app named above is hiding data inside internet lookups, a way to sneak information out.", "Uninstall that app now, especially if it was installed from a link or APK file.", "Change your banking and email passwords from a trusted device."),
    )
    private val HI = mapOf(
        Family.PROMO to listOf("यह किसी पंजीकृत कंपनी का प्रचार मैसेज लगता है, धोखा नहीं।", "ऑफ़र सिर्फ़ उसी ब्रांड के ऐप या वेबसाइट पर इस्तेमाल करें।", "ऐसे मैसेज बंद करने के लिए STOP भेजें या DND (1909) पर रजिस्टर करें।"),
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
        Family.NET_DOMAIN to listOf("Shield ने इस साइट को कुछ भी भेजे जाने से पहले रोक दिया।", "अगर आपने इसे ख़ुद नहीं खोला, तो ऊपर लिखे ऐप की जांच करें; भरोसा न हो तो उसे हटा दें।", "अगर आप इस साइट को जानते हैं और भरोसा करते हैं, तो 'यह साइट चालू करें' दबाएं।"),
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
        "known_phish" -> "the site is on a public list of reported phishing sites (PhishTank, OpenPhish)"
        "beacon" -> "an app contacts the same server on a fixed timer, like malware checking in"
        "dga_burst" -> "an app looked up many random names that do not exist, like malware searching for its server"
        "tunnel" -> "an app is hiding data inside long random web addresses"
        "port_scan" -> v.worstHost?.let { "the device $it on this Wi-Fi probed several ports on the phone" } ?: "a device on this Wi-Fi probed the phone"
        "adb_probe" -> "it tried the Android debugging port that phone-hijacking worms use"
        "brute_force" -> "it tried to log in again and again"
        "upi_pin_field" -> "the page asks for your UPI PIN, which is only ever typed inside a UPI app"
        "atm_pin_field" -> "the page asks for your ATM or card PIN"
        "card_and_cvv" -> "the page asks for your card number and CVV"
        "form_to_messenger" -> "what you type on the page is sent straight to a Telegram or Discord bot"
        "exfil_script" -> "the page has hidden code that sends data to a chat bot"
        "brand_impersonation" -> v.worstHost?.let { "the page copies a well-known brand but is not on its website ($it)" } ?: "the page copies a well-known brand"
        "brand_mention_offdomain" -> "the page names a brand that does not own this website"
        "apk_download" -> "the link downloads an Android app file (APK) from outside the Play Store"
        "page_links_apk" -> "the page offers an APK app file to install"
        "tls_invalid" -> "the site's security certificate is not valid"
        "password_field" -> "the page asks for a password"
        "otp_field" -> "the page asks for an OTP"
        "aadhaar_field" -> "the page asks for your Aadhaar number"
        "pan_field" -> "the page asks for your PAN"
        "form_offsite" -> "the form sends your details to a different website"
        "obfuscated_js" -> "the page hides its code"
        "anti_inspection" -> "the page tries to stop anyone inspecting it"
        "fullpage_iframe_offsite" -> "the page shows another website inside itself"
        "no_tls_form" -> "it asks for details without a secure connection"
        "cloaked_empty" -> "the page shows nothing until hidden code runs"
        "cross_site_redirects" -> "the link bounces through several websites before landing"
        "app_redirect" -> "the link tries to open or install an app"
        "new_certificate" -> "the site was set up only days ago"
        "userinfo_in_url" -> "the address hides the real site behind an @ sign"
        "scam_words", "scam_words_with_form" -> "the page's own words read like a scam"
        "mpin_field" -> "the page asks for your MPIN"
        "secret_offsite" -> "the form sends your password or codes to a different website"
        "unverifiable" -> "the page is gone or would not load, so Shield could not check it. Scam pages are often taken down within hours"
        "from_flagged_message" -> "it came in a message Shield already flagged"
        "your_label" -> if (v.band == Band.SCAM) "you marked messages like this as a scam" else null
        "in_contacts" -> "it is from a saved contact, but the message itself looks like fraud (their account may be hacked)"
        "cloaking_redirect" -> "the link sends visitors on to the real site, a trick scam kits use to hide from checkers"
        "secret_on_free_host" -> "it asks for passwords or codes on a free website anyone can make"
        "lookalike_domain" -> v.evidence.firstOrNull { it.startsWith("imitates:") }?.let { "the address imitates ${it.removePrefix("imitates:").replaceFirstChar { c -> c.uppercase() }}" } ?: "the address imitates a well-known brand"
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
        "known_phish" -> "यह साइट धोखे वाली साइटों की सार्वजनिक सूची (PhishTank, OpenPhish) में है"
        "beacon" -> "एक ऐप तय समय पर बार-बार एक ही सर्वर से संपर्क करता है, जैसे मैलवेयर करता है"
        "dga_burst" -> "एक ऐप ने बहुत सारे बेतरतीब नाम खोजे जो मौजूद ही नहीं हैं"
        "tunnel" -> "एक ऐप लंबे बेतरतीब पतों में डेटा छिपाकर भेज रहा है"
        "port_scan" -> v.worstHost?.let { "इस Wi-Fi पर डिवाइस $it ने फ़ोन के कई पोर्ट टटोले" } ?: "इस Wi-Fi पर किसी डिवाइस ने फ़ोन को टटोला"
        "adb_probe" -> "उसने Android डिबगिंग पोर्ट आज़माया, जिसे फ़ोन पर कब्ज़ा करने वाले वर्म इस्तेमाल करते हैं"
        "brute_force" -> "उसने बार-बार लॉगिन करने की कोशिश की"
        "upi_pin_field" -> "पेज आपका UPI PIN मांगता है, जो सिर्फ़ UPI ऐप के अंदर डाला जाता है"
        "atm_pin_field" -> "पेज आपका ATM या कार्ड PIN मांगता है"
        "card_and_cvv" -> "पेज आपका कार्ड नंबर और CVV मांगता है"
        "form_to_messenger" -> "पेज पर जो भी लिखेंगे, वो सीधे Telegram या Discord बॉट को जाता है"
        "exfil_script" -> "पेज में छिपा कोड डेटा चैट बॉट को भेजता है"
        "brand_impersonation" -> v.worstHost?.let { "पेज किसी जाने-माने ब्रांड की नकल है, पर उसकी वेबसाइट नहीं ($it)" } ?: "पेज किसी जाने-माने ब्रांड की नकल है"
        "brand_mention_offdomain" -> "पेज ऐसे ब्रांड का नाम लेता है जिसकी यह वेबसाइट नहीं"
        "apk_download" -> "लिंक Play Store के बाहर से ऐप फ़ाइल (APK) डाउनलोड करता है"
        "page_links_apk" -> "पेज इंस्टॉल करने को APK ऐप फ़ाइल देता है"
        "tls_invalid" -> "साइट का सुरक्षा सर्टिफ़िकेट सही नहीं है"
        "password_field" -> "पेज पासवर्ड मांगता है"
        "otp_field" -> "पेज OTP मांगता है"
        "aadhaar_field" -> "पेज आपका आधार नंबर मांगता है"
        "pan_field" -> "पेज आपका PAN मांगता है"
        "form_offsite" -> "फ़ॉर्म आपकी जानकारी किसी दूसरी वेबसाइट को भेजता है"
        "obfuscated_js" -> "पेज अपना कोड छिपाता है"
        "anti_inspection" -> "पेज जांच से बचने की कोशिश करता है"
        "fullpage_iframe_offsite" -> "पेज के अंदर कोई दूसरी वेबसाइट दिखती है"
        "no_tls_form" -> "यह बिना सुरक्षित कनेक्शन के जानकारी मांगता है"
        "cloaked_empty" -> "छिपा कोड चलने तक पेज पर कुछ नहीं दिखता"
        "cross_site_redirects" -> "लिंक कई वेबसाइटों से घूमकर पहुंचता है"
        "app_redirect" -> "लिंक कोई ऐप खोलने या इंस्टॉल करने की कोशिश करता है"
        "new_certificate" -> "साइट कुछ ही दिन पहले बनी है"
        "userinfo_in_url" -> "पता असली साइट को @ के पीछे छिपाता है"
        "scam_words", "scam_words_with_form" -> "पेज के शब्द धोखे जैसे हैं"
        "mpin_field" -> "पेज आपका MPIN मांगता है"
        "secret_offsite" -> "फ़ॉर्म आपका पासवर्ड या कोड किसी दूसरी वेबसाइट को भेजता है"
        "unverifiable" -> "पेज हट चुका है या खुला नहीं, इसलिए Shield इसे जांच नहीं सका। धोखे वाले पेज अक्सर कुछ घंटों में हटा दिए जाते हैं"
        "from_flagged_message" -> "यह उस मैसेज में आया जिसे Shield पहले ही ख़तरनाक बता चुका है"
        "your_label" -> if (v.band == Band.SCAM) "आपने ऐसे मैसेज को धोखा बताया था" else null
        "in_contacts" -> "यह आपके सेव किए संपर्क से है, पर मैसेज धोखे जैसा है (उनका खाता हैक हो सकता है)"
        "cloaking_redirect" -> "लिंक जांचने वालों को असली साइट पर भेज देता है, धोखेबाज़ ख़ुद को छिपाने के लिए यही चाल चलते हैं"
        "secret_on_free_host" -> "यह ऐसी मुफ़्त वेबसाइट पर पासवर्ड या कोड मांगता है जिसे कोई भी बना सकता है"
        "lookalike_domain" -> v.evidence.firstOrNull { it.startsWith("imitates:") }?.let { "पता ${it.removePrefix("imitates:").replaceFirstChar { c -> c.uppercase() }} की नकल करता है" } ?: "पता किसी जाने-माने ब्रांड की नकल करता है"
        else -> null
    }

    fun signsFor(v: Verdict, lang: Lang): List<String> =
        if (lang == Lang.HI) v.evidence.mapNotNull { phraseHi(it, v) }.distinct() else signs(v)

    // ------------------------------------------------------------ Llama prompt (v4 design: fact sheet + verdict prefill)

    private const val FACTS = "Facts: KYC means Know Your Customer. Banks never ask for KYC, OTP, PIN or passwords through SMS links. " +
        "A UPI PIN is only needed to send money, never to receive it. Police and courts never arrest anyone over a phone or video call. " +
        "The cyber crime helpline in India is 1930."

    /** Short title for notifications and the verdict banner: names the scam type in plain words. */
    fun headline(v: Verdict, lang: Lang): String {
        val hi = lang == Lang.HI
        if (v.band == Band.SAFE) return if (hi) "कोई ख़तरा नहीं मिला" else "No threat found"
        if (v.band == Band.UNCERTAIN && "unverifiable" in v.evidence && "from_flagged_message" !in v.evidence)
            return if (hi) "यह लिंक जांचा नहीं जा सका" else "Couldn't check this link"
        val name = when (v.family) {
            Family.NET_DOMAIN -> return if (hi) "Shield ने एक जोखिम भरी साइट रोकी" else "Shield stopped a risky site"
            Family.PROMO -> return if (hi) "प्रचार मैसेज (स्पैम)" else "Promotion (spam), not a scam"
            Family.NET_BEACON -> return if (hi) "ऐप मैलवेयर जैसा व्यवहार कर रहा है" else "App behaving like malware"
            Family.NET_DGA -> return if (hi) "ऐप छिपा सर्वर ढूंढ रहा है" else "App hunting for a hidden server"
            Family.NET_TUNNEL -> return if (hi) "लुकअप में छिपाकर डेटा भेजा जा रहा है" else "Data hidden in web lookups"
            Family.NET_SCAN -> return if (hi) "इस Wi-Fi पर कोई आपके फ़ोन को टटोल रहा है" else "Someone on this Wi-Fi is probing your phone"
            Family.NET_BRUTE -> return if (hi) "पासवर्ड अनुमान वाला हमला" else "Password-guessing attack"
            Family.NET_ANOMALY -> return if (hi) "असामान्य नेटवर्क गतिविधि" else "Unusual network activity"
            Family.KYC -> if (hi) "नकली KYC अपडेट धोखा" else "Fake KYC update scam"
            Family.POWER -> if (hi) "नकली बिजली कटौती धोखा" else "Fake power cut scam"
            Family.PARCEL -> if (hi) "नकली पार्सल फ़ीस धोखा" else "Fake parcel fee scam"
            Family.ARREST -> if (hi) "'डिजिटल अरेस्ट' धोखा" else "'Digital arrest' scam"
            Family.JOB -> if (hi) "टास्क-नौकरी धोखा" else "Task job scam"
            Family.PRIZE -> if (hi) "नकली इनाम या लॉटरी" else "Fake prize or lottery"
            Family.UPI -> if (hi) "UPI रिफ़ंड या कलेक्ट चाल" else "UPI refund or collect trick"
            Family.OTP -> if (hi) "OTP चुराने की कोशिश" else "OTP theft attempt"
            Family.LOAN -> if (hi) "नकली लोन ऑफ़र" else "Fake loan offer"
            Family.INVEST -> if (hi) "निवेश धोखा" else "Investment scam"
            Family.FAMILY -> if (hi) "'नया नंबर' परिवार धोखा" else "'New number' family scam"
            Family.LINK -> if (hi) "फ़िशिंग लिंक" else "Phishing link"
            Family.GENERIC -> if (hi) "धोखे वाला मैसेज" else "Scam message"
        }
        return if (v.band == Band.SCAM) name else if (hi) "संभावित $name" else "Possible ${name.replaceFirstChar { it.lowercase() }}"
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
