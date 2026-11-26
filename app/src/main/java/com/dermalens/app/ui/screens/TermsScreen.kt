package com.dermalens.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dermalens.app.R
import com.dermalens.app.navigation.Screen
import com.dermalens.app.ui.LocalAppSettings
import kotlinx.coroutines.flow.first

/**
 * Terms and Conditions (panel revision). Shown directly on the Login page before anything else,
 * read to the end, then agreed to. It also carries the research-contribution clause that replaced
 * the old on/off toggle, so agreeing here is what allows a saved scan's photo to be uploaded.
 *
 * Acceptance is stored per phone with the version number. Raising [VERSION] after changing the
 * text makes everyone see and accept the new version on their next launch.
 */
object TermsAgreement {
    const val VERSION = 1
    const val EFFECTIVE_DATE = "November 2026"

    fun isAccepted(context: android.content.Context): Boolean =
        prefs(context).getInt(DermaPrefs.KEY_TERMS_ACCEPTED_VERSION, 0) >= VERSION

    fun accept(context: android.content.Context) {
        prefs(context).edit()
            .putInt(DermaPrefs.KEY_TERMS_ACCEPTED_VERSION, VERSION)
            .putLong(DermaPrefs.KEY_TERMS_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
        // Contribution is part of what was just agreed to, so the upload job starts now.
        com.dermalens.app.worker.ContributionUploadScheduler.scheduleUpload(context)
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
}

/** Title to body. Kept in one place so the Login gate, the gate screen and the Profile dialog
 *  can never show different wording. */
val termsSections: List<Pair<String, String>> = listOf(
    "1. About DermaLens" to
        "DermaLens is a capstone project of Tarlac State University. It uses an on-device AI model to suggest which of six skin conditions (Acne Vulgaris, Eczema, Melasma, Tinea, Warts, Scabies) a photo may show. By creating an account or signing in, you agree to these Terms and Conditions.",
    "2. Not a medical diagnosis" to
        "DermaLens is a guide, not a doctor. Its results can be wrong and do not replace a consultation. Always see a dermatologist or doctor for a diagnosis and treatment, and get help right away if a condition is painful, spreading quickly, bleeding, or making you feel unwell.",
    "3. Your account" to
        "You need an account to use the app. Keep your password private. You may delete your account at any time in Profile, which removes your profile, your scan history on this phone, and any cloud backup of it.",
    "4. What stays on your phone" to
        "Your scans, their photos, your notes, and your answers to the questions asked before each scan are stored on this phone. They are not sent anywhere unless you turn on Back Up Scan History (section 6) or the research contribution below applies.",
    "5. Research contribution" to
        "To keep improving detection for Filipino skin, DermaLens uses saved scans to train future versions of its AI model. By agreeing to these Terms, you allow the following:\n\n" +
            "• What is sent: the photo of each scan you save to Self Monitoring, and the condition the app detected. Scans with no condition found are never sent.\n" +
            "• What is not sent: your name, email, account, location, notes, your answers to the pre-scan questions, or any device identifier. Each photo is given a random file name.\n" +
            "• When: in the background, only on Wi-Fi, after you save the scan.\n" +
            "• Where and who: a private Google Drive folder owned by the DermaLens research team. Only the team and its advisers can open it, and only to train and test the model.\n" +
            "• A photo can still show identifying features, such as your face or a tattoo. Frame only the affected skin if you prefer not to share them.\n" +
            "• To stop a photo from being sent, delete that scan before it uploads. Once uploaded, a photo carries nothing that links it to you, so the team cannot find a specific person's photo to remove it.",
    "6. Optional history backup" to
        "If you turn on Back Up Scan History in Profile, your scan records (condition, confidence, date, notes and pre-scan answers, but no photos) are stored in Google Firebase under your account so they can be restored on another phone. Turning it off deletes the cloud copy.",
    "7. Location" to
        "Your location is used only while you use the Clinic Locator, to find dermatology clinics near you. It is not stored.",
    "8. Your rights under the Data Privacy Act" to
        "Under the Data Privacy Act of 2012 (Republic Act No. 10173), you have the right to be informed, to access and correct your data, to object, and to have your data erased. You can view, edit and delete your scans in Self Monitoring, and delete your account in Profile.",
    "9. Changes to these Terms" to
        "If these Terms change, the app will show the new version and ask you to agree again before you continue.",
    "10. Contact" to
        "DermaLens is developed by a student team at Tarlac State University. For questions or concerns about these Terms or your data, contact the development team through the university."
)

@Composable
private fun TermsBody() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        termsSections.forEach { (title, body) -> PrivacySection(title, body) }
    }
}

