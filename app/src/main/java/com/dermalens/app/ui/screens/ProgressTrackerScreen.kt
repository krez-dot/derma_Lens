package com.dermalens.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dermalens.app.data.db.DermaDatabase
import com.dermalens.app.data.sync.ScanHistorySync
import com.dermalens.app.navigation.Screen
import com.dermalens.app.ui.LocalAppSettings
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanEntry(val id: Int, val date: String, val confidence: Float, val notes: String, val imagePath: String = "", val rawDate: Long = 0L)
// trackGroupId identifies which specific occurrence/spot this card represents -- passed back to
// Scan when "Scan Again" is tapped so the resulting new scan explicitly continues *this* card's
// trend instead of just matching by condition name. See ScanRecord.trackGroupId.
data class ConditionTrack(val condition: String, val color: Color, val emoji: String, val scans: List<ScanEntry>, val trackGroupId: Int)

val mockProgressData = listOf(
    ConditionTrack("Papular Acne", Color(0xFFE53935), "🔴", listOf(
        ScanEntry(0, "May 1, 2026", 94.3f, "Initial scan — widespread breakout"),
        ScanEntry(0, "May 5, 2026", 89.2f, "Slight improvement after treatment"),
        ScanEntry(0, "May 10, 2026", 91.5f, "Significant improvement noted"),
    ), trackGroupId = 0),
    ConditionTrack("Eczema", Color(0xFFFF9800), "🟠", listOf(
        ScanEntry(0, "Apr 20, 2026", 87.6f, "Flare-up detected on forearm"),
        ScanEntry(0, "Apr 28, 2026", 85.1f, "Moisturizer routine helping"),
    ), trackGroupId = 0),
    ConditionTrack("Melasma", Color(0xFF795548), "🟤", listOf(
        ScanEntry(0, "May 3, 2026", 91.2f, "Brown patches on cheeks detected"),
    ), trackGroupId = 0)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressTrackerScreen(navController: NavController) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val db = remember { DermaDatabase.getDatabase(context) }
    val prefs = remember { context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var totalScans by remember { mutableStateOf(0) }
    var daysTracked by remember { mutableStateOf(0) }
    // Distinct conditions, not conditionTracks.size -- two separate Melasma timelines are still
    // one condition (and Profile's own "Conditions" stat already counts it this way).
    var conditionCount by remember { mutableStateOf(0) }
    var conditionTracks by remember { mutableStateOf(emptyList<ConditionTrack>()) }

    LaunchedEffect(refreshKey) {
        val savedEmail = prefs.getString(DermaPrefs.KEY_USER_EMAIL, "") ?: ""
        val user = db.userDao().getUserByEmail(savedEmail)
        if (user != null) {
            val scans = db.scanRecordDao().getScansByUserOnce(user.userId)
            totalScans = scans.size
            conditionCount = scans.map { it.condition }.distinct().size
            // Reset to 0 when empty -- otherwise deleting the last scan left the old value up.
            daysTracked = if (scans.isEmpty()) 0 else {
                val earliest = scans.minOf { it.scanDate }
                ((System.currentTimeMillis() - earliest) / (1000L * 60L * 60L * 24L)).toInt() + 1
            }
            // Grouped by trackGroupId, not condition -- two unrelated occurrences that happen to
            // classify the same (a wart on one finger, an unrelated new wart on a toe) get their
            // own separate cards instead of being conflated into one misleading trend. Falls back
            // to the scan's own id for any pre-migration row that somehow has a null group (there
            // shouldn't be any post-migration, since saveScan always assigns one on insert).
            val grouped = scans.groupBy { it.trackGroupId ?: it.id }
            conditionTracks = grouped.map { (groupId, scanList) ->
                val sortedScans = scanList.sortedBy { it.scanDate }
                // The card shows the *latest* classification for this group, not the first --
                // reflects current status, matching the timeline's own "Latest scan" emphasis.
                val condition = sortedScans.last().condition
                val mockTrack = mockProgressData.find { it.condition == condition }
                ConditionTrack(
                    condition = condition,
                    // The condition's own colour, the same one the result screen uses.
                    color = mockDetectionResults.find { it.condition == condition }?.color ?: mockTrack?.color ?: Color(0xFF7C3AED),
                    emoji = mockTrack?.emoji ?: "🔵",
                    // Ascending by date (oldest first) -- the DAO query itself returns newest
                    // first (for other screens that want that), but this timeline's visuals
                    // (filled dot, highlighted card, "Latest scan" label) are built around the
                    // *last* list item being the most recent one, matching the natural top-to-
                    // bottom "journey" reading of a progress timeline.
                    scans = sortedScans.map { scan ->
                        ScanEntry(
                            id = scan.id,
                            date = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(scan.scanDate)),
                            confidence = scan.confidence,
                            notes = scan.notes,
                            imagePath = scan.imagePath,
                            rawDate = scan.scanDate
                        )
                    },
                    trackGroupId = groupId
                ) to (sortedScans.lastOrNull()?.scanDate ?: 0L)
            }.sortedByDescending { (_, latestRawDate) -> latestRawDate }.map { (track, _) -> track }
        }
    }

    Scaffold(
        bottomBar = { DermaBottomNavBar(navController) },
        containerColor = DermaPageBackground,
        // Floats above the tab bar so a new scan is one tap away however long the list gets.
        floatingActionButton = {
            if (conditionTracks.isNotEmpty()) {
                PillButton(
                    text = "Start New Scan",
                    icon = Icons.Default.CameraAlt,
                    elevated = true,
                    modifier = Modifier.width(220.dp),
                    onClick = { navController.navigate(Screen.Scan.createRoute()) { launchSingleTop = true } }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // Extra bottom room so the last card can scroll clear of the floating button.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Eyebrow("Your journey")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Progress", fontSize = settings.textDisplay.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = settings.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Every scan you've saved, over time", fontSize = settings.textMd.sp, color = settings.textSecondary)
                }
            }

            item { DiagnosticAidDisclaimer() }

            item {
                StatRow(listOf("$totalScans" to "Total scans", "$conditionCount" to "Conditions", "$daysTracked" to "Days tracked"))
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (settings.highContrast) Color(0xFFEDE9FE) else DermaGreenLight)
                        .then(if (settings.highContrast) Modifier.border(1.dp, DermaGreenDark, RoundedCornerShape(18.dp)) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Scan the same spot regularly to see how it changes over time.", fontSize = settings.textBase.sp, color = DermaGreenDark, lineHeight = (settings.textBase * 1.4f).sp)
                }
            }

            item {
                Text(
                    "Condition timelines",
                    fontSize = settings.textLg.sp,
                    fontWeight = FontWeight.Bold,
                    color = settings.textPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (conditionTracks.isEmpty()) {
                item {
                    SoftCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = com.dermalens.app.R.drawable.dermalens_logo),
                                contentDescription = null,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No scans yet", fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Start scanning to track your skin health journey.",
                                fontSize = settings.textBase.sp,
                                color = settings.textSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            PillButton(
                                text = "Start Your First Scan",
                                icon = Icons.Default.CameraAlt,
                                elevated = true,
                                onClick = { navController.navigate(Screen.Scan.createRoute()) { launchSingleTop = true } }
                            )
                        }
                    }
                }
            }

            itemsIndexed(conditionTracks) { index, track ->
                EntranceAnimation(delayMillis = index.coerceAtMost(6) * 70) {
                    ConditionTrackCard(
                        track = track,
                        onScanAgain = { navController.navigate(Screen.Scan.createRoute(continueTrackGroupId = track.trackGroupId)) { launchSingleTop = true } },
                        onDeleteScan = { scanId ->
                            scope.launch {
                                // The dialog promises the scan is permanently removed, so the
                                // saved skin photo goes too -- deleting only the row used to
                                // leave every deleted scan's image behind in scan_photos/.
                                val scan = db.scanRecordDao().getScanById(scanId)
                                val imagePath = scan?.imagePath.orEmpty()
                                if (imagePath.isNotEmpty()) {
                                    withContext(Dispatchers.IO) { java.io.File(imagePath).delete() }
                                }
                                db.scanRecordDao().deleteScan(scanId)
                                // Backup on: tell the other phones this scan is gone (tombstone),
                                // so they don't restore or re-upload it.
                                scan?.let { ScanHistorySync.pushDelete(context, it.scanDate) }
                                refreshKey++
                            }
                        },
                        onEditNote = { scanId, newNote ->
                            scope.launch {
                                db.scanRecordDao().updateNotes(scanId, newNote)
                                ScanHistorySync.pushNotes(context, scanId)
                                refreshKey++
                            }
                        },
                        onOpenScan = { scan ->
                            navController.navigate(Screen.ScanResult.createRoute(imageUri = scan.imagePath.ifEmpty { null }, scanId = scan.id))
                        }
                    )
                }
            }

        }
    }
}

