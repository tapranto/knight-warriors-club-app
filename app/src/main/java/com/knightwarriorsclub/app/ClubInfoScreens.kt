package com.knightwarriorsclub.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.knightwarriorsclub.app.data.AppPrefs
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun PageHeader(title: String, sub: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(26.dp).clickable { onBack() }
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(sub, color = Color(0xFFC4CEDC), fontSize = 11.sp)
        }
    }
}

// ── About the Club — static page, written into the app's code ────────────
// (Pranto, 2026-11-01: no database needed; this text ships inside the APK
// and works fully offline. Mirror of the website's About facts.)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHeader("About the Club", "Knight Warriors Club — Since 2023", onBack) }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(R.drawable.club_logo),
                    contentDescription = "Knight Warriors Club logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp))
                        .border(1.5.dp, Gold, RoundedCornerShape(18.dp))
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "KNIGHT WARRIORS CLUB", color = GoldLight, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    "EST. 2023 • ESPORTS CLUB", color = Color(0xFFC4CEDC), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "A brotherhood of passionate players — a sports & e-sports club built on " +
                        "tournaments, practice together, gaming knowledge, teamwork, skill " +
                        "development and pure gaming fun since 2023.",
                    color = Color(0xFFFFF8EE), fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Play together. Rise together.", color = GoldLight,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
            }
        }
        item {
            Text("WHAT WE DO", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                listOf(
                    "🏆  Regular tournaments & club events",
                    "🎮  Practice sessions, together",
                    "🎓  Coaching & gaming knowledge sharing",
                    "🤝  Teamwork, squads & skill development"
                ).forEach { line ->
                    Text(line, color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Members compete, learn and grow together — and every champion is " +
                        "remembered in the club's tournament history.",
                    color = Color(0xFFE8EDF4), fontSize = 12.sp
                )
            }
        }
        item {
            Text("OUR GAMES", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f)) { Text("● eFootball", color = Color.White, fontSize = 13.sp) }
                GlassCard(Modifier.weight(1f)) { Text("◎ PUBG", color = Color.White, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f)) { Text("♜ Clash of Clans", color = Color.White, fontSize = 13.sp) }
                GlassCard(Modifier.weight(1f)) { Text("✛ Call of Duty", color = Color.White, fontSize = 13.sp) }
            }
        }
        item {
            Text("LEADERSHIP", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "President: Tanvir Ahmed Pranto", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Text(
                    "Run with a 10-member committee — Vice President, Secretary, Treasurer, " +
                        "Sponsorship, Events, Public Relations, Discipline, Media & Promotion " +
                        "and Community.",
                    color = Color(0xFFE8EDF4), fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Contact: president@knightwarriorsclub.com",
                    color = GoldLight, fontSize = 12.sp
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ── Settings ─────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    hasCustomBg: Boolean,
    onPickBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHeader("Settings", "Make the app yours", onBack) }
        item { CallAlertsCard() }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("App background", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasCustomBg)
                        "You are using your own picture as the background."
                    else
                        "You are using the club's default background.",
                    color = Color(0xFFE8EDF4), fontSize = 12.sp
                )
                Text(
                    "Your picture stays only on this phone — it is never uploaded and " +
                        "nobody else sees it.",
                    color = Color(0xFFC4CEDC), fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Gold)
                        .clickable { onPickBackground() }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text("CHANGE BACKGROUND", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (hasCustomBg) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, Color.White, RoundedCornerShape(14.dp))
                            .clickable { onResetBackground() }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text("RESET TO CLUB DEFAULT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            // beta41: low-data mode toggle (AppPrefs, this phone only)
            val ctx = LocalContext.current
            var lowData by remember { mutableStateOf(AppPrefs.lowData(ctx)) }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Low-data mode", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Pictures load at quarter size — handy on slow or metered connections.",
                            color = Color(0xFFC4CEDC), fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = lowData,
                        onCheckedChange = {
                            lowData = it
                            AppPrefs.setLowData(ctx, it)
                        }
                    )
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Coming later", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sounds & vibration controls and the app lock (fingerprint / PIN) " +
                        "arrive in a later phase.",
                    color = Color(0xFFC4CEDC), fontSize = 12.sp
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}


/**
 * Phase 3.1 fix (2026-11-02): Android 14+ only lets a call take over the
 * whole screen with the member's explicit full-screen permission. The
 * push rang but nothing showed on Pranto's phone without it — this card
 * shows the current state and opens the system toggle.
 */
@Composable
fun CallAlertsCard() {
    val context = LocalContext.current
    val granted = if (android.os.Build.VERSION.SDK_INT >= 34) {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: false
    } else true
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Call alerts", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            if (granted)
                "Incoming calls can take over your screen when the app is closed. All set."
            else
                "Android is blocking incoming calls from taking over your screen, so a call " +
                    "rings in the background without showing who is calling. Allow it here.",
            color = Color(0xFFE8EDF4), fontSize = 12.sp
        )
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Gold)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                                ).setData(android.net.Uri.parse("package:" + context.packageName))
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text("ALLOW FULL-SCREEN ALERTS", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
