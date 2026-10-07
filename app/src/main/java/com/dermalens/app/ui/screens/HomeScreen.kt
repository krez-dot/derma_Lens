package com.dermalens.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dermalens.app.R
import com.dermalens.app.data.db.DermaDatabase
import com.dermalens.app.data.model.ScanRecord
import com.dermalens.app.navigation.Screen
import com.dermalens.app.ui.LocalAppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// route is the template used to detect "is this tab currently selected" (NavDestination.route
// always reports the raw {placeholder} template, never the filled-in runtime string) --
// navigateRoute is the actual, fully-resolved string passed to navigate(). They're the same for
// every tab except Scan, whose route now carries an optional continueTrackGroupId argument.
sealed class BottomNavItem(val route: String, val icon: ImageVector, val label: String, val navigateRoute: String = route) {
    object Home : BottomNavItem(Screen.Home.route, Icons.Default.Home, "Home")
    object Scan : BottomNavItem(Screen.Scan.route, Icons.Default.CameraAlt, "Scan", navigateRoute = Screen.Scan.createRoute())
    object Progress : BottomNavItem(Screen.ProgressTracker.route, Icons.Default.Timeline, "Progress")
    object Clinics : BottomNavItem(Screen.ClinicLocator.route, Icons.Default.LocationOn, "Clinics")
    object Profile : BottomNavItem(Screen.Profile.route, Icons.Default.Person, "Profile")
}

// Scan sits in the middle, drawn as a raised button (see DermaBottomNavBar).
val bottomNavItems = listOf(BottomNavItem.Home, BottomNavItem.Progress, BottomNavItem.Scan, BottomNavItem.Clinics, BottomNavItem.Profile)

@Composable
fun DermaBottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val settings = LocalAppSettings.current

    @Composable
    fun RowScope.NavItems() {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            val tint = if (selected) DermaGreen else if (settings.highContrast) Color(0xFF444444) else DermaMuted
            val navInteractionSource = remember { MutableInteractionSource() }
            val isScan = item == BottomNavItem.Scan
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .pressScale(navInteractionSource)
                    .clickable(
                        interactionSource = navInteractionSource,
                        indication = null,
                        onClick = {
                            navController.navigate(item.navigateRoute) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    .padding(vertical = 10.dp)
            ) {
                if (isScan) {
                    // Raised purple button that pokes above the bar. The 32dp box keeps the row's
                    // height the same as the other tabs; the circle overflows it upward.
                    Box(modifier = Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            modifier = Modifier
                                .requiredSize(60.dp)
                                .offset(y = (-18).dp)
                                .shadow(12.dp, CircleShape, ambientColor = DermaGreen, spotColor = DermaGreen)
                                .clip(CircleShape)
                                .background(
                                    if (settings.highContrast) Brush.linearGradient(listOf(DermaGreen, DermaGreenDark))
                                    else Brush.linearGradient(listOf(Color(0xFFA78BFA), DermaGreen))
                                )
                                .border(4.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CenterFocusWeak, contentDescription = item.label, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(item.label, color = DermaGreen, fontSize = settings.textSm.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) DermaGreenLight else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Icon(item.icon, contentDescription = item.label, tint = tint)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(item.label, color = tint, fontSize = settings.textSm.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }

    // Experimental "liquid glass" treatment: a floating, frosted pill instead of a flush,
    // opaque bar. No real backdrop blur (minSdk 26 predates Compose's RenderEffect blur, which
    // needs API 31+) -- translucency alone fakes the glass read safely on every supported device
    // instead of depending on a blur API that's unavailable to many. No shadow/border either --
    // both render as a hard flat outline rather than a soft blur on this emulator's renderer.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x224C1D95), spotColor = Color(0x224C1D95))
                .clip(RoundedCornerShape(28.dp))
                // High Contrast: fully opaque, with an outline so the bar's edge stays clear.
                .background(if (settings.highContrast) Color.White else Color.White.copy(alpha = 0.94f))
                .then(if (settings.highContrast) Modifier.border(1.dp, HcBorder, RoundedCornerShape(28.dp)) else Modifier)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) { NavItems() }
    }
}

/** Scanning tips for Home's "Tip of the Day" card: a short headline plus one line of detail. */
val scanningTips = listOf(
    "Soft light, clearer scan" to "Natural, indirect light helps DermaLens see the true colour and detail of your skin.",
    "Keep a little distance" to "Hold your phone about 15–20 cm from the area you're scanning.",
    "A dermatologist knows best" to "DermaLens is a helper, not a diagnosis. See a dermatologist for anything that worries you.",
    "Wipe the lens" to "A quick clean of your camera lens makes scans noticeably sharper.",
    "Skip the harsh sun" to "Direct sunlight washes out colour. A bright spot indoors works better.",
    "Steady does it" to "Keep your hand still while capturing. Blurry photos are harder to read.",
    "Clean, dry skin" to "Wash and pat the area dry before scanning for the most accurate result.",
    "Scan a few times" to "Scanning the same spot more than once helps you see whether a result is consistent."
)

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** "Today, 8:42 AM", "Yesterday, 6:10 PM", or "Sep 28, 6:10 PM". */
fun friendlyScanDate(millis: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    val scan = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val today = java.util.Calendar.getInstance()
    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(scan, today) -> "Today, $time"
        sameDay(scan, yesterday) -> "Yesterday, $time"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis)) + ", $time"
    }
}

