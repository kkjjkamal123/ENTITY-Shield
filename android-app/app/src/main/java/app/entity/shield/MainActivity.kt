package app.entity.shield

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    private val openAlert = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pipeline.ensureChannel(this)
        Pipeline.load(this)
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS), 1)
        thread { Detectors.get(this) } // warm the detectors so the first SMS doesn't pay the load
        handle(intent)
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App(shared, openAlert) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) shared.value = i.getStringExtra(Intent.EXTRA_TEXT)
        i?.getLongExtra("alert", 0L)?.takeIf { it != 0L }?.let { openAlert.value = it }
    }
}

private val RED = Color(0xFFC62828)
private val AMBER = Color(0xFFEF8F00)
private val GREEN = Color(0xFF2E7D32)
private fun bandColor(b: Band) = when (b) { Band.SCAM -> RED; Band.UNCERTAIN -> AMBER; Band.SAFE -> GREEN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(shared: MutableState<String?>, openAlert: MutableState<Long?>) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    val selected = openAlert.value?.let { id -> Pipeline.alerts.firstOrNull { it.id == id } }
    LaunchedEffect(shared.value) { if (shared.value != null) tab = 1 }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("ENTITY Shield", fontWeight = FontWeight.Bold); Text("Offline · on-device · Arm", fontSize = 12.sp) } },
            navigationIcon = { if (selected != null) TextButton(onClick = { openAlert.value = null }) { Text("←") } },
            actions = {
                TextButton(onClick = {
                    lang = if (lang == Advice.Lang.EN) Advice.Lang.HI else Advice.Lang.EN
                    Prefs.setLang(ctx, lang)
                }) { Text(if (lang == Advice.Lang.EN) "EN → हिं" else "हिं → EN") }
            })
    }, bottomBar = {
        if (selected == null) NavigationBar {
            listOf("Alerts", "Check", "Demo", "Self-test").forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("🛡️", "🔍", "▶️", "🧪")[i]) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                selected != null -> AlertDetail(selected, lang)
                tab == 0 -> AlertList { openAlert.value = it.id }
                tab == 1 -> CheckScreen(shared) { openAlert.value = it.id }
                tab == 2 -> DemoScreen { openAlert.value = it.id }
                else -> SelfTest()
            }
        }
    }
}

