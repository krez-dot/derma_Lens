package com.dermalens.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dermalens.app.R
import com.dermalens.app.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val backgroundColor: Color,
    val accentColor: Color
)

// Each page shows the owl; [icon] is the small badge on its corner.
val onboardingPages = listOf(
    OnboardingPage("A clearer look at your skin", "Scan 6 common skin concerns privately, right on your phone.", Icons.Default.CenterFocusWeak, Color(0xFF7C3AED), Color(0xFFEDE9FE)),
    OnboardingPage("Notice changes over time", "Save results, add notes, and keep a gentle record of your progress.", Icons.Default.TrendingUp, Color(0xFF7C3AED), Color(0xFFEDE9FE)),
    OnboardingPage("Know when to seek care", "Get clear next steps and quickly find dermatology clinics nearby.", Icons.Default.LocationOn, Color(0xFF7C3AED), Color(0xFFEDE9FE))
)

// ── Splash Screen ─────────────────────────────────────────────────────────────
@Composable
fun SplashScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val hasSeenOnboarding = remember { prefs.getBoolean(DermaPrefs.KEY_HAS_SEEN_ONBOARDING, false) }
    val isLoggedIn = remember { prefs.getBoolean(DermaPrefs.KEY_IS_LOGGED_IN, false) }

    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        launch { alpha.animateTo(1f, animationSpec = tween(800)) }
        delay(2000)
        when {
            isLoggedIn -> navController.navigate(Screen.Home.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
            hasSeenOnboarding -> navController.navigate(Screen.Login.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
            else -> navController.navigate(Screen.Onboarding.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(DermaGreen, DermaGreenDark))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.dermalens_logo),
                contentDescription = "DermaLens logo",
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp))
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("DermaLens", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Skin health in your hands", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(48.dp))
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        Text("Tarlac State University • Capstone 2026", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }
}

// ── Onboarding Screen ─────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(navController: NavController) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()

    fun finishOnboarding() {
        context.getSharedPreferences(DermaPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DermaPrefs.KEY_HAS_SEEN_ONBOARDING, true)
            .apply()
        navController.navigate(Screen.Login.route) { popUpTo(Screen.Onboarding.route) { inclusive = true } }
    }

    Box(modifier = Modifier.fillMaxSize().background(DermaPageBackground)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            OnboardingPageContent(page = onboardingPages[page])
        }

        TextButton(onClick = { finishOnboarding() }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            Text("Skip", color = DermaSubtle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(onboardingPages.size) { index ->
                    Box(
                        modifier = Modifier.animateContentSize().height(8.dp)
                            .width(if (pagerState.currentPage == index) 24.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == index) onboardingPages[pagerState.currentPage].backgroundColor else Color(0xFFD9D6E8))
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = {
                    if (pagerState.currentPage < onboardingPages.size - 1) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        finishOnboarding()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(56.dp).dermaGradientPill(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text(
                    if (pagerState.currentPage < onboardingPages.size - 1) "Continue" else "Get started",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = { finishOnboarding() }) {
                Text("I already have an account", color = DermaGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Onboarding Page Content ───────────────────────────────────────────────────
@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 170.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            // Owl on two soft halo rings, with this page's badge on its corner
            Box(
                modifier = Modifier.size(260.dp).clip(CircleShape).background(page.backgroundColor.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(196.dp).clip(CircleShape).background(page.backgroundColor.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(150.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.dermalens_logo),
                            contentDescription = "DermaLens logo",
                            modifier = Modifier
                                .size(132.dp)
                                .shadow(22.dp, RoundedCornerShape(36.dp), ambientColor = page.backgroundColor, spotColor = page.backgroundColor)
                                .clip(RoundedCornerShape(36.dp))
                        )
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .align(Alignment.BottomEnd)
                                .shadow(10.dp, CircleShape, ambientColor = page.backgroundColor, spotColor = page.backgroundColor)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = page.icon, contentDescription = null, tint = page.backgroundColor, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(44.dp))
            Text(text = page.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827), textAlign = TextAlign.Center, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = page.description, fontSize = 15.sp, color = DermaSubtle, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
    }
}