/** Small uppercase purple label that sits above a title ("YOUR SKIN CHECK-IN"). */
@Composable
fun Eyebrow(text: String, color: Color = DermaGreen, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    Text(
        text.uppercase(),
        fontSize = settings.textSm.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = if (settings.highContrast && color == DermaGreen) DermaGreenDark else color,
        modifier = modifier
    )
}

/** White rounded card with a soft shadow -- the base surface of the new look. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(interaction, pressedScale = 0.97f) else Modifier)
            .shadow(10.dp, shape, ambientColor = Color(0x1A4C1D95), spotColor = Color(0x1A4C1D95))
            .then(if (settings.highContrast) Modifier.border(1.dp, HcBorder, shape) else Modifier)
            .clip(shape)
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
        content = content
    )
}

@Composable
fun HomeScreen(navController: NavController) {
    val tip = remember { scanningTips.random() }
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val db = remember { DermaDatabase.getDatabase(context) }
    val prefs = remember { context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var userId by remember { mutableStateOf<Int?>(null) }
    var firstName by remember { mutableStateOf("") }
    var recentScan by remember { mutableStateOf<ScanRecord?>(null) }
    var scanCount by remember { mutableStateOf(0) }
    var showContributePrompt by remember {
        mutableStateOf(NewUserSignal.pendingContributePrompt.also { NewUserSignal.pendingContributePrompt = false })
    }

    LaunchedEffect(Unit) {
        val savedEmail = prefs.getString(DermaPrefs.KEY_USER_EMAIL, "") ?: ""
        val user = db.userDao().getUserByEmail(savedEmail)
        userId = user?.userId
        firstName = user?.fullName?.trim()?.split(" ")?.firstOrNull() ?: ""
        // Opt-in scan history backup: merge with the cloud copy (restores scans saved on another
        // phone, applies deletions/edits made there). Silently skipped offline or when backup is
        // off; the scan list below updates on its own since it observes Room.
        if (user != null) {
            val restored = com.dermalens.app.data.sync.ScanHistorySync.reconcile(context)
            if (restored > 0) {
                android.widget.Toast.makeText(
                    context,
                    "Restored $restored scan${if (restored == 1) "" else "s"} from your backup",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(userId) {
        val id = userId ?: return@LaunchedEffect
        db.scanRecordDao().getScansByUser(id).collect { scans ->
            recentScan = scans.firstOrNull()
            scanCount = scans.size
        }
    }

    val startScan = { navController.navigate(Screen.Scan.createRoute()) { launchSingleTop = true } }

    Scaffold(
        bottomBar = { DermaBottomNavBar(navController) },
        containerColor = DermaPageBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Greeting
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Eyebrow("Your skin check-in")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (firstName.isNotEmpty()) "${greeting()}, $firstName" else greeting(),
                        fontSize = settings.textDisplay.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        lineHeight = (settings.textDisplay * 1.15f).sp,
                        color = settings.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Anything you want to keep an eye on?", fontSize = settings.textMd.sp, color = settings.textSecondary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                val avatarInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(avatarInteraction)
                        .shadow(8.dp, CircleShape, spotColor = DermaGreen)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFA78BFA), DermaGreen)))
                        .border(3.dp, Color.White, CircleShape)
                        .clickable(interactionSource = avatarInteraction, indication = null) {
                            navController.navigate(Screen.Profile.route) { launchSingleTop = true }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        firstName.firstOrNull()?.uppercase() ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = settings.textLg.sp
                    )
                    if (firstName.isEmpty()) Icon(Icons.Default.Person, contentDescription = "Profile", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            EntranceAnimation { LatestScanCard(recentScan, navController, onStartScan = startScan) }

            Spacer(modifier = Modifier.height(28.dp))

            Text("Where would you like to go?", fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            EntranceAnimation(delayMillis = 80) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StartScanCard(onClick = startScan)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        DestinationCard(
                            icon = Icons.Default.LocationOn,
                            iconTint = Color(0xFF2563EB),
                            iconBg = Color(0xFFEFF6FF),
                            title = "Clinics",
                            subtitle = "Find one near you",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { navController.navigate(Screen.ClinicLocator.route) }
                        )
                        DestinationCard(
                            icon = Icons.Default.TrendingUp,
                            iconTint = Color(0xFFE11D48),
                            iconBg = Color(0xFFFFF1F2),
                            title = "Progress",
                            subtitle = if (scanCount == 0) "No scans yet" else "$scanCount scan${if (scanCount == 1) "" else "s"}",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { navController.navigate(Screen.ProgressTracker.route) { launchSingleTop = true } }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            DiagnosticAidDisclaimer()
            Spacer(modifier = Modifier.height(16.dp))

            // Tip of the Day
            EntranceAnimation(delayMillis = 160) {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(DermaGreenLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Eyebrow("Tip of the day")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(tip.first, fontSize = settings.textMd.sp, fontWeight = FontWeight.SemiBold, color = settings.textPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(tip.second, fontSize = settings.textBase.sp, color = settings.textSecondary, lineHeight = (settings.textBase * 1.5f).sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Contribute to Research prompt -- shown once, right after a new account finishes email
    // verification (see NewUserSignal). Existing users logging back in never see this again.
    if (showContributePrompt) {
        DermaAlertDialog(
            onDismissRequest = { showContributePrompt = false },
            icon = { Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFF7C3AED)) },
            title = { Text("Help Improve DermaLens?", fontWeight = FontWeight.Bold, fontSize = settings.textXl.sp, color = Color(0xFF111827)) },
            text = {
                Text(
                    "Would you like to contribute your skin scan images and results to the DermaLens research dataset? This helps train and improve future versions of the AI model, especially for detecting conditions across a wider range of Filipino skin tones.\n\nYour scans are contributed anonymously and are never linked to your name or account. You can change this anytime in Profile > Contribute to Research.",
                    fontSize = settings.textMd.sp,
                    color = Color(0xFF374151),
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putBoolean(DermaPrefs.KEY_CONTRIBUTE_DATA, true).apply()
                        com.dermalens.app.worker.ContributionUploadScheduler.scheduleUpload(context)
                        showContributePrompt = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Agree", fontSize = settings.textMd.sp) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        prefs.edit().putBoolean(DermaPrefs.KEY_CONTRIBUTE_DATA, false).apply()
                        showContributePrompt = false
                    },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Disagree", fontSize = settings.textMd.sp) }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            titleContentColor = Color(0xFF111827),
            textContentColor = Color(0xFF374151)
        )
    }
}

/** Home's hero card: the most recent saved scan, or a nudge to take the first one. */
@Composable
private fun LatestScanCard(scan: ScanRecord?, navController: NavController, onStartScan: () -> Unit) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val openScan = {
        if (scan != null) {
            navController.navigate(Screen.ScanResult.createRoute(imageUri = scan.imagePath.ifEmpty { null }, scanId = scan.id))
        } else onStartScan()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.97f)
            .shadow(18.dp, shape, ambientColor = DermaGreen, spotColor = DermaGreen)
            .clip(shape)
            .background(
                if (settings.highContrast) Brush.linearGradient(listOf(DermaGreen, DermaGreenDark))
                else Brush.linearGradient(listOf(Color(0xFF9F67FF), DermaGreen, DermaGreenDark))
            )
            .clickable(interactionSource = interaction, indication = null, onClick = openScan)
    ) {
        // Soft decorative rings, like the light catching a lens.
        // matchParentSize, so these never make the card taller than its content.
        Box(modifier = Modifier.matchParentSize()) {
            Box(modifier = Modifier.requiredSize(220.dp).offset(x = (-70).dp, y = (-40).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
            Box(modifier = Modifier.requiredSize(160.dp).align(Alignment.BottomEnd).offset(x = 50.dp, y = 60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)))
        }
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .border(2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.dermalens_logo),
                    contentDescription = "DermaLens logo",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (scan != null) Color(0xFF6EE7B7) else Color.White.copy(alpha = 0.6f)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Eyebrow(if (scan != null) "Latest scan" else "Get started", color = Color.White.copy(alpha = 0.85f))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scan?.condition ?: "No scans yet",
                        fontSize = settings.textXxl.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (scan != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("${scan.confidence.roundToInt()}%", fontSize = settings.textBase.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (scan != null) friendlyScanDate(scan.scanDate) else "Your first scan takes under a minute",
                    fontSize = settings.textBase.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(if (scan != null) "View result" else "Start scanning", fontSize = settings.textBase.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The big purple "Start a skin scan" button-card. */
@Composable
private fun StartScanCard(onClick: () -> Unit) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.97f)
            .shadow(12.dp, shape, ambientColor = DermaGreen, spotColor = DermaGreen)
            .clip(shape)
            .background(if (settings.highContrast) Brush.linearGradient(listOf(DermaGreen, DermaGreenDark)) else Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF7C3AED))))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(modifier = Modifier.matchParentSize()) {
            Box(modifier = Modifier.requiredSize(150.dp).align(Alignment.CenterEnd).offset(x = 40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        }
        Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CenterFocusWeak, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Start a skin scan", fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Check a spot in under a minute", fontSize = settings.textBase.sp, color = Color.White.copy(alpha = 0.85f))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
        }
    }
}

/** Small white card linking to another part of the app. */
@Composable
private fun DestinationCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val settings = LocalAppSettings.current
    SoftCard(modifier = modifier, onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DermaMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(title, fontSize = settings.textLg.sp, fontWeight = FontWeight.Bold, color = settings.textPrimary)
            Text(subtitle, fontSize = settings.textBase.sp, color = settings.textSecondary)
        }
    }
}
