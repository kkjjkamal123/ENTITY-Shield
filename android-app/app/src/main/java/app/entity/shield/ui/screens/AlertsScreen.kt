// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Band
import app.entity.shield.core.Pipeline
import app.entity.shield.ui.LocalAdaptive
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.sideCutoutPadding
import app.entity.shield.ui.Route
import app.entity.shield.ui.components.*
import app.entity.shield.ui.navClearance
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import java.util.Calendar

@Composable
fun AlertsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var filter by remember { mutableIntStateOf(0) }
    val all = Pipeline.alerts
    val list = remember(all.size, all.firstOrNull()?.id, filter) {
        all.filter {
            when (filter) {
                1 -> it.v.band == Band.SCAM && it.v.kind != "net"
                2 -> it.v.band == Band.UNCERTAIN
                3 -> it.v.kind == "net"
                4 -> it.v.band == Band.SAFE
                else -> true
            }
        }
    }
    val groups = remember(list) { list.groupBy { dayKey(it.at) } }
    val names = listOf(tr("All", "सभी"), tr("Scams", "धोखे"), tr("Unsure", "संदिग्ध"), tr("Network", "नेटवर्क"), tr("Safe", "सुरक्षित"))

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().sideCutoutPadding(),
        contentPadding = PaddingValues(start = LocalAdaptive.current.listSide, end = LocalAdaptive.current.listSide, top = Dim.s, bottom = navClearance()),
        verticalArrangement = Arrangement.spacedBy(Dim.s),
    ) {
        item { ScreenTitle(tr("Alerts", "अलर्ट"), tr("${all.count { it.v.band != Band.SAFE }} need a look · ${all.size} checked", "${all.count { it.v.band != Band.SAFE }} पर ध्यान दें · ${all.size} जांचे")) }
        item { Segmented(names, filter) { filter = it } }
        if (list.isEmpty()) item {
            EmptyState(Ico.ShieldCheck, tr("Nothing here", "यहां कुछ नहीं"),
                tr("Every SMS, link and network event Shield checks lands here. All of it is checked on this phone.",
                    "Shield जो भी SMS, लिंक और नेटवर्क गतिविधि जांचता है, यहां दिखती है। सब इसी फ़ोन पर।"))
        }
        groups.forEach { (day, items) ->
            item(key = "h$day") { SectionHeader(dayLabel(items.first().at)) }
            item(key = "g$day") {
                Card(pad = Dim.l) {
                    items.forEachIndexed { i, a -> if (i > 0) Divider(); AlertRow(a) { nav.open(Route.AlertR(a.id)) } }
                }
            }
        }
        if (all.isNotEmpty()) item {
            Text(tr("Clear history", "इतिहास मिटाएं"), style = Sh.t.caption, color = Sh.c.text3,
                modifier = Modifier.padding(vertical = Dim.l).pressable { Pipeline.clear(ctx) })
        }
    }
}

private fun dayKey(at: Long): Int = Calendar.getInstance().apply { timeInMillis = at }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

@Composable
private fun dayLabel(at: Long): String {
    val today = dayKey(System.currentTimeMillis())
    return when (dayKey(at)) {
        today -> tr("Today", "आज")
        today - 1 -> tr("Yesterday", "कल")
        else -> java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}
