package com.dermalens.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.foundation.border
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import com.dermalens.app.ui.LocalAppSettings

/**
 * Whether High Contrast is in effect: the user's own toggle in Profile, or the phone's system
 * contrast setting (Android 14+). Backed by snapshot state, so every colour below that reads it
 * switches automatically and recomposes wherever it's used.
 */
object ContrastMode {
    var userSetting by mutableStateOf(false)
    var systemHigh by mutableStateOf(false)
    val highContrast: Boolean get() = userSetting || systemHigh
}

// Colours with a deeper High Contrast variant, chosen to reach at least 7:1 against the white
// and tinted surfaces they sit on (WCAG AAA) -- the normal shades only meet the 4.5:1 minimum.
val DermaGreen: Color get() = if (ContrastMode.highContrast) Color(0xFF5B21B6) else Color(0xFF7C3AED)
val DermaGreenLight = Color(0xFFEDE9FE)
val DermaGreenDark: Color get() = if (ContrastMode.highContrast) Color(0xFF4C1D95) else Color(0xFF6D28D9)
/** Placeholders, chevrons and quiet icons. */
val DermaMuted: Color get() = if (ContrastMode.highContrast) Color(0xFF374151) else Color(0xFF9CA3AF)
/** Secondary text and icons. */
val DermaSubtle: Color get() = if (ContrastMode.highContrast) Color(0xFF374151) else Color(0xFF6B7280)
val DermaDanger: Color get() = if (ContrastMode.highContrast) Color(0xFF7F1D1D) else Color(0xFFDC2626)
val DermaSuccess: Color get() = if (ContrastMode.highContrast) Color(0xFF14532D) else Color(0xFF16A34A)
val DermaSuccessText: Color get() = if (ContrastMode.highContrast) Color(0xFF14532D) else Color(0xFF15803D)
/** Thin outline High Contrast adds around cards, pills and fields (4.8:1 against white). */
val HcBorder = Color(0xFF6B7280)
val DermaWarningText: Color get() = if (ContrastMode.highContrast) Color(0xFF78350F) else Color(0xFF92400E)


/**
 * Scales a clickable element down slightly while pressed. Used in place of Material's default
 * ripple/indication on icons where that ripple was disabled (see [DermaGlassTopBar] and the
 * bottom nav) -- those had to lose the default indication to work around a background-painting
 * bug in IconButton/NavigationBarItem, but that left the icons with zero tap feedback at all.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.82f): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressedScale else 1f, label = "pressScale")
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}

/**
 * Fades and slides content up into place when it first appears -- the shared "entrance" motion
 * used across every screen (Home's cards, Progress Tracker's timelines, clinic list items, etc.)
 * so content loading in reads as a deliberate reveal instead of just popping into existence.
 * [delayMillis] staggers a list of these so items cascade in one after another rather than all
 * appearing in lockstep.
 */
@Composable
fun EntranceAnimation(delayMillis: Int = 0, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 5 }
    ) {
        content()
    }
}

/**
 * Shared "not a diagnosis" banner shown at the top of every main screen that displays
 * health-related content. Deliberately bold/high-contrast (not a muted footnote) so it's one
 * of the first things a user notices, not something that blends into the rest of the page.
 */
@Composable
fun DiagnosticAidDisclaimer(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFFFFF8EB))
            .border(1.dp, if (settings.highContrast) DermaWarningText else Color(0xFFFCE3B4), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "DermaLens is a diagnostic aid only. Always consult a dermatologist for professional advice.",
            fontSize = settings.textBase.sp,
            fontWeight = FontWeight.SemiBold,
            color = DermaWarningText,
            lineHeight = (settings.textBase * 1.4f).sp
        )
    }
}

/**
 * Shared top bar matching the bottom nav's floating glass treatment: a translucent, softly
 * bordered pill instead of a flush, opaque bar. Falls back to a plain solid [TopAppBar] under
 * High Contrast, same rule as the bottom nav -- translucency is inherently low-contrast, so it
 * has no place when that setting is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DermaGlassTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    titleColor: Color = Color(0xFF111827),
    actions: @Composable RowScope.() -> Unit = {}
) {
    val settings = LocalAppSettings.current

    if (settings.highContrast) {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = settings.textXl.sp) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Go back") }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White, titleContentColor = titleColor)
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.78f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    val backInteractionSource = remember { MutableInteractionSource() }
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Go back",
                        tint = titleColor,
                        modifier = Modifier
                            .pressScale(backInteractionSource)
                            .padding(4.dp)
                            .clickable(
                                interactionSource = backInteractionSource,
                                indication = null,
                                onClick = onBack
                            )
                            .padding(12.dp)
                    )
                } else {
                    // Matches the back icon's total footprint (24dp icon + 12dp + 4dp padding on
                    // each side = 56dp) so the row's height -- and therefore the pill's height --
                    // stays identical whether or not a screen has a back button.
                    Spacer(modifier = Modifier.size(56.dp))
                }
                Text(title, fontWeight = FontWeight.Bold, fontSize = settings.textXl.sp, color = titleColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                actions()
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

object DermaPrefs {
    const val PREFS_NAME = "dermalens_prefs"
    const val KEY_REMEMBER_EMAIL = "remember_email"
    const val KEY_IS_LOGGED_IN = "is_logged_in"
    const val KEY_USER_EMAIL = "user_email"
    const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
    const val KEY_FONT_SIZE = "font_size"
    const val KEY_HIGH_CONTRAST = "high_contrast"
    const val KEY_CONTRIBUTE_DATA = "contribute_data"
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val KEY_HIDE_SCAN_CONDITIONS_INFO = "hide_scan_conditions_info"
    // Local mirror of the signed-in account's "Back Up Scan History" choice. The source of truth
    // is users/{uid}.syncEnabled in Firestore (so a second phone learns it at login); this copy
    // just lets save/delete/edit paths check it without a network read. Cleared on logout.
    const val KEY_SYNC_HISTORY = "sync_history"
}

/**
 * Set right before navigating a freshly-verified new account to Home, consumed once there to
 * show the Contribute to Research consent prompt. In-memory only (not persisted) since it only
 * needs to survive the single VerifyEmail-to-Home navigation within the same app process.
 */
