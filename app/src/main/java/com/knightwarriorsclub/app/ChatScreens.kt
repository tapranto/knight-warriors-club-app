package com.knightwarriorsclub.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Dm
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.RealtimeClient
import com.knightwarriorsclub.app.data.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DhakaZone: ZoneId = ZoneId.of("Asia/Dhaka")
private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "2026-11-01T03:20:11.123+00:00" → "09:20" in Dhaka time. */
fun fmtTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(DhakaZone).format(TimeFmt)
}.getOrDefault("")

/** A direct_messages row arriving over Realtime, mapped like the REST rows. */
fun recordToDm(o: JsonObject): Dm? = runCatching {
    Dm(
        id = o["id"]!!.jsonPrimitive.long,
        senderId = o["sender_member_id"]!!.jsonPrimitive.long,
        recipientId = o["recipient_member_id"]!!.jsonPrimitive.long,
        body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
        createdAt = o["created_at"]!!.jsonPrimitive.content
    )
}.getOrNull()

@Composable
fun InitialAvatar(name: String, size: Int = 44) {
    val initials = name.split(" ").filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Color(0xB3141E30)).border(1.5.dp, Gold, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = (size / 3).sp)
    }
}

// ── Login ────────────────────────────────────────────────────────────────

@Composable
fun LoginScreen(
    busy: Boolean, error: String?,
    onSignIn: (String, String) -> Unit,
    onRegister: () -> Unit,
    onForgot: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            // the real club crest, big — Pranto's feedback, 2026-11-01
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.club_logo),
                contentDescription = "Knight Warriors Club logo",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(110.dp).clip(RoundedCornerShape(20.dp))
                    .border(1.5.dp, Gold, RoundedCornerShape(20.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "KNIGHT WARRIORS CLUB", color = GoldLight, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "Sign in with your club account — the same one as the website.",
                color = Color(0xFFC4CEDC), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            ClubTextField(
                "Email", email, { email = it },
                keyboardType = KeyboardType.Email, placeholder = "you@example.com"
            )
            Spacer(Modifier.height(10.dp))
            PasswordField("Password", password, { password = it })
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "Forgot password?", color = GoldLight, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .clickable { onForgot() }
                        .padding(vertical = 6.dp)
                )
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            GoldButton(
                if (busy) "SIGNING IN…" else "SIGN IN",
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { onSignIn(email.trim(), password) }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("New to the club? ", color = Color(0xFFC4CEDC), fontSize = 12.sp)
                Text(
                    "Register here", color = GoldLight, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onRegister() }
                )
            }
        }
    }
}

// ── Inbox ────────────────────────────────────────────────────────────────

