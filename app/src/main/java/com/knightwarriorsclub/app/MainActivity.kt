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
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.SessionStore
import kotlinx.coroutines.launch

/**
 * Knight Warriors Club app.
 * Phase 1: Liquid Glass screens standing (one artwork on every screen —
 * the Home lantern-forest, Pranto's rule 2026-11-01).
 * Phase 2: real Supabase sign-in + inbox + DM chat against the SAME
 * database as knightwarriorsclub.com.
 */

val Gold = Color(0xFFD4A017)
val GoldLight = Color(0xFFF2C33B)
val Ink = Color(0xFF0A101C)
val GlassFill = Color.White.copy(alpha = 0.13f)
val GlassRim = Color.White.copy(alpha = 0.38f)

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    News("News", Icons.Filled.Email),
    Events("Events", Icons.Filled.Flag),
    More("More", Icons.Filled.Menu)
}

private sealed interface Screen {
    data object Tabs : Screen
    data object Inbox : Screen
    data class Chat(val partner: Member) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SessionStore(this)
        setContent { ClubApp(store) }
    }
}

@Composable
fun ClubApp(store: SessionStore) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf<Session?>(null) }
    var me by remember { mutableStateOf<Member?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Tabs) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var unreadTotal by remember { mutableStateOf(0) }

    // restore a saved session on launch (sign in once per device)
    LaunchedEffect(Unit) {
        val rt = store.refreshToken()
        if (rt != null) {
            ClubApi.refresh(rt).onSuccess { s ->
                session = s
                store.save(s)
                me = runCatching { ClubApi.myMember(s) }.getOrNull()
            }.onFailure { store.clear() }
        }
        checking = false
    }

    // envelope badge = real unread DMs, refreshed when back on the tabs
    LaunchedEffect(session, me, screen) {
        val s = session; val m = me
        if (s != null && m != null && screen is Screen.Tabs) {
            unreadTotal = runCatching {
                ClubApi.unreadCounts(s, m.id).values.sum()
            }.getOrDefault(0)
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Image(
            painter = painterResource(R.drawable.bg_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color(0x59060B14))) // navy veil for contrast

        when {
            checking -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = Color.White, fontSize = 15.sp)
                }
            }
            session == null -> {
                LoginScreen(busy = loginBusy, error = loginError) { email, password ->
                    loginBusy = true
                    loginError = null
                    scope.launch {
                        ClubApi.signIn(email, password)
                            .onSuccess { s ->
                                session = s
                                store.save(s)
                                me = runCatching { ClubApi.myMember(s) }.getOrNull()
                                if (me == null) {
                                    loginError = "Signed in, but no member profile was found for this account."
                                    session = null
                                    store.clear()
                                }
                            }
                            .onFailure { loginError = it.message ?: "Sign-in failed" }
                        loginBusy = false
                    }
                }
            }
            else -> {
                val s = session!!
                val myProfile = me
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    when (val scr = screen) {
                        is Screen.Chat -> ChatScreen(
                            session = s, me = myProfile ?: return@Column,
                            partner = scr.partner,
                            onBack = { screen = Screen.Inbox }
                        )
                        is Screen.Inbox -> InboxScreen(
                            session = s, me = myProfile ?: return@Column,
                            onOpenChat = { screen = Screen.Chat(it) },
                            onBack = { screen = Screen.Tabs }
                        )
                        is Screen.Tabs -> {
                            ClubHeader(unreadTotal) { screen = Screen.Inbox }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    Tab.Home -> HomeScreen()
                                    Tab.News -> NewsScreen()
                                    Tab.Events -> EventsScreen()
                                    Tab.More -> MorePanel(
                                        me = myProfile,
                                        onLogout = {
                                            store.clear()
                                            session = null
                                            me = null
                                            screen = Screen.Tabs
                                            tab = Tab.Home
                                        }
                                    )
                                }
                            }
                            GlassTabBar(tab) { tab = it }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubHeader(unreadTotal: Int, onEnvelopeClick: () -> Unit) {
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
        Icon(
            Icons.Filled.Notifications, contentDescription = "Notifications",
            tint = Color.White, modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(14.dp))
        Box(Modifier.clickable { onEnvelopeClick() }) {
            Icon(
                Icons.Filled.Email, contentDescription = "Inbox",
                tint = Color.White, modifier = Modifier.size(26.dp)
            )
            if (unreadTotal > 0) {
                Box(
                    Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                        .clip(CircleShape).background(Color(0xFFE23B3B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (unreadTotal > 9) "9+" else "$unreadTotal",
                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
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
private fun MorePanel(me: Member?, onLogout: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle("More", "Everything else in the club") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (me != null) InitialAvatar(me.fullName, 46)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            me?.fullName ?: "Member",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                        )
                        Text(
                            me?.username?.let { "@$it" } ?: "Knight Warriors Club",
                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                        )
                    }
                }
            }
        }
        listOf(
            "Profile" to "Your member card & honours",
            "Leaderboard" to "Club rankings",
            "Tournaments" to "All club tournaments",
            "My Squads" to "Your teams",
            "Tournament Maker" to "Build your own tournament",
            "About the Club" to "Since 2023",
            "Settings" to "Sounds, vibration, app lock"
        ).forEach { (t, s) ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(s, color = Color(0xFFC4CEDC), fontSize = 11.sp)
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().clickable { onLogout() }) {
                Text("Log out", color = Color(0xFFFF9B9B), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Sign out of this device", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
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