@Composable
private fun AlertList(onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    if (Pipeline.alerts.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text("No messages checked yet.", fontWeight = FontWeight.Bold)
            Text("Every SMS that arrives is checked on this phone. Nothing leaves the device. Try the Demo tab.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Pipeline.alerts, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(a) }) {
                Row(Modifier.padding(12.dp)) {
                    Box(Modifier.size(10.dp).background(bandColor(a.v.band), RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${a.v.band.name.lowercase().replaceFirstChar { it.uppercase() }} · ${a.source} · ${a.sender.ifEmpty { a.v.worstHost ?: "" }}",
                            fontWeight = FontWeight.Bold, color = bandColor(a.v.band), fontSize = 13.sp)
                        Text(a.text, maxLines = 2, fontSize = 14.sp)
                        Text("${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(a.at))} · detect ${"%.1f".format(a.v.millis)} ms · warn ${"%.0f".format(a.warnMs)} ms",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        item { TextButton(onClick = { Pipeline.clear(ctx) }) { Text("Clear history") } }
    }
}

@Composable
private fun AlertDetail(a: Alert, lang: Advice.Lang) {
    val ctx = LocalContext.current
    val ex = remember { Explainer.get(ctx) }
    var why by remember(a.id, lang) { mutableStateOf("") }
    var whyStats by remember(a.id, lang) { mutableStateOf("") }
    var answer by remember(a.id, lang) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun ask(prompt: String, maxNew: Int, out: (String) -> Unit, statsOut: (String) -> Unit) {
        busy = true
        var acc = ""
        ex.run(prompt, maxNew, object : Explainer.Listener {
            override fun onToken(piece: String) { acc += piece; val s = acc; (ctx as ComponentActivity).runOnUiThread { out(s.trim()) } }
            override fun onDone(stats: Explainer.Stats) = (ctx as ComponentActivity).runOnUiThread {
                busy = false
                statsOut("Llama 3.2 1B · ExecuTorch · ${if (stats.loadMs > 0) "load ${stats.loadMs} ms · " else ""}first token ${stats.ttftMs} ms · ${stats.tokens} tokens in ${stats.totalMs} ms")
            }
            override fun onError(msg: String) = (ctx as ComponentActivity).runOnUiThread { busy = false; out(msg) }
        })
    }

    LaunchedEffect(a.id, lang) {
        if (a.v.band != Band.SAFE && ex.available()) ask(Advice.explainPrompt(a.v, a.text, lang), 80, { why = it }, { whyStats = it })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Verdict: from the detector, shown at once
        Surface(color = bandColor(a.v.band), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val head = if (a.v.band == Band.SAFE) (if (lang == Advice.Lang.HI) "✅ कोई ख़तरा नहीं मिला" else "✅ No threat found")
                    else Advice.prefill(a.v, lang).substringBefore(if (lang == Advice.Lang.HI) " कारण:" else " Why:")
                Text(head, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Score ${(a.v.score * 100).toInt()}% · ${a.v.kind.uppercase()} model · ${"%.2f".format(a.v.millis)} ms on device", color = Color.White, fontSize = 12.sp)
            }
        }
        if (a.sender.isNotEmpty()) Text("From: ${a.sender}", fontWeight = FontWeight.Bold)
        Text(a.text, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp).fillMaxWidth())
        val signs = Advice.signs(a.v)
        if (signs.isNotEmpty()) {
            Text(if (lang == Advice.Lang.HI) "संकेत" else "Warning signs", fontWeight = FontWeight.Bold)
            signs.forEach { Text("• $it", fontSize = 14.sp) }
        }
        if (a.v.band == Band.SAFE) return@Column

        // 2. Why: Llama, streamed
        Text(if (lang == Advice.Lang.HI) "क्यों (Llama, इसी फ़ोन पर)" else "Why (Llama, on this phone)", fontWeight = FontWeight.Bold)
        when {
            !ex.available() -> Text("Llama model not installed on this phone. Detection and the steps below still work.", color = MaterialTheme.colorScheme.outline)
            why.isEmpty() -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Thinking…") }
            else -> Text(why)
        }
        if (whyStats.isNotEmpty()) Text(whyStats, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)

        // 3. What to do: fixed, checked text
        Text(if (lang == Advice.Lang.HI) "क्या करें" else "What to do", fontWeight = FontWeight.Bold)
        Advice.steps(a.v.family, lang).forEachIndexed { i, s -> Text("${i + 1}. $s") }

        // 4. Actions that really act
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.sender.isNotEmpty() && a.source != "net") Button(onClick = { block(ctx, a.sender) }, colors = ButtonDefaults.buttonColors(containerColor = RED)) { Text(if (lang == Advice.Lang.HI) "ब्लॉक करें" else "Block sender") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) }) { Text("Call 1930") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy(ctx, reportText(a)) }) { Text(if (lang == Advice.Lang.HI) "रिपोर्ट कॉपी करें" else "Copy report") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in"))) }) { Text("cybercrime.gov.in") }
        }

        // 5. Follow-up questions
        if (ex.available() && a.source != "net") {
            Text(if (lang == Advice.Lang.HI) "पूछें" else "Ask", fontWeight = FontWeight.Bold)
            val qs = if (lang == Advice.Lang.HI) listOf("मैंने लिंक खोल दिया, अब क्या करूं?", "मैंने OTP बता दिया", "मेरे पैसे कट गए")
                else listOf("I already clicked the link. What now?", "I shared the OTP", "I already paid money")
            qs.forEach { q -> OutlinedButton(enabled = !busy, onClick = { answer = "…"; ask(Advice.followUpPrompt(a.v, a.text, q, lang), 100, { answer = it }, {}) }) { Text(q) } }
            if (answer.isNotEmpty()) Text(answer)
        }
        Text("Explanations are written by an AI model and can be imperfect. The verdict and the steps are not generated.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

private fun reportText(a: Alert) = "Suspected ${a.v.kind.uppercase()} fraud\nFrom: ${a.sender}\nReceived: ${Date(a.at)}\nMessage: ${a.text}\n" +
    (a.v.worstHost?.let { "Link domain: $it\n" } ?: "") + "Flagged on-device by ENTITY Shield (score ${(a.v.score * 100).toInt()}%)."

private fun copy(ctx: Context, s: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", s))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

/** Only the default dialer/SMS app may write the block list, so: copy the number and open the system block list. */
private fun block(ctx: Context, sender: String) {
    copy(ctx, sender)
    Toast.makeText(ctx, "Number copied. Tap 'Add a number' and paste.", Toast.LENGTH_LONG).show()
    runCatching { ctx.startActivity(ctx.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
}

@Composable
private fun CheckScreen(shared: MutableState<String?>, onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(shared.value ?: "") }
    var sender by remember { mutableStateOf("") }
    LaunchedEffect(shared.value) { shared.value?.let { text = it; shared.value = null } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Paste a message or a link, or share one to ENTITY Shield from any app.")
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Message or link") })
        OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth(), label = { Text("Sender (optional, e.g. VM-HDFCBK or a number)") }, singleLine = true)
        Button(enabled = text.isNotBlank(), onClick = {
            val t = text.trim()
            thread {
                val a = if (!t.contains(' ') && Featurizer.URL_RE.containsMatchIn(t)) Pipeline.onUrl(ctx, t, "check") else Pipeline.onSms(ctx, sender, t, "check")
                (ctx as ComponentActivity).runOnUiThread { onOpen(a) }
            }
        }) { Text("Check") }
    }
}

private data class Sample(val title: String, val sender: String, val text: String)

private val SAMPLES = listOf(
    Sample("Bank KYC scam (EN)", "+919876543210", "Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify"),
    Sample("Digital arrest (HI)", "+918765432109", "आपके आधार पर मनी लॉन्ड्रिंग केस दर्ज है। गिरफ्तारी से बचने के लिए तुरंत 9123456780 पर कॉल करें"),
    Sample("Electricity cut", "+917012345678", "Dear Consumer, your electricity power will be disconnected tonight at 9:30 pm because your previous month bill was not updated. Please immediately contact our electricity officer 9876501234"),
    Sample("Part-time job (Hinglish)", "+916398765432", "Ghar baithe kamaye Rs 5000 roz. Simple task, rating dene ka kaam. Abhi join karein t.me/earnfast_jobs"),
    Sample("UPI refund trick", "+919988776655", "You have received Rs 4,999 refund. Enter your UPI PIN to receive the money in your account. bit.ly/rfnd49"),
    Sample("Real bank OTP (safe)", "VM-HDFCBK", "482913 is your OTP for transaction of Rs 1,999 at Amazon. Valid for 10 mins. Do not share with anyone. -HDFC Bank"),
    Sample("Real delivery (safe)", "JD-AMAZON", "Your Amazon order will be delivered today by 9 PM. Track: amazon.in/your-orders"),
    Sample("Friend (safe)", "+919845012345", "Bhai kal ka plan cancel, office me late ho jayega. Sunday ko milte hain"),
)

@Composable
private fun DemoScreen(onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Each sample goes through the same path as a real incoming SMS (detectors → notification → alert). Works in airplane mode.") }
        items(SAMPLES) { s ->
            Card(Modifier.fillMaxWidth().clickable {
                thread { val a = Pipeline.onSms(ctx, s.sender, s.text, "demo"); (ctx as ComponentActivity).runOnUiThread { onOpen(a) } }
            }) { Column(Modifier.padding(12.dp)) { Text(s.title, fontWeight = FontWeight.Bold); Text(s.text, maxLines = 2, fontSize = 13.sp) } }
        }
        item {
            OutlinedButton(onClick = {
                thread { val a = Pipeline.onUrl(ctx, "https://hdfc-netbanking-verify.top/login", "demo"); (ctx as ComponentActivity).runOnUiThread { onOpen(a) } }
            }) { Text("Check a phishing link") }
        }
    }
}

/** On-device proof: golden vectors through the Kotlin featurizer + ExecuTorch must give the Python scores. */
@Composable
private fun SelfTest() {
    val ctx = LocalContext.current
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Checks that this phone computes exactly what training computed, and times the full detection path.")
        Button(enabled = !running, onClick = {
            running = true; out = "Running…"
            thread {
                val r = runSelfTest(ctx)
                (ctx as ComponentActivity).runOnUiThread { out = r; running = false }
            }
        }) { Text("Run self-test") }
        val ex = Explainer.get(ctx)
        Text("Llama: " + (ex.files()?.first?.absolutePath ?: "not installed"), fontSize = 12.sp)
        Text(out, fontSize = 13.sp)
    }
}

fun runSelfTest(ctx: Context): String {
    val d = Detectors.get(ctx)
    val sb = StringBuilder()
    for (kind in listOf("sms", "url")) {
        val g = JSONArray(ctx.assets.open("${kind}_golden.json").bufferedReader().readText())
        var ok = 0
        var worst = 0.0
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val p = d.score(kind, Featurizer.vector(kind, row.getString("text"), row.getString("sender")))
            val diff = kotlin.math.abs(p - row.getDouble("score"))
            worst = maxOf(worst, diff)
            if (diff < 1e-4) ok++
        }
        sb.append("$kind golden: $ok/${g.length()} match (max diff ${"%.1e".format(worst)})\n")
    }
    // full path timing: featurize + both models, as an incoming SMS would take it
    val msg = SAMPLES[0]
    repeat(50) { d.checkSms(msg.text, msg.sender) }
    val ms = List(1000) { d.checkSms(msg.text, msg.sender).millis }.sorted()
    sb.append("SMS check, featurize + models, n=1000: p50 ${"%.3f".format(ms[500])} ms · p95 ${"%.3f".format(ms[950])} ms\n")
    val ums = List(1000) { d.checkUrl("https://hdfc-netbanking-verify.top/login").millis }.sorted()
    sb.append("URL check, n=1000: p50 ${"%.3f".format(ums[500])} ms · p95 ${"%.3f".format(ums[950])} ms\n")
    android.util.Log.i("Shield", "selftest " + sb.toString().replace("\n", " | "))
    return sb.toString()
}
