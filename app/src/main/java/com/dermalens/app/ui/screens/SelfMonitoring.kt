package com.dermalens.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dermalens.app.ui.LocalAppSettings

/*
 * Self Monitoring: tracking a spot without a severity score (panel revision).
 *
 * The model gives a condition and a confidence, and confidence is how sure the model is, not how
 * bad the skin is -- so it is deliberately NOT used as a progress measure here. Instead each
 * timeline compares, first scan vs latest:
 *   - spots detected (detection boxes on the saved photo; depends on framing, so it says so)
 *   - itch and pain, as the user reported them in the pre-scan check-in
 *   - how the user said it feels compared with last time
 * plus a small itch/pain trend chart and a side-by-side photo comparison.
 */

/**
 * One-word answer to "is this spot getting better?" -- the panel's progress-tracking question --
 * without a severity score. It combines, first scan vs latest scan:
 *   spots detected, itch, pain (each: lower = +1 toward Improving, higher = +1 toward Worsening)
 *   and the user's own latest "Better / About the same / Worse".
 * More signals pointing one way than the other decides it; a tie is Stable. A latest scan with no
 * condition found is Clear. Each badge lists the reasons it came from.
 */
enum class SpotStatus(val label: String) { IMPROVING("Improving"), STABLE("Stable"), WORSENING("Worsening"), CLEAR("Clear") }

data class SpotAssessment(val status: SpotStatus, val reasons: List<String>)

fun assessSpot(track: ConditionTrack): SpotAssessment? {
    val scans = track.scans
    if (track.latestClear) return SpotAssessment(SpotStatus.CLEAR, listOf("Latest scan found no condition"))
    if (scans.size < 2) return null
    var better = 0
    var worse = 0
    val reasons = mutableListOf<String>()
    fun compare(name: String, first: Int?, latest: Int?, show: (Int) -> String) {
        if (first == null || latest == null) return
        when {
            latest < first -> { better++; reasons += "$name ${show(first)} → ${show(latest)}" }
            latest > first -> { worse++; reasons += "$name ${show(first)} → ${show(latest)}" }
            else -> reasons += "$name unchanged"
        }
    }
    val withSpots = scans.filter { it.spotCount != null && !it.isClear }
    if (withSpots.size >= 2) compare("spots", withSpots.first().spotCount, withSpots.last().spotCount) { "$it" }
    val withItch = scans.filter { it.itchLevel >= 0 }
    if (withItch.size >= 2) compare("itch", withItch.first().itchLevel, withItch.last().itchLevel) { CheckInOptions.levelLabel(it).lowercase() }
    val withPain = scans.filter { it.painLevel >= 0 }
    if (withPain.size >= 2) compare("pain", withPain.first().painLevel, withPain.last().painLevel) { CheckInOptions.levelLabel(it).lowercase() }
    when (scans.last().feelsVsLast) {
        "Better" -> { better++; reasons += "you said it feels better" }
        "Worse" -> { worse++; reasons += "you said it feels worse" }
        "About the same" -> reasons += "you said it feels about the same"
    }
    if (reasons.isEmpty()) return null
    val status = when {
        better > worse -> SpotStatus.IMPROVING
        worse > better -> SpotStatus.WORSENING
        else -> SpotStatus.STABLE
    }
    return SpotAssessment(status, reasons)
}

