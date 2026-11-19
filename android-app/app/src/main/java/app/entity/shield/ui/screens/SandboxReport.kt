// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Featurizer
import app.entity.shield.ui.components.*
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import org.json.JSONObject

private val DANGEROUS_FIELDS = setOf("upi_pin", "atm_pin", "mpin", "cvv", "card", "otp", "password", "aadhaar", "pan", "netbanking_id", "expiry")

@Composable
private fun fieldName(kind: String) = when (kind) {
    "upi_pin" -> tr("UPI PIN", "UPI PIN"); "atm_pin" -> tr("ATM PIN", "ATM PIN"); "mpin" -> "MPIN"
    "otp" -> "OTP"; "cvv" -> "CVV"; "card" -> tr("Card number", "कार्ड नंबर"); "expiry" -> tr("Card expiry", "कार्ड की तारीख़")
    "aadhaar" -> tr("Aadhaar", "आधार"); "pan" -> "PAN"; "password" -> tr("Password", "पासवर्ड")
    "netbanking_id" -> tr("Bank login ID", "बैंक लॉगिन ID"); "account" -> tr("Account number", "खाता नंबर"); "ifsc" -> "IFSC"
    "phone" -> tr("Mobile number", "मोबाइल नंबर"); "email" -> tr("Email", "ईमेल"); "dob" -> tr("Date of birth", "जन्म तिथि")
    else -> tr("Other details", "दूसरी जानकारी")
}

/** What the sandbox saw when it visited the link: the route, the certificate, what the page asks for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SandboxReport(extra: String) {
    val c = Sh.c
    val sb = remember(extra) { runCatching { JSONObject(extra).getJSONObject("sandbox") }.getOrNull() } ?: return
    val hops = sb.optJSONArray("hops")
    val page = sb.optJSONObject("page")
    val cert = sb.optJSONObject("cert")
    val iso = sb.optJSONObject("isolation")
    SectionHeader(tr("Sandbox report", "सैंडबॉक्स रिपोर्ट"))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Ico.Box, Tone.Info, 36.dp)
            Spacer(Modifier.width(Dim.m))
            Column(Modifier.weight(1f)) {
                Text(tr("Opened in the sandbox", "सैंडबॉक्स में खोला गया"), style = Sh.t.headline, color = c.text)
                Text(
                    if (iso != null) "uid ${iso.optInt("uid")} · ${if (iso.optBoolean("isolated")) "isolated" else "not isolated"} · files ${if (iso.optBoolean("filesDenied")) "denied" else "open"} · network ${if (iso.optBoolean("netDenied")) "denied" else "open"}"
                    else tr("Isolated process", "अलग बंद प्रोसेस"),
                    style = Sh.t.monoSmall, color = c.text3, maxLines = 2,
                )
            }
        }
        // the route the link really takes
        if (hops != null && hops.length() > 0) {
            Spacer(Modifier.height(Dim.l))
            Text(tr("ROUTE", "रास्ता"), style = Sh.t.overline, color = c.text3)
            for (i in 0 until hops.length()) {
                val h = hops.getJSONObject(i)
                val last = i == hops.length() - 1
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (last) c.text else c.text3))
                    Spacer(Modifier.width(10.dp))
                    Text(Featurizer.urlHost(h.optString("url")), style = Sh.t.mono, color = if (last) c.text else c.text2, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val st = h.optInt("status")
                    Tag("$st", if (st in 300..399) Tone.Info else if (st >= 400) Tone.Warn else Tone.Neutral)
                }
            }
        }
        sb.optString("schemeRedirect").takeIf { it.isNotEmpty() }?.let {
            Text(tr("Then tries to open: $it:// (an app)", "फिर खोलने की कोशिश: $it:// (एक ऐप)"), style = Sh.t.caption, color = c.warnText, modifier = Modifier.padding(top = Dim.s))
        }
        sb.optString("error").takeIf { it.isNotEmpty() }?.let {
            Text(when (it) {
                "dns" -> tr("The address does not exist (taken down, or never real).", "यह पता मौजूद नहीं है (हटा दिया गया या कभी था ही नहीं)।")
                "tls_invalid" -> tr("Its security certificate is not valid for this site.", "इसका सुरक्षा सर्टिफ़िकेट इस साइट के लिए सही नहीं है।")
                "private_ip" -> tr("It points into a private network, so Shield did not go there.", "यह किसी निजी नेटवर्क की ओर जाता है, इसलिए Shield वहां नहीं गया।")
                "timeout" -> tr("The site did not answer in 10 seconds.", "साइट ने 10 सेकंड में जवाब नहीं दिया।")
                else -> tr("Could not reach it ($it). The address check above still applies.", "वहां नहीं पहुंच सके ($it)। ऊपर की पते की जांच फिर भी लागू है।")
            }, style = Sh.t.callout, color = c.text2, modifier = Modifier.padding(top = Dim.m))
        }
        if (cert != null) {
            val age = cert.optInt("ageDays")
            Row(Modifier.padding(top = Dim.m), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Certificate", "सर्टिफ़िकेट"), style = Sh.t.caption, color = c.text2, modifier = Modifier.weight(1f))
                Text("${cert.optString("issuer").take(28)} · ", style = Sh.t.monoSmall, color = c.text2)
                Tag(tr("$age days old", "$age दिन पुराना"), if (age < 14) Tone.Warn else Tone.Neutral)
            }
        }
        if (page != null) {
            val title = page.optString("title")
            if (title.isNotEmpty()) {
                Spacer(Modifier.height(Dim.m))
                Text(tr("PAGE TITLE", "पेज का शीर्षक"), style = Sh.t.overline, color = c.text3)
                Text(title, style = Sh.t.callout, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            val fields = page.optJSONArray("fields")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("kind") } }.orEmpty().filter { it != "other" }.distinct()
            if (fields.isNotEmpty()) {
                Spacer(Modifier.height(Dim.m))
                Text(tr("THIS PAGE ASKS FOR", "यह पेज मांगता है"), style = Sh.t.overline, color = c.text3)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.forEach { Tag(fieldName(it), if (it in DANGEROUS_FIELDS) Tone.Danger else Tone.Neutral) }
                }
            }
        }
        Text(tr("Shield fetched the page without cookies or scripts and read it in an isolated process. The page itself was never shown to the AI.",
            "Shield ने पेज बिना कुकीज़ या स्क्रिप्ट के लिया और एक अलग बंद प्रोसेस में पढ़ा। पेज ख़ुद कभी AI को नहीं दिखाया गया।"),
            style = Sh.t.caption, color = c.text3, modifier = Modifier.padding(top = Dim.l))
    }
}