object NewUserSignal {
    var pendingContributePrompt: Boolean = false
}
/** Light lavender page background used behind the card-based screens. */
val DermaPageBackground = Color(0xFFF7F6FC)

/** White circular icon button with a soft shadow, used for back/close in large-title headers. */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val settings = LocalAppSettings.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(46.dp)
            .pressScale(interaction)
            .shadow(8.dp, CircleShape, ambientColor = Color(0x334C1D95), spotColor = Color(0x334C1D95))
            .then(if (settings.highContrast) Modifier.border(1.dp, HcBorder, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = settings.textPrimary, modifier = Modifier.size(22.dp))
    }
}

/** Full-width pill button in the new look. Transparent [container] makes it a plain text button. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = DermaGreen,
    content: Color = Color.White,
    bordered: Boolean = false,
    borderColor: Color? = null,
    elevated: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 54.dp
) {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pressScale(interaction, pressedScale = 0.97f)
            .then(
                if (!elevated) Modifier
                // A white button can't cast a white glow, so it gets a soft purple-grey shadow instead.
                else if (container == Color.White) Modifier.shadow(8.dp, shape, ambientColor = Color(0x334C1D95), spotColor = Color(0x334C1D95))
                else Modifier.shadow(10.dp, shape, ambientColor = container, spotColor = container)
            )
            .clip(shape)
            .background(container)
            .then(
                if (settings.highContrast && (bordered || borderColor != null || container == Color.Transparent || container == Color.White)) Modifier.border(1.dp, HcBorder, shape)
                else if (borderColor != null) Modifier.border(1.5.dp, borderColor, shape)
                else if (bordered) Modifier.border(1.5.dp, Color(0xFFE5E7EB), shape)
                else Modifier
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(color = content, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, fontSize = settings.textLg.sp, fontWeight = FontWeight.SemiBold, color = content)
    }
}

/**
 * The app's popup: a white card with large rounded corners, the icon in a soft round tile, a
 * centred title, and full-width pill buttons side by side. Drop-in for Material's AlertDialog
 * (same parameter names), so every dialog in the app shares one look. [shape] and
 * [containerColor] are accepted for call-site compatibility; the card always uses the app style.
 */
@Composable
fun DermaAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
    @Suppress("UNUSED_PARAMETER") containerColor: Color? = null,
    titleContentColor: Color = Color(0xFF111827),
    textContentColor: Color = Color(0xFF374151)
) {
    val settings = LocalAppSettings.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Purple accents for any default-coloured TextButton/Button inside.
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(primary = DermaGreen, onPrimary = Color.White),
            typography = androidx.compose.material3.MaterialTheme.typography
        ) {
            val cardShape = RoundedCornerShape(32.dp)
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .clip(cardShape)
                    .background(Color.White)
                    .then(if (settings.highContrast) Modifier.border(1.dp, HcBorder, cardShape) else Modifier)
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFF5F3FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides DermaGreen
                            ) { icon() }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (title != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.ProvideTextStyle(
                            androidx.compose.ui.text.TextStyle(
                                color = titleContentColor,
                                fontSize = settings.textXxl.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontFamily = androidx.compose.material3.MaterialTheme.typography.titleLarge.fontFamily
                            )
                        ) { title() }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                if (text != null) {
                    // weight(fill = false) lets long content (the Privacy Policy) scroll inside the
                    // card instead of pushing the buttons off-screen.
                    Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        androidx.compose.material3.ProvideTextStyle(
                            androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                                color = textContentColor,
                                fontSize = settings.textMd.sp,
                                lineHeight = (settings.textMd * 1.5f).sp
                            )
                        ) { text() }
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    // propagateMinConstraints stretches each button to fill its half.
                    if (dismissButton != null) {
                        Box(modifier = Modifier.weight(1f), propagateMinConstraints = true) {
                            // Secondary actions read in grey, not the accent purple.
                            androidx.compose.material3.MaterialTheme(
                                colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(primary = Color(0xFF4B5563))
                            ) { dismissButton() }
                        }
                    }
                    Box(modifier = Modifier.weight(1f), propagateMinConstraints = true) { confirmButton() }
                }
            }
        }
    }
}

/** Purple gradient pill background with a soft glow, for the app's main call-to-action buttons. */
@Composable
fun Modifier.dermaGradientPill(): Modifier {
    val settings = LocalAppSettings.current
    val shape = RoundedCornerShape(50)
    return this
        .shadow(12.dp, shape, ambientColor = DermaGreen, spotColor = DermaGreen)
        .clip(shape)
        .background(
            if (settings.highContrast) androidx.compose.ui.graphics.Brush.linearGradient(listOf(DermaGreen, DermaGreenDark))
            else androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF9061F9), DermaGreenDark))
        )
}