/** Status pill: icon + word, never colour alone. */
@Composable
fun SpotStatusBadge(status: SpotStatus) {
    val settings = LocalAppSettings.current
    val (icon, fg, bg) = when (status) {
        SpotStatus.IMPROVING -> Triple(Icons.Default.TrendingDown, DermaSuccessText, Color(0xFFDCFCE7))
        SpotStatus.CLEAR -> Triple(Icons.Default.CheckCircle, DermaSuccessText, Color(0xFFDCFCE7))
        SpotStatus.STABLE -> Triple(Icons.Default.Remove, Color(0xFF374151), Color(0xFFF3F4F6))
        SpotStatus.WORSENING -> Triple(Icons.Default.TrendingUp, DermaWarningText, Color(0xFFFEF3C7))
    }
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(status.label, fontSize = settings.textSm.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Itch line colour (the app's purple) and pain line colour (amber). Pain is also dashed, and
 *  both are named in the legend, so the two never rely on colour alone. */
val ItchColor = Color(0xFF7C3AED)
val PainColor = Color(0xFFD97706)

@Composable
fun SpotChangeSummary(track: ConditionTrack) {
    val settings = LocalAppSettings.current
    val scans = track.scans // oldest first
    if (scans.size < 2) {
        Text(
            "Scan this spot again to see how it changes.",
            fontSize = settings.textBase.sp,
            color = settings.textSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        return
    }
    var showCompare by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val first = scans.first()
    val latest = scans.last()
    val assessment = assessSpot(track)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF8F7FC))
            .padding(14.dp)
    ) {
        // Title + one info button. The caveats live behind it once, instead of under every card.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How it's going", fontSize = settings.textMd.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "How this is worked out", tint = DermaSubtle, modifier = Modifier.size(18.dp))
            }
        }
        // Safety note stays visible: it is the one line that asks the user to act.
        if (assessment?.status == SpotStatus.WORSENING) {
            Text("Getting worse? See a dermatologist.", fontSize = settings.textSm.sp, fontWeight = FontWeight.SemiBold, color = DermaWarningText)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // First -> latest as tiles: the latest value large, the change underneath with an arrow.
        val tiles = buildList {
            val fs = scans.firstOrNull { it.spotCount != null }
            val ls = scans.lastOrNull { it.spotCount != null }
            if (fs != null && ls != null && fs.scanId != ls.scanId) add(ChangeTileData("Spots", "${fs.spotCount}", "${ls.spotCount}", fs.spotCount!!, ls.spotCount!!))
            val fi = scans.firstOrNull { it.itchLevel >= 0 }
            val li = scans.lastOrNull { it.itchLevel >= 0 }
            if (fi != null && li != null && fi.scanId != li.scanId) add(ChangeTileData("Itch", CheckInOptions.levelLabel(fi.itchLevel), CheckInOptions.levelLabel(li.itchLevel), fi.itchLevel, li.itchLevel))
            val fp = scans.firstOrNull { it.painLevel >= 0 }
            val lp = scans.lastOrNull { it.painLevel >= 0 }
            if (fp != null && lp != null && fp.scanId != lp.scanId) add(ChangeTileData("Pain", CheckInOptions.levelLabel(fp.painLevel), CheckInOptions.levelLabel(lp.painLevel), fp.painLevel, lp.painLevel))
        }
        if (tiles.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                tiles.forEach { ChangeTile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        if (latest.feelsVsLast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = DermaSubtle, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("You said: ${latest.feelsVsLast.lowercase()}", fontSize = settings.textSm.sp, color = settings.textSecondary)
            }
        }

        // One chart at a time. Symptoms and spot count are different units, so they never share an axis.
        val symptomPoints = scans.filter { it.itchLevel >= 0 || it.painLevel >= 0 }
        val spotPoints = scans.filter { it.spotCount != null }
        val hasSymptoms = symptomPoints.size >= 2
        val hasSpots = spotPoints.size >= 2
        if (hasSymptoms || hasSpots) {
            var showSpots by remember { mutableStateOf(!hasSymptoms) }
            Spacer(modifier = Modifier.height(12.dp))
            if (hasSymptoms && hasSpots) {
                SegmentedToggle(listOf("Symptoms", "Spots"), if (showSpots) 1 else 0) { showSpots = it == 1 }
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (showSpots && hasSpots) {
                SpotCountChart(
                    counts = spotPoints.map { it.spotCount!! },
                    xLabels = listOf(friendlyShortDate(spotPoints.first().rawDate), friendlyShortDate(spotPoints.last().rawDate))
                )
            } else if (hasSymptoms) {
                SymptomTrendChart(
                    itch = symptomPoints.map { it.itchLevel.takeIf { v -> v >= 0 }?.toFloat() },
                    pain = symptomPoints.map { it.painLevel.takeIf { v -> v >= 0 }?.toFloat() },
                    xLabels = listOf(friendlyShortDate(symptomPoints.first().rawDate), friendlyShortDate(symptomPoints.last().rawDate))
                )
            }
        }

        if (first.imagePath.isNotEmpty() || latest.imagePath.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            PillButton(
                text = "Compare photos",
                icon = Icons.Default.CompareArrows,
                container = Color.White,
                content = DermaGreenDark,
                bordered = true,
                height = 44.dp,
                onClick = { showCompare = true }
            )
        }
    }

    if (showInfo) {
        DermaAlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("How this works", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DermaLens compares your first and latest scan of this spot: spots detected, itch, pain, and how you said it feels.")
                    Text("If more of these got better than worse, the spot is Improving. If more got worse, it's Worsening. Otherwise it's Stable.")
                    Text("This is not a severity score.", fontWeight = FontWeight.SemiBold)
                    Text("Spot counts change with distance and lighting, so take each photo the same way.")
                    assessment?.let { a ->
                        Text("This spot: ${a.reasons.joinToString("; ")}.", color = DermaSubtle)
                    }
                }
            },
            confirmButton = { Button(onClick = { showInfo = false }) { Text("Got it") } }
        )
    }

    if (showCompare) {
        DermaAlertDialog(
            onDismissRequest = { showCompare = false },
            title = { Text("${track.condition}: first vs latest", fontWeight = FontWeight.Bold) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComparePhoto(first, "First", Modifier.weight(1f))
                    ComparePhoto(latest, "Latest", Modifier.weight(1f))
                }
            },
            confirmButton = { Button(onClick = { showCompare = false }) { Text("Close") } }
        )
    }
}