@Composable
fun InboxScreen(
    session: Session, me: Member,
    realtime: RealtimeClient? = null,
    onOpenChat: (Member) -> Unit, onBack: () -> Unit
) {
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var unread by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var latest by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // live refresh: any new DM anywhere bumps the tick and reloads the list
    DisposableEffect(me.id) {
        val rt = realtime
        val channel = "app-inbox-${me.id}"
        rt?.joinInserts(channel, "direct_messages") { refreshTick++ }
        onDispose { rt?.leave(channel) }
    }

    LaunchedEffect(me.id, refreshTick) {
        runCatching {
            members = ClubApi.allMembers(session).filter { it.id != me.id }
            unread = ClubApi.unreadCounts(session, me.id)
            latest = ClubApi.recentPartners(session, me.id)
        }
        loaded = true
    }

    val withHistory = members.filter { latest.containsKey(it.id) }
        .sortedByDescending { latest[it.id] }
    val others = members.filter { !latest.containsKey(it.id) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Inbox", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Messages with club members", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(withHistory + others, key = { it.id }) { m ->
                    GlassCard(Modifier.fillMaxWidth().clickable { onOpenChat(m) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(m.fullName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    m.username?.let { "@$it" } ?: "Knight Warriors member",
                                    color = Color(0xFFC4CEDC), fontSize = 11.sp
                                )
                            }
                            val n = unread[m.id] ?: 0
                            if (n > 0) {
                                Box(
                                    Modifier.clip(CircleShape).background(Color(0xFFE23B3B))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                latest[m.id]?.let {
                                    Text(fmtTime(it), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Chat thread ──────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    session: Session, me: Member, partner: Member,
    realtime: RealtimeClient? = null,
    onCall: (Member, String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Dm>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var partnerTypingUntil by remember { mutableStateOf(0L) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastTypingSent by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()

    val insertsChannel = "app-chat-${me.id}-${partner.id}"
    val typingChannel = "kwc-chat-${minOf(me.id, partner.id)}-${maxOf(me.id, partner.id)}"

    suspend fun loadLatest() {
        val page = ClubApi.threadPage(session, me.id, partner.id)
        val known = messages.map { it.id }.toSet()
        val fresh = page.filter { it.id !in known }
        if (messages.isEmpty()) {
            messages = page
            hasMore = page.size >= 100
            if (page.isNotEmpty()) listState.scrollToItem(page.size - 1)
        } else if (fresh.isNotEmpty()) {
            val wasAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                    (messages.size - 1)
            messages = messages + fresh
            if (wasAtBottom) listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Phase 3: messages arrive live over Realtime; a slow poll is the net
    DisposableEffect(partner.id) {
        val rt = realtime
        rt?.joinInserts(insertsChannel, "direct_messages") { record ->
            val dm = recordToDm(record) ?: return@joinInserts
            val pairMatches = (dm.senderId == me.id && dm.recipientId == partner.id) ||
                    (dm.senderId == partner.id && dm.recipientId == me.id)
            if (!pairMatches) return@joinInserts
            if (messages.none { it.id == dm.id }) {
                val wasAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                        (messages.size - 1)
                messages = messages + dm
                if (wasAtBottom) {
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                }
            }
            if (dm.senderId == partner.id) {
                scope.launch { ClubApi.markRead(session, me.id, partner.id) }
            }
        }
        rt?.joinBroadcast(typingChannel, { event, payload ->
            if (event == "typing" &&
                payload["from"]?.jsonPrimitive?.longOrNull == partner.id
            ) {
                partnerTypingUntil = System.currentTimeMillis() + 4000
            }
        })
        onDispose {
            rt?.leave(insertsChannel)
            rt?.leave(typingChannel)
        }
    }

    LaunchedEffect(partner.id) {
        runCatching { loadLatest() }
        ClubApi.markRead(session, me.id, partner.id)
        while (true) {
            delay(30000)
            runCatching { loadLatest() }
            ClubApi.markRead(session, me.id, partner.id)
        }
    }

    // 1-second ticker for the typing indicator's expiry
    LaunchedEffect(partner.id) {
        while (true) {
            delay(1000)
            nowTick = System.currentTimeMillis()
        }
    }

    // tell the partner we're typing (throttled, app-to-app for now)
    LaunchedEffect(input) {
        if (input.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent > 3000) {
                lastTypingSent = now
                realtime?.broadcast(
                    typingChannel, "typing",
                    buildJsonObject { put("from", me.id) }
                )
            }
        }
    }

    // scroll-up loads older history (Pranto's in-app preference)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx == 0 && hasMore && !loadingOlder && messages.isNotEmpty()) {
                    loadingOlder = true
                    val older = runCatching {
                        ClubApi.threadPage(session, me.id, partner.id, beforeIso = messages.first().createdAt)
                    }.getOrDefault(emptyList())
                    if (older.isNotEmpty()) {
                        messages = older + messages
                        listState.scrollToItem(older.size)
                    }
                    hasMore = older.size >= 100
                    loadingOlder = false
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        // glass header strip
        Row(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.13f))
                .border(1.dp, GlassRim)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            InitialAvatar(partner.fullName, 38)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(partner.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    partner.username?.let { "@$it" } ?: "Knight Warriors member",
                    color = Color(0xFFC4CEDC), fontSize = 11.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.Call, contentDescription = "Voice call",
                tint = GoldLight,
                modifier = Modifier.size(24.dp).clickable { onCall(partner, "voice") }
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Filled.Videocam, contentDescription = "Video call",
                tint = GoldLight,
                modifier = Modifier.size(26.dp).clickable { onCall(partner, "video") }
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (loadingOlder) {
                item {
                    Text(
                        "Loading older messages…", color = Color(0xFFC4CEDC), fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                    )
                }
            }
            items(messages, key = { it.id }) { dm ->
                val mine = dm.senderId == me.id
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        Modifier.widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (mine) Color(0x40FFBE28) else Color.White.copy(alpha = 0.16f)
                            )
                            .border(1.dp, GlassRim, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(dm.body, color = Color.White, fontSize = 14.sp)
                        Text(
                            fmtTime(dm.createdAt), color = Color(0xFFC4CEDC), fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }

        if (error != null) {
            Text(
                error!!, color = Color(0xFFFF9B9B), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (partnerTypingUntil > nowTick) {
            Text(
                "${partner.fullName.split(" ").firstOrNull() ?: "Partner"} is typing…",
                color = Color(0xFFC4CEDC), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // floating glass input bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .border(1.dp, GlassRim, RoundedCornerShape(26.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message…", color = Color(0xFFC4CEDC)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = GoldLight
                )
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Gold)
                    .clickable(enabled = input.isNotBlank()) {
                        val text = input.trim()
                        if (text.isEmpty()) return@clickable
                        input = ""
                        error = null
                        scope.launch {
                            val res = ClubApi.sendDm(session, me.id, partner.id, text)
                            if (res.isSuccess) {
                                runCatching { loadLatest() }
                            } else {
                                error = "Couldn't send — check connection and retry."
                                input = text
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Ink, modifier = Modifier.size(20.dp))
            }
        }
    }
}