/**
 * Three stats side by side -- or, at Large/XL font, stacked in one card, since three columns are
 * too narrow there and a word like "Conditions" would break mid-word.
 */
@Composable
fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    if (settings.fontScale > 1.05f) {
        SoftCard(modifier = modifier.fillMaxWidth()) {
            stats.forEachIndexed { i, (value, label) ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = if (settings.highContrast) Color(0xFFD1D5DB) else Color(0xFFF3F4F6))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = settings.textMd.sp, color = settings.textSecondary, modifier = Modifier.weight(1f))
                    Text(value, fontSize = settings.textXxl.sp, fontWeight = FontWeight.Bold, color = if (settings.highContrast) DermaGreenDark else DermaGreen)
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.height(IntrinsicSize.Min)) {
            stats.forEach { (value, label) -> StatCard(value, label, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    SoftCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
            Text(value, fontSize = settings.textDisplay.sp, fontWeight = FontWeight.Bold, color = if (settings.highContrast) DermaGreenDark else DermaGreen)
            Text(label, fontSize = settings.textSm.sp, color = settings.textSecondary)
        }
    }
}

@Composable
fun ConditionTrackCard(track: ConditionTrack, onScanAgain: () -> Unit, onDeleteScan: (Int) -> Unit, onEditNote: (Int, String) -> Unit, onOpenScan: (ScanEntry) -> Unit) {
    var isExpanded by remember { mutableStateOf(true) }
    val settings = LocalAppSettings.current

    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(track.color.copy(alpha = if (settings.highContrast) 0.2f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(track.color))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.condition, fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
                    Text(
                        "${track.scans.size} ${if (track.scans.size == 1) "scan" else "scans"} · ${track.scans.lastOrNull()?.let { friendlyScanDate(it.rawDate).substringBefore(",").replace("Today", "today").replace("Yesterday", "yesterday") } ?: "--"}",
                        fontSize = settings.textBase.sp,
                        color = settings.textSecondary,
                        maxLines = 1
                    )
                }
                val chevronRotation by animateFloatAsState(if (isExpanded) 0f else 180f, label = "chevronRotation")
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFF3F4F6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = if (settings.highContrast) Color(0xFF444444) else DermaSubtle,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevronRotation }
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(280)) + fadeIn(tween(280)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(180))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    // Newest first, so the scan you just saved is right at the top.
                    val newestFirst = track.scans.asReversed()
                    newestFirst.forEachIndexed { index, scan ->
                        TimelineNode(
                            scan = scan,
                            // "Latest" only means something once a timeline has more than one scan.
                            isLatest = index == 0 && newestFirst.size > 1,
                            isLastRow = index == newestFirst.size - 1,
                            color = track.color,
                            onDelete = { onDeleteScan(scan.id) },
                            onEditNote = { newNote -> onEditNote(scan.id, newNote) },
                            onOpenScan = { onOpenScan(scan) }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PillButton(
                        text = "Scan Again",
                        icon = Icons.Default.CameraAlt,
                        container = DermaGreenLight,
                        content = DermaGreenDark,
                        height = 46.dp,
                        onClick = onScanAgain
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineNode(scan: ScanEntry, isLatest: Boolean, isLastRow: Boolean, color: Color, onDelete: () -> Unit, onEditNote: (String) -> Unit, onOpenScan: () -> Unit) {
    val settings = LocalAppSettings.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditNoteDialog by remember { mutableStateOf(false) }
    var editedNote by remember(scan.id, scan.notes) { mutableStateOf(scan.notes) }

    if (showDeleteDialog) {
        DermaAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            titleContentColor = Color(0xFF111827),
            textContentColor = Color(0xFF374151),
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete Scan?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently remove the scan from ${scan.date}. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = DermaDanger),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = DermaSubtle)
                }
            }
        )
    }

    if (showEditNoteDialog) {
        DermaAlertDialog(
            onDismissRequest = { showEditNoteDialog = false },
            containerColor = Color.White,
            titleContentColor = Color(0xFF111827),
            textContentColor = Color(0xFF374151),
            shape = RoundedCornerShape(28.dp),
            title = { Text("Edit Note", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editedNote,
                    onValueChange = { editedNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("How does it look today?") }
                )
            },
            confirmButton = {
                Button(onClick = { showEditNoteDialog = false; onEditNote(editedNote) }) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editedNote = scan.notes; showEditNoteDialog = false }) {
                    Text("Cancel", color = DermaSubtle)
                }
            }
        )
    }

    val rowShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLastRow) 0.dp else 8.dp)
            .clip(rowShape)
            .background(
                when {
                    isLatest -> color.copy(alpha = 0.07f)
                    else -> Color(0xFFF8F7FC)
                }
            )
            .then(if (settings.highContrast) Modifier.border(1.dp, if (isLatest) color else HcBorder, rowShape) else Modifier)
            .clickable { onOpenScan() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail: the saved photo, or a soft placeholder when none was kept.
        Box(
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (scan.imagePath.isNotEmpty()) {
                AsyncImage(
                    model = scan.imagePath,
                    contentDescription = "Scan photo from ${scan.date}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.ImageNotSupported, contentDescription = null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friendlyScanDate(scan.rawDate),
                    fontSize = settings.textMd.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = settings.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isLatest) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Latest",
                        fontSize = settings.textSm.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                scan.notes.ifBlank { "Tap the pencil to add a note" },
                fontSize = settings.textBase.sp,
                color = if (scan.notes.isBlank()) DermaMuted else settings.textSecondary,
                lineHeight = (settings.textBase * 1.35f).sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${scan.confidence.roundToInt()}%", fontSize = settings.textMd.sp, fontWeight = FontWeight.Bold, color = DermaGreen)
            Row {
                IconButton(onClick = { editedNote = scan.notes; showEditNoteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit note", tint = DermaSubtle, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete scan", tint = DermaMuted, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}