private data class ChangeTileData(val label: String, val firstText: String, val latestText: String, val firstValue: Int, val latestValue: Int)

/** Latest value large; underneath, an arrow + "from X" (or "same"). Lower is better for every
 *  measure here. Arrow icon and wording carry the direction, colour only reinforces it. */
@Composable
private fun ChangeTile(d: ChangeTileData, modifier: Modifier) {
    val settings = LocalAppSettings.current
    val (icon, color, sub) = when {
        d.latestValue < d.firstValue -> Triple(Icons.Default.ArrowDownward, DermaSuccessText, "from ${d.firstText}")
        d.latestValue > d.firstValue -> Triple(Icons.Default.ArrowUpward, DermaWarningText, "from ${d.firstText}")
        else -> Triple(Icons.Default.Remove, settings.textSecondary, "same")
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${d.label}: ${d.firstText} at first, ${d.latestText} now"
            }
    ) {
        Text(d.label, fontSize = settings.textSm.sp, color = settings.textSecondary)
        Text(d.latestText, fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary, maxLines = 1)
        // May wrap ("from / Moderate") in a narrow tile -- better than cutting the word off.
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text(sub, fontSize = settings.textSm.sp, fontWeight = FontWeight.SemiBold, color = color, lineHeight = (settings.textSm * 1.25f).sp)
        }
    }
}

/** Two-option pill switch (e.g. Symptoms | Spots). */
@Composable
fun SegmentedToggle(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val settings = LocalAppSettings.current
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFEDE9FE)).padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Text(
                label,
                fontSize = settings.textSm.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (on) Color.White else DermaGreenDark,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) DermaGreen else Color.Transparent)
                    .clickable { onSelect(i) }
                    .semantics { selected = on }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

/** Tiny trend line for a collapsed card header. Decorative: the status badge next to it says the
 *  same thing in words. Uses itch if answered, else spot count. */
@Composable
fun MiniTrend(track: ConditionTrack, modifier: Modifier = Modifier) {
    val itch = track.scans.filter { it.itchLevel >= 0 }.map { it.itchLevel.toFloat() }
    val values = if (itch.size >= 2) itch else track.scans.mapNotNull { it.spotCount?.toFloat() }
    if (values.size < 2) return
    val top = maxOf(values.max(), 1f)
    Canvas(modifier = modifier.width(52.dp).height(22.dp)) {
        fun x(i: Int) = size.width * i / (values.size - 1)
        fun y(v: Float) = size.height - 3.dp.toPx() - (size.height - 6.dp.toPx()) * (v / top)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, ItchColor.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(ItchColor, radius = 3.dp.toPx(), center = Offset(x(values.lastIndex), y(values.last())))
    }
}


@Composable
private fun ComparePhoto(scan: ScanEntry, label: String, modifier: Modifier) {
    val settings = LocalAppSettings.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3F4F6)),
            contentAlignment = Alignment.Center
        ) {
            if (scan.imagePath.isNotEmpty()) {
                AsyncImage(model = scan.imagePath, contentDescription = "$label scan photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.ImageNotSupported, contentDescription = "No photo saved", tint = DermaMuted)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, fontSize = settings.textBase.sp, fontWeight = FontWeight.SemiBold, color = settings.textPrimary)
        Text(friendlyShortDate(scan.rawDate), fontSize = settings.textSm.sp, color = settings.textSecondary)
    }
}

fun friendlyShortDate(millis: Long): String =
    java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(millis))

/**
 * Itch and pain (0 None .. 3 Severe) over a run of scans or periods. One shared 0-3 axis (same
 * unit, so no dual axis). A null value is a gap. Legend above, labels on the y axis, first/last
 * x labels below.
 */
