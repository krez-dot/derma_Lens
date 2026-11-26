@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dermalens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dermalens.app.ui.LocalAppSettings

/**
 * The pre-scan check-in (panel revision: "provide questions before scanning"). Asked before the
 * camera opens. The answers are the user's own report and never change what the model detects:
 * they exist so Self Monitoring and Insights have something to track over time, since the model
 * gives a condition and a confidence, not a severity. They stay on the phone (and in the optional
 * history backup); they are never part of a research upload.
 */
data class SymptomCheckIn(
    val bodyArea: String = "",
    val duration: String = "",
    val itchLevel: Int = -1,
    val painLevel: Int = -1,
    val feelsVsLast: String = "",
    val triggers: List<String> = emptyList()
) {
    /** Compact form for the ScanResult route. Every value comes from the fixed option lists
     *  below, none of which contain ';', '=' or '|'. */
    fun encode(): String = listOf(
        "a=$bodyArea", "d=$duration", "i=$itchLevel", "p=$painLevel", "f=$feelsVsLast",
        "t=${triggers.joinToString("|")}"
    ).joinToString(";")

    companion object {
        fun decode(raw: String?): SymptomCheckIn? {
            if (raw.isNullOrBlank()) return null
            val map = raw.split(";").mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            return SymptomCheckIn(
                bodyArea = map["a"].orEmpty(),
                duration = map["d"].orEmpty(),
                itchLevel = map["i"]?.toIntOrNull() ?: -1,
                painLevel = map["p"]?.toIntOrNull() ?: -1,
                feelsVsLast = map["f"].orEmpty(),
                triggers = map["t"].orEmpty().split("|").filter { it.isNotBlank() }
            )
        }
    }
}

object CheckInOptions {
    val bodyAreas = listOf("Face", "Scalp", "Neck", "Chest or back", "Arms or hands", "Legs or feet", "Other")
    val durations = listOf("Less than a week", "1 to 4 weeks", "1 to 3 months", "More than 3 months")
    /** Index = stored level (0..3). */
    val levels = listOf("None", "Mild", "Moderate", "Severe")
    val feelings = listOf("Better", "About the same", "Worse")
    const val NOT_SURE = "Not sure"
    val triggers = listOf(
        "New soap, skincare or product",
        "Sun exposure",
        "Heat or sweating",
        "Stress or lack of sleep",
        "Certain food",
        "Pets or animals",
        "Close contact with someone with a rash",
        NOT_SURE
    )

    fun levelLabel(level: Int): String = levels.getOrNull(level) ?: "--"
}

/**
 * Full-screen check-in shown at the start of a scan. [isFollowUp] adds the "compared with last
 * time" question, which only makes sense on "Scan Again" of an existing timeline.
 */
