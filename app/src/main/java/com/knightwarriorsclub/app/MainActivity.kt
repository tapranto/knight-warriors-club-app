package com.knightwarriorsclub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1 skeleton — the approved Liquid Glass design standing on real screens:
 * ONE artwork on every screen (the Home lantern-forest — Pranto's rule,
 * 2026-11-01), glass panels, floating glass tab bar,
 * header with bell LEFT and envelope RIGHT (Pranto's order, 2026-11-01).
 * Data wiring (Supabase auth/chat) arrives in Phase 2+.
 */

private val Gold = Color(0xFFD4A017)
private val GoldLight = Color(0xFFF2C33B)
private val Ink = Color(0xFF0A101C)
private val GlassFill = Color.White.copy(alpha = 0.13f)
private val GlassRim = Color.White.copy(alpha = 0.38f)

enum class Tab(val label: String, val icon: ImageVector, val bg: Int) {
    Home("Home", Icons.Filled.Home, R.drawable.bg_home),
    News("News", Icons.Filled.Email, R.drawable.bg_home),
    Events("Events", Icons.Filled.Flag, R.drawable.bg_home),
    More("More", Icons.Filled.Menu, R.drawable.bg_home)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClubApp() }
    }
}

@Composable
fun ClubApp() {
    var tab by remember { mutableStateOf(Tab.Home) }
    Box(Modifier.fillMaxSize().background(Ink)) {
        Image(
            painter = painterResource(tab.bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color(0x59060B14))) // navy veil for contrast
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ClubHeader()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Home -> HomeScreen()
                    Tab.News -> NewsScreen()
                    Tab.Events -> EventsScreen()
                    Tab.More -> MorePanel()
                }
            }
            GlassTabBar(tab) { tab = it }
        }
    }
}

@Composable
private fun ClubHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(Color(0xB3141E30)).border(1.5.dp, Gold, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("♞", color = GoldLight, fontSize = 24.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("KNIGHT WARRIORS CLUB", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("EST. 2023 • ESPORTS CLUB", color = Color(0xFFC4CEDC), fontSize = 9.sp)
        }
        // bell LEFT, envelope RIGHT — Pranto's header order
        HeaderIcon(Icons.Filled.Notifications, "3")
        Spacer(Modifier.width(14.dp))
        HeaderIcon(Icons.Filled.Email, "5")
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, badge: String) {
    Box {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Box(
            Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                .clip(CircleShape).background(Color(0xFFE23B3B)),
            contentAlignment = Alignment.Center
        ) { Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    fill: Color = GlassFill,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.shadow(10.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .border(1.dp, GlassRim, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun HomeScreen() {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("KNIGHT WARRIORS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                Text("CLUB", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                Spacer(Modifier.height(6.dp))
                Text("Play together. Rise together.", color = Color(0xFFFFF4E4), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Row {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Gold)
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("JOIN THE CLUB", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).border(1.5.dp, Color.White, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("TOURNAMENTS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }
        }
        item {
            Text("ABOUT THE CLUB", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "A brotherhood of passionate players — tournaments, practice, coaching and pure gaming fun since 2023.",
                    color = Color(0xFFFFF8EE), fontSize = 13.sp
                )
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth(), fill = Color(0x47FFBE28)) {
                Text("♛  eFootball Tournament Legacy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Knockout • Registration is open", color = Color(0xFFFFF0C8), fontSize = 12.sp)
            }
        }
        item {
            Text("LATEST NEWS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Tournament Maker is live!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Create your own tournaments in the club app.", color = Color(0xFFE8EDF4), fontSize = 12.sp)
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
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NewsScreen() {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle("News", "Stories from the club") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("FEATURED", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("Tournament Maker is live!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Create your own tournaments — league, knockout or groups.", color = Color(0xFFF2F5F9), fontSize = 12.sp)
            }
        }
        listOf(
            "Legacy registration is open — join now" to "31 Oct 2026",
            "Calls are live: voice & video in your DMs" to "31 Oct 2026",
            "Messages now auto-clean after 7 days" to "31 Oct 2026",
            "Welcome our newest members" to "27 Oct 2026"
        ).forEach { (t, d) ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(d, color = Color(0xFFC4CEDC), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EventsScreen() {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle("Events", "Tournaments & club events") }
        item {
            GlassCard(Modifier.fillMaxWidth(), fill = Color(0x40FFBE28)) {
                Text("● REGISTRATION OPEN", color = Color(0xFFBEFFD2), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("eFootball Tournament Legacy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("eFootball • Single elimination • 6 players joined", color = Color(0xFFFFF0C8), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Gold)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) { Text("JOIN NOW", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
        item {
            Text("TOURNAMENT HISTORY", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("♛ eFootball Demo Cup", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Champion: Bijoy Rahman", color = GoldLight, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MorePanel() {
    // Phase 1 placeholder for the right-side More drawer content.
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle("More", "Everything else in the club") }
        listOf(
            "Tanvir Ahmed Pranto — President" to "View profile",
            "Profile" to "Your member card & honours",
            "Leaderboard" to "Club rankings",
            "Tournaments" to "All club tournaments",
            "My Squads" to "Your teams",
            "Tournament Maker" to "Build your own tournament",
            "About the Club" to "Since 2023",
            "Settings" to "Sounds, vibration, app lock",
            "Log out" to ""
        ).forEach { (t, s) ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (s.isNotEmpty()) Text(s, color = Color(0xFFC4CEDC), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, sub: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(sub, color = Color(0xFFC4CEDC), fontSize = 11.sp)
    }
}

@Composable
private fun GlassTabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
            .shadow(12.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .border(1.dp, GlassRim, RoundedCornerShape(32.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.values().forEach { t ->
            val active = t == current
            Column(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (active) Color.White.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(t.icon, contentDescription = t.label, tint = if (active) GoldLight else Color(0xFFD6DEE9), modifier = Modifier.size(21.dp))
                Text(t.label, color = if (active) GoldLight else Color(0xFFD6DEE9), fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