@Composable
fun SymptomTrendChart(itch: List<Float?>, pain: List<Float?>, xLabels: List<String>, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val gridColor = Color(0xFFE5E7EB)
    Column(modifier = modifier.fillMaxWidth().semantics {
        contentDescription = "Itch and pain over time. Itch: " +
            itch.joinToString { v -> v?.let { CheckInOptions.levelLabel(it.toInt()) } ?: "not answered" } +
            ". Pain: " + pain.joinToString { v -> v?.let { CheckInOptions.levelLabel(it.toInt()) } ?: "not answered" }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendKey(ItchColor, dashed = false)
            Text(" Itch", fontSize = settings.textSm.sp, color = settings.textSecondary)
            Spacer(modifier = Modifier.width(14.dp))
            LegendKey(PainColor, dashed = true)
            Text(" Pain", fontSize = settings.textSm.sp, color = settings.textSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            // Each label is ~16dp tall; the canvas below is inset by half that so the gridlines
            // line up with the label centres.
            Column(modifier = Modifier.fillMaxHeight().width(62.dp), verticalArrangement = Arrangement.SpaceBetween) {
                CheckInOptions.levels.reversed().forEach {
                    Text(it, fontSize = 10.sp, color = DermaSubtle, lineHeight = 16.sp, modifier = Modifier.height(16.dp))
                }
            }
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp, horizontal = 6.dp)) {
                val n = maxOf(itch.size, pain.size)
                fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
                fun y(v: Float) = size.height * (1f - v / 3f)
                for (level in 0..3) {
                    drawLine(gridColor, Offset(0f, y(level.toFloat())), Offset(size.width, y(level.toFloat())), strokeWidth = 1.dp.toPx())
                }
                fun series(values: List<Float?>, color: Color, dashed: Boolean) {
                    val path = Path()
                    var drawing = false
                    values.forEachIndexed { i, v ->
                        if (v == null) { drawing = false; return@forEachIndexed }
                        if (!drawing) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                        drawing = true
                    }
                    drawPath(
                        path, color,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
                        )
                    )
                    values.forEachIndexed { i, v ->
                        if (v != null) {
                            // White ring so overlapping itch/pain points stay distinguishable.
                            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x(i), y(v)))
                            drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
                        }
                    }
                }
                series(itch, ItchColor, dashed = false)
                series(pain, PainColor, dashed = true)
            }
        }
        if (xLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 62.dp)) {
                Text(xLabels.first(), fontSize = 10.sp, color = DermaSubtle, modifier = Modifier.weight(1f))
                if (xLabels.size > 1) Text(xLabels.last(), fontSize = 10.sp, color = DermaSubtle)
            }
        }
    }
}

/** Detection count per scan, oldest to newest. One series, so no legend: the title above names it.
 *  Y axis from 0 to the highest count; the latest value is labelled at the end of the line. */
@Composable
fun SpotCountChart(counts: List<Int>, xLabels: List<String>) {
    val settings = LocalAppSettings.current
    val gridColor = Color(0xFFE5E7EB)
    val lineColor = DermaGreen
    val top = maxOf(counts.maxOrNull() ?: 1, 1)
    Column(modifier = Modifier.fillMaxWidth().semantics {
        contentDescription = "Spots detected per scan, oldest to newest: " + counts.joinToString(", ")
    }) {
        Row(modifier = Modifier.fillMaxWidth().height(90.dp)) {
            Column(modifier = Modifier.fillMaxHeight().width(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("$top", fontSize = 10.sp, color = DermaSubtle, lineHeight = 16.sp, modifier = Modifier.height(16.dp))
                Text("0", fontSize = 10.sp, color = DermaSubtle, lineHeight = 16.sp, modifier = Modifier.height(16.dp))
            }
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp, horizontal = 6.dp)) {
                val n = counts.size
                fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
                fun y(v: Int) = size.height * (1f - v.toFloat() / top)
                drawLine(gridColor, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, y(top)), Offset(size.width, y(top)), strokeWidth = 1.dp.toPx())
                val path = Path()
                counts.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                counts.forEachIndexed { i, v ->
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x(i), y(v)))
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 28.dp)) {
            Text(xLabels.first(), fontSize = 10.sp, color = DermaSubtle, modifier = Modifier.weight(1f))
            Text("${xLabels.last()} · latest ${counts.last()}", fontSize = 10.sp, color = settings.textSecondary)
        }
    }
}

@Composable
private fun LegendKey(color: Color, dashed: Boolean) {
    Canvas(modifier = Modifier.width(18.dp).height(10.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
        )
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
    }
}