/**
 * The full agreement panel: header, the terms in a scrolling card, and an Agree button that only
 * unlocks once the user has scrolled to the end. Fills its parent.
 */
@Composable
fun TermsAgreementPanel(onAgree: () -> Unit, onDecline: () -> Unit) {
    val settings = LocalAppSettings.current
    val scroll = rememberScrollState()
    // Latched: scrolling back up after reaching the end doesn't lock the button again.
    var reachedEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        // maxValue is Int.MAX_VALUE until the content is measured; 0 means it all fits.
        snapshotFlow { scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue - 24 }
            .first { it }
        reachedEnd = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DermaPageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow("Before you sign in")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Terms and Conditions", fontSize = settings.textDisplay.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = settings.textPrimary, lineHeight = (settings.textDisplay * 1.15f).sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Please read to the end. Effective ${TermsAgreement.EFFECTIVE_DATE}.", fontSize = settings.textBase.sp, color = settings.textSecondary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Image(
                painter = painterResource(id = R.drawable.dermalens_logo),
                contentDescription = "DermaLens logo",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        SoftCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(18.dp)
            ) {
                TermsBody()
                Spacer(modifier = Modifier.height(16.dp))
                Text("End of Terms and Conditions", fontSize = settings.textSm.sp, color = DermaSubtle, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (!reachedEnd) {
            Row(modifier = Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = DermaSubtle, modifier = Modifier.size(18.dp))
                Text("Scroll to the end to continue", fontSize = settings.textSm.sp, color = DermaSubtle)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        PillButton(
            text = "I Agree",
            icon = Icons.Default.Check,
            container = if (reachedEnd) DermaGreen else Color(0xFFD1D5DB),
            elevated = reachedEnd,
            enabled = reachedEnd,
            onClick = onAgree
        )
        TextButton(onClick = onDecline, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("I don't agree", fontSize = settings.textMd.sp, color = settings.textSecondary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Gate for someone already signed in who hasn't accepted the current version. */
@Composable
fun TermsScreen(navController: NavController) {
    val context = LocalContext.current
    var showDeclined by remember { mutableStateOf(false) }
    TermsAgreementPanel(
        onAgree = {
            TermsAgreement.accept(context)
            navController.navigate(Screen.Home.route) { popUpTo(Screen.Terms.route) { inclusive = true } }
        },
        onDecline = { showDeclined = true }
    )
    if (showDeclined) TermsDeclinedDialog(onDismiss = { showDeclined = false })
}

/** Declining doesn't sign anyone in; it explains why and offers to close the app. */
@Composable
fun TermsDeclinedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    DermaAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Gavel, contentDescription = null, tint = DermaGreen) },
        title = { Text("Agreement needed", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = { Text("DermaLens can only be used after agreeing to the Terms and Conditions. You can read them again, or close the app.") },
        confirmButton = { Button(onClick = onDismiss) { Text("Read again") } },
        dismissButton = {
            TextButton(onClick = { (context as? android.app.Activity)?.finishAffinity() }) { Text("Close app") }
        }
    )
}

/** Read-only copy for Profile and Register ("Terms and Conditions" link). */
@Composable
fun TermsDialog(onDismiss: () -> Unit) {
    val settings = LocalAppSettings.current
    DermaAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Gavel, contentDescription = null, tint = DermaGreen) },
        title = { Text("Terms and Conditions", fontWeight = FontWeight.Bold, fontSize = settings.textXl.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Effective ${TermsAgreement.EFFECTIVE_DATE}", fontSize = settings.textSm.sp, color = DermaSubtle)
                Spacer(modifier = Modifier.height(10.dp))
                TermsBody()
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close", fontWeight = FontWeight.SemiBold) } }
    )
}
