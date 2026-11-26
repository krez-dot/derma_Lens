package com.dermalens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dermalens.app.data.db.DermaDatabase
import com.dermalens.app.data.model.ScanRecord
import com.dermalens.app.navigation.Screen
import com.dermalens.app.ui.LocalAppSettings
import kotlin.math.roundToInt

/**
 * Insights -- analytics on the user's historical data (panel revision). Everything here is
 * computed on the phone from saved scans and their pre-scan check-ins. Patterns are the user's
 * own reports lined up side by side, not medical findings, and the screen says so.
 */
private data class InsightsData(
    val total: Int,
    val last30Days: Int,
    val checkIns: Int,
    val perMonth: List<Pair<String, Int>>,             // last 6 months, oldest first
    val itchByMonth: List<Float?>,
    val painByMonth: List<Float?>,
    val conditions: List<Pair<String, Int>>,
    val bodyAreas: List<Pair<String, Int>>,
    val triggers: List<Pair<String, Int>>,
    val feelings: List<Pair<String, Int>>,
    val patterns: List<String>
)

private fun computeInsights(scans: List<ScanRecord>, triggersByScan: Map<Int, List<String>>): InsightsData {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000

    // Last 6 calendar months, oldest first.
    val monthFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
    val months = (5 downTo 0).map { back ->
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            add(java.util.Calendar.MONTH, -back)
        }
    }
    fun monthKey(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 12 + c.get(java.util.Calendar.MONTH)
    fun monthKeyOf(millis: Long) = monthKey(java.util.Calendar.getInstance().apply { timeInMillis = millis })
    val byMonth = scans.groupBy { monthKeyOf(it.scanDate) }

    fun avg(values: List<Int>): Float? = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    val answered = scans.filter { it.itchLevel >= 0 || it.painLevel >= 0 }
    val realScans = scans.filter { it.condition != NO_CONDITION_FOUND }

    fun counts(values: List<String>) = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .toList().sortedByDescending { it.second }

    // Same on-device learner Home and the check-in use (PersonalPatterns), so they always agree.
    val patterns = PersonalPatterns.learn(scans, triggersByScan).patterns.map { it.sentence() }

    return InsightsData(
        total = scans.size,
        last30Days = scans.count { now - it.scanDate <= 30 * day },
        checkIns = answered.size,
        perMonth = months.map { monthFmt.format(it.time) to (byMonth[monthKey(it)]?.size ?: 0) },
        itchByMonth = months.map { m -> avg(byMonth[monthKey(m)].orEmpty().map { it.itchLevel }.filter { it >= 0 }) },
        painByMonth = months.map { m -> avg(byMonth[monthKey(m)].orEmpty().map { it.painLevel }.filter { it >= 0 }) },
        conditions = counts(realScans.map { it.condition }),
        bodyAreas = counts(scans.map { it.bodyArea }),
        triggers = counts(triggersByScan.values.flatten()),
        feelings = CheckInOptions.feelings.map { f -> f to scans.count { it.feelsVsLast == f } }.filter { it.second > 0 },
        patterns = patterns.take(4)
    )
}

@Composable
fun InsightsScreen(navController: NavController) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val db = remember { DermaDatabase.getDatabase(context) }
    val prefs = remember { context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    val data by produceState<InsightsData?>(initialValue = null) {
        val user = db.userDao().getUserByEmail(prefs.getString(DermaPrefs.KEY_USER_EMAIL, "") ?: "")
        value = if (user == null) computeInsights(emptyList(), emptyMap()) else {
            val scans = db.scanRecordDao().getScansByUserOnce(user.userId)
            val triggers = db.scanRecordDao().getTriggersByUser(user.userId).groupBy({ it.scanId }, { it.triggerName })
            computeInsights(scans, triggers)
        }
    }

    Scaffold(
        topBar = { DermaGlassTopBar(title = "Insights", onBack = { navController.popBackStack() }) },
        containerColor = DermaPageBackground
    ) { innerPadding ->
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DermaGreen) }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Patterns across all your saved scans and check-in answers.",
                fontSize = settings.textMd.sp,
                color = settings.textSecondary
            )

            if (d.total == 0) {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No scans yet", fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text("Save a few scans and your insights will show up here.", fontSize = settings.textBase.sp, color = settings.textSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        PillButton(text = "Start a scan", onClick = { navController.navigate(Screen.Scan.createRoute()) { launchSingleTop = true } })
                    }
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            StatRow(listOf("${d.total}" to "Total scans", "${d.last30Days}" to "Last 30 days", "${d.checkIns}" to "Check-ins"))

            InsightCard("Scans per month", "Last 6 months") { MonthlyBars(d.perMonth) }

            if (d.itchByMonth.any { it != null } || d.painByMonth.any { it != null }) {
                InsightCard("Itch and pain by month", "Average of what you reported") {
                    SymptomTrendChart(itch = d.itchByMonth, pain = d.painByMonth, xLabels = listOf(d.perMonth.first().first, d.perMonth.last().first))
                }
            }

            if (d.patterns.isNotEmpty()) {
                InsightCard("Possible patterns", "From your own answers. A pattern is not proof of a cause.") {
                    d.patterns.forEach { p ->
                        Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p, fontSize = settings.textBase.sp, color = settings.textPrimary, lineHeight = (settings.textBase * 1.4f).sp)
                        }
                    }
                }
            }

            if (d.conditions.isNotEmpty()) InsightCard("Conditions detected", null) { CountBars(d.conditions) }
            if (d.bodyAreas.isNotEmpty()) InsightCard("Body areas", "Where your scanned spots were") { CountBars(d.bodyAreas) }
            if (d.triggers.isNotEmpty()) InsightCard("Possible triggers you noted", "How many scans each one was ticked on") { CountBars(d.triggers) }
            if (d.feelings.isNotEmpty()) InsightCard("How it felt vs the scan before", null) { CountBars(d.feelings) }

            if (d.checkIns == 0) {
                Text(
                    "Older scans were saved before the pre-scan questions existed, so they only count toward scans and conditions.",
                    fontSize = settings.textSm.sp, color = DermaSubtle
                )
            }
            DiagnosticAidDisclaimer()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InsightCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    val settings = LocalAppSettings.current
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
            if (subtitle != null) Text(subtitle, fontSize = settings.textSm.sp, color = settings.textSecondary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Horizontal bars, one hue, longest first; the count is printed beside each bar. */
@Composable
private fun CountBars(rows: List<Pair<String, Int>>) {
    val settings = LocalAppSettings.current
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, count) ->
            Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $count" }) {
                Text(label, fontSize = settings.textBase.sp, color = settings.textPrimary)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(count.toFloat() / max)
                                .clip(RoundedCornerShape(4.dp))
                                .background(DermaGreen)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("$count", fontSize = settings.textBase.sp, fontWeight = FontWeight.SemiBold, color = settings.textPrimary, modifier = Modifier.widthIn(min = 20.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** Vertical bars per month, anchored to a shared baseline, value above each bar. */
@Composable
private fun MonthlyBars(months: List<Pair<String, Int>>) {
    val settings = LocalAppSettings.current
    val max = months.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        months.forEach { (label, count) ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) { contentDescription = "$label: $count scans" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("$count", fontSize = settings.textSm.sp, fontWeight = FontWeight.SemiBold, color = if (count == 0) DermaMuted else settings.textPrimary)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(if (count == 0) 0.01f else 0.6f * count / max)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (count == 0) Color(0xFFE5E7EB) else DermaGreen)
                )
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = settings.textSm.sp, color = settings.textSecondary)
            }
        }
    }
}