@Composable
fun PreScanQuestionsScreen(
    isFollowUp: Boolean,
    onBack: () -> Unit,
    onDone: (SymptomCheckIn) -> Unit,
    // Set on "Scan Again": same spot, so the body-area question is skipped and this is reused.
    knownBodyArea: String = "",
    // Triggers this user has noted on 2+ scans (PersonalPatterns), most frequent first.
    usualTriggers: List<String> = emptyList()
) {
    val settings = LocalAppSettings.current
    val askArea = knownBodyArea.isEmpty()
    var bodyArea by rememberSaveable { mutableStateOf(knownBodyArea) }
    var duration by rememberSaveable { mutableStateOf("") }
    var itch by rememberSaveable { mutableStateOf(-1) }
    var pain by rememberSaveable { mutableStateOf(-1) }
    var feels by rememberSaveable { mutableStateOf("") }
    var triggers by rememberSaveable { mutableStateOf(listOf<String>()) }

    val complete = bodyArea.isNotEmpty() && duration.isNotEmpty() && itch >= 0 && pain >= 0 &&
        (!isFollowUp || feels.isNotEmpty())

    Box(modifier = Modifier.fillMaxSize().background(DermaPageBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DermaGlassTopBar(title = "Before you scan", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    "A few quick questions about this spot. Your answers help you spot patterns in Self Monitoring. They don't change the scan result.",
                    fontSize = settings.textMd.sp,
                    color = settings.textSecondary,
                    lineHeight = (settings.textMd * 1.45f).sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                var next = 1
                if (askArea) {
                    QuestionCard(number = next++, question = "Where on your body is it?") {
                        ChoiceChips(CheckInOptions.bodyAreas, chosen = setOf(bodyArea)) { bodyArea = it }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(DermaGreenLight).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = DermaGreenDark, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Same spot as before: $knownBodyArea", fontSize = settings.textMd.sp, fontWeight = FontWeight.SemiBold, color = DermaGreenDark)
                    }
                }
                QuestionCard(number = next++, question = "How long have you had it?") {
                    ChoiceChips(CheckInOptions.durations, chosen = setOf(duration)) { duration = it }
                }
                QuestionCard(number = next++, question = "How itchy is it right now?") {
                    ChoiceChips(CheckInOptions.levels, chosen = setOf(CheckInOptions.levelLabel(itch))) { itch = CheckInOptions.levels.indexOf(it) }
                }
                QuestionCard(number = next++, question = "Any pain, burning or tenderness?") {
                    ChoiceChips(CheckInOptions.levels, chosen = setOf(CheckInOptions.levelLabel(pain))) { pain = CheckInOptions.levels.indexOf(it) }
                }
                if (isFollowUp) {
                    QuestionCard(number = next++, question = "Compared with your last scan of this spot, it feels...") {
                        ChoiceChips(CheckInOptions.feelings, chosen = setOf(feels)) { feels = it }
                    }
                }
                // The user's usual triggers (learned on-device) go first and are starred. Never
                // pre-ticked: suggesting an answer would bias what the user reports.
                val usual = usualTriggers.filter { it in CheckInOptions.triggers }
                val triggerOrder = usual + CheckInOptions.triggers.filter { it !in usual }
                QuestionCard(
                    number = next,
                    question = "Did you notice any of these before it appeared or got worse?",
                    hint = if (usual.isEmpty()) "Optional. Choose any that apply." else "Optional. Choose any that apply. ★ = you often note this."
                ) {
                    ChoiceChips(triggerOrder, chosen = triggers.toSet(), starred = usual.toSet()) { option ->
                        triggers = when {
                            option in triggers -> triggers - option
                            // "Not sure" and a specific trigger contradict each other.
                            option == CheckInOptions.NOT_SURE -> listOf(option)
                            else -> triggers - CheckInOptions.NOT_SURE + option
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = DermaSubtle, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Saved only with your scan. Never included in research uploads.", fontSize = settings.textSm.sp, color = DermaSubtle)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (!complete) {
                    Text(
                        "Answer questions 1 to ${(if (askArea) 4 else 3) + (if (isFollowUp) 1 else 0)} to continue.",
                        fontSize = settings.textSm.sp,
                        color = settings.textSecondary,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                    )
                }
                PillButton(
                    text = "Continue to camera",
                    icon = Icons.Default.ArrowForward,
                    container = if (complete) DermaGreen else Color(0xFFD1D5DB),
                    elevated = complete,
                    enabled = complete,
                    onClick = {
                        onDone(
                            SymptomCheckIn(
                                bodyArea = bodyArea,
                                duration = duration,
                                itchLevel = itch,
                                painLevel = pain,
                                feelsVsLast = if (isFollowUp) feels else "",
                                triggers = triggers
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(number: Int, question: String, hint: String? = null, content: @Composable () -> Unit) {
    val settings = LocalAppSettings.current
    SoftCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$number. $question", fontSize = settings.textLg.sp, fontWeight = FontWeight.SemiBold, color = settings.textPrimary, lineHeight = (settings.textLg * 1.3f).sp)
            if (hint != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(hint, fontSize = settings.textSm.sp, color = settings.textSecondary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ChoiceChips(options: List<String>, chosen: Set<String>, starred: Set<String> = emptySet(), onClick: (String) -> Unit) {
    val settings = LocalAppSettings.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isOn = option in chosen
            val isStarred = option in starred
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (isOn) DermaGreen else Color(0xFFF3F4F6))
                    .then(if (settings.highContrast) Modifier.border(1.dp, if (isOn) DermaGreenDark else HcBorder, shape) else Modifier)
                    .clickable(role = Role.Checkbox) { onClick(option) }
                    .semantics { selected = isOn; contentDescription = if (isStarred) "$option, you often note this" else option }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isOn) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (isStarred) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(option, fontSize = settings.textMd.sp, fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal, color = if (isOn) Color.White else settings.textPrimary)
            }
        }
    }
}

/** "Your answers" card on the result screen, so the user sees what was saved with this scan. */
@Composable
fun CheckInSummaryCard(checkIn: SymptomCheckIn, matches: List<PersonalPatterns.Pattern> = emptyList()) {
    val settings = LocalAppSettings.current
    val rows = listOfNotNull(
        checkIn.bodyArea.takeIf { it.isNotEmpty() }?.let { "Where" to it },
        checkIn.duration.takeIf { it.isNotEmpty() }?.let { "How long" to it },
        checkIn.itchLevel.takeIf { it >= 0 }?.let { "Itch" to CheckInOptions.levelLabel(it) },
        checkIn.painLevel.takeIf { it >= 0 }?.let { "Pain" to CheckInOptions.levelLabel(it) },
        checkIn.feelsVsLast.takeIf { it.isNotEmpty() }?.let { "Vs last scan" to it },
        checkIn.triggers.takeIf { it.isNotEmpty() }?.let { "Noticed" to it.joinToString(", ") }
    )
    if (rows.isEmpty()) return
    ResultCard(icon = Icons.Default.Check, iconBg = DermaGreenLight, iconTint = DermaGreen, title = "Your answers") {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(label, fontSize = settings.textMd.sp, color = settings.textSecondary, modifier = Modifier.width(110.dp))
                Text(value, fontSize = settings.textMd.sp, fontWeight = FontWeight.SemiBold, color = settings.textPrimary, modifier = Modifier.weight(1f))
            }
        }
        // This scan's answers line up with a pattern learned from the user's earlier check-ins.
        matches.take(2).forEach { pattern ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DermaGreenLight).padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = DermaGreenDark, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Matches your pattern", fontSize = settings.textBase.sp, fontWeight = FontWeight.Bold, color = DermaGreenDark)
                    Text(pattern.tip(), fontSize = settings.textBase.sp, color = DermaGreenDark, lineHeight = (settings.textBase * 1.4f).sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text("Your own report. It doesn't change the scan result.", fontSize = settings.textSm.sp, color = DermaSubtle)
    }
}
