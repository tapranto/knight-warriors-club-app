package com.knightwarriorsclub.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.BackgroundStore
import com.knightwarriorsclub.app.data.Broadcast
import com.knightwarriorsclub.app.data.ChatPrefs
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Dm
import com.knightwarriorsclub.app.data.InboxCache
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.RealtimeClient
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.Squad
import com.knightwarriorsclub.app.data.SquadMsg
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
        createdAt = o["created_at"]!!.jsonPrimitive.content,
        isRead = o["is_read"]?.jsonPrimitive?.contentOrNull == "true",
        deliveredAt = o["delivered_at"]?.jsonPrimitive?.contentOrNull
    )
}.getOrNull()

/** One action row in the club-styled popup menus (icon + label). */
@Composable
fun MenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** One row of the merged inbox list: a DM chat or a squad chat (v0.4.0). */
private sealed interface InboxRow {
    val timeIso: String

    data class DmRow(val member: Member, override val timeIso: String) : InboxRow
    data class SquadRow(val squad: Squad, val snippet: String?, override val timeIso: String) : InboxRow
}

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    session: Session, me: Member,
    realtime: RealtimeClient? = null,
    onOpenChat: (Member) -> Unit,
    onOpenEveryone: () -> Unit = {},
    onOpenSquad: (Squad) -> Unit = {},
    onOpenProfile: (Member) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var unread by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var latest by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var mySquads by remember { mutableStateOf<List<Squad>>(emptyList()) }
    var squadLatest by remember { mutableStateOf<Map<Long, SquadMsg?>>(emptyMap()) }
    var blockedSet by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var menuPartner by remember { mutableStateOf<Member?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }

    // beta37 (Pranto): Messenger-style — paint the last-known inbox from
    // the phone cache instantly, then let the fetch below refresh it.
    LaunchedEffect(me.id) {
        if (members.isEmpty()) {
            InboxCache.loadInbox(context, me.id)?.let { snap ->
                members = snap.members
                latest = snap.latest
                unread = snap.unread
                blockedSet = snap.blocked
                mySquads = snap.squads
                squadLatest = snap.squadLatest.mapValues { (sid, v) ->
                    if (v.second.isBlank()) null
                    else SquadMsg(0, sid, 0, v.first ?: "", v.second)
                }
                loaded = true
            }
        }
    }

    // live refresh: any new DM anywhere bumps the tick and reloads the list
    DisposableEffect(me.id) {
        val rt = realtime
        val channel = "app-inbox-${me.id}"
        rt?.joinInserts(channel, "direct_messages") { refreshTick++ }
        onDispose { rt?.leave(channel) }
    }

    LaunchedEffect(me.id, refreshTick) {
        // the inbox processing a message = it reached this phone
        ClubApi.markDelivered(session, me.id)
        runCatching {
            members = ClubApi.allMembers(session).filter { it.id != me.id }
            unread = ClubApi.unreadCounts(session, me.id)
            latest = ClubApi.recentPartners(session, me.id)
            blockedSet = ClubApi.myBlockedIds(session, me.id) +
                    ClubApi.blockedByIds(session, me.id)
            val squads = ClubApi.squadsOf(session, me.id).map { it.first }
            mySquads = squads
            val map = mutableMapOf<Long, SquadMsg?>()
            squads.forEach { sq -> map[sq.id] = ClubApi.latestSquadMessage(session, sq.id) }
            squadLatest = map
            InboxCache.saveInbox(
                context, me.id, members, latest, unread, blockedSet, mySquads, squadLatest
            )
        }
        loaded = true
    }

    // Messenger rule (Pranto, 2026-11-01): the inbox lists ONLY members
    // you actually have a conversation with — everyone else is reached
    // through the ✎ new-chat picker. v0.4.0: blocked members drop out,
    // locally-deleted chats stay out until a newer message arrives, and
    // squad chats merge into the same list (Pranto, 2026-11-02).
    fun isHiddenNow(m: Member): Boolean {
        val hiddenAt = ChatPrefs.hiddenAt(context, m.id)
        if (hiddenAt == 0L) return false
        val latestMs = latest[m.id]?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        } ?: return true
        return latestMs <= hiddenAt
    }
    val withHistory = members.filter {
        latest.containsKey(it.id) && it.id !in blockedSet && !isHiddenNow(it)
    }
    val mergedRows: List<InboxRow> = (
            withHistory.map { InboxRow.DmRow(it, latest[it.id] ?: "") } +
                    mySquads.map { sq ->
                        InboxRow.SquadRow(
                            sq,
                            squadLatest[sq.id]?.body,
                            squadLatest[sq.id]?.createdAt ?: sq.createdAt
                        )
                    }
            ).sortedByDescending { it.timeIso }

    if (picking) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable { picking = false }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("New chat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Choose a club member", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // search the member list (Pranto's Point 3, 2026-11-02)
            var pickQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                value = pickQuery, onValueChange = { pickQuery = it },
                placeholder = { Text("Search members…", color = Color(0xFFC4CEDC)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Gold, unfocusedBorderColor = GlassRim,
                    cursorColor = GoldLight
                )
            )
            Spacer(Modifier.height(12.dp))
            val pickList = members.filter {
                it.id !in blockedSet &&
                        (pickQuery.isBlank() || it.fullName.contains(pickQuery, ignoreCase = true))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pickList, key = { it.id }) { m ->
                    GlassCard(Modifier.fillMaxWidth().clickable {
                        picking = false
                        ChatPrefs.unhideChat(context, m.id)
                        onOpenChat(m)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarImage(url = m.avatarUrl, name = m.fullName, size = 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    m.username?.let { "@$it" } ?: "Knight Warriors member",
                                    color = Color(0xFFC4CEDC), fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
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
            // fixed for everyone: the Club Room, always pinned at the top
            GlassCard(
                Modifier.fillMaxWidth().clickable { onOpenEveryone() },
                fill = Color(0x47FFBE28)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Color(0xB3141E30)).border(1.5.dp, Gold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("ALL", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Everyone — Club Room", color = Color.White,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                        Text(
                            "Club broadcasts from the President",
                            color = Color(0xFFE8EDF4), fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!loaded) {
                Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            } else if (mergedRows.isEmpty()) {
                Text(
                    "No chats yet. Tap the gold ✎ button to start your first chat.",
                    color = Color(0xFFC4CEDC), fontSize = 13.sp
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(mergedRows, key = { row ->
                        when (row) {
                            is InboxRow.DmRow -> "dm-${row.member.id}"
                            is InboxRow.SquadRow -> "sq-${row.squad.id}"
                        }
                    }) { row ->
                        when (row) {
                            is InboxRow.DmRow -> {
                                val m = row.member
                                GlassCard(
                                    Modifier.fillMaxWidth().combinedClickable(
                                        onClick = {
                                            ChatPrefs.unhideChat(context, m.id)
                                            onOpenChat(m)
                                        },
                                        onLongClick = { menuPartner = m }
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AvatarImage(url = m.avatarUrl, name = m.fullName, size = 44)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                ChatPrefs.displayName(context, m),
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                            )
                                            Text(
                                                m.username?.let { "@$it" } ?: "Knight Warriors member",
                                                color = Color(0xFFC4CEDC), fontSize = 11.sp
                                            )
                                        }
                                        val n = if (ChatPrefs.isMuted(context, m.id)) 0
                                        else unread[m.id] ?: 0
                                        if (n > 0) {
                                            Box(
                                                Modifier.clip(CircleShape).background(Color(0xFFE23B3B))
                                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Text(fmtTime(row.timeIso), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            is InboxRow.SquadRow -> {
                                val sq = row.squad
                                GlassCard(Modifier.fillMaxWidth().clickable { onOpenSquad(sq) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        InitialAvatar(sq.name)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    sq.name, color = Color.White,
                                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                                )
                                                if (sq.captainId == me.id) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Box(
                                                        Modifier.clip(RoundedCornerShape(8.dp)).background(Gold)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("CAPTAIN", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Text(
                                                row.snippet ?: "Squad chat",
                                                color = Color(0xFFC4CEDC), fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                        Text(fmtTime(row.timeIso), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
        // long-press a conversation → options (Pranto's Point 4, 2026-11-02),
        // as a club-styled glass sheet, not the stock popup (2026-11-02)
        menuPartner?.let { p ->
            Dialog(onDismissRequest = { menuPartner = null }) {
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Color(0xF0141E30))
                        .border(1.dp, GlassRim, RoundedCornerShape(24.dp))
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarImage(url = p.avatarUrl, name = p.fullName, size = 48)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                ChatPrefs.displayName(context, p),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                            Text(
                                p.username?.let { "@$it" } ?: "Knight Warriors member",
                                color = Color(0xFFC4CEDC), fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    MenuActionRow(Icons.Filled.Person, "View profile", Color.White) {
                        menuPartner = null
                        onOpenProfile(p)
                    }
                    MenuActionRow(Icons.Filled.Delete, "Delete chat", Color(0xFFFF9B9B)) {
                        menuPartner = null
                        ChatPrefs.hideChat(context, p.id)
                        refreshTick++
                    }
                    MenuActionRow(Icons.Filled.Block, "Block member", Color(0xFFFF9B9B)) {
                        menuPartner = null
                        scope.launch {
                            ClubApi.blockMember(session, me.id, p.id)
                            ChatPrefs.hideChat(context, p.id)
                            refreshTick++
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Gold, RoundedCornerShape(14.dp))
                            .clickable { menuPartner = null }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("CANCEL", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
        // ✎ new chat — Messenger-style compose button
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp)
                .size(54.dp).clip(CircleShape).background(Gold)
                .clickable { picking = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "New chat", tint = Ink, modifier = Modifier.size(24.dp))
        }
    }
}

// ── Everyone — the fixed Club Room (club broadcasts) ─────────────────────

@Composable
fun EveryoneScreen(session: Session, onBack: () -> Unit) {
    var broadcasts by remember { mutableStateOf<List<Broadcast>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        broadcasts = ClubApi.broadcasts(session)
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Everyone", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Club broadcasts from the President", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
        } else if (broadcasts.isEmpty()) {
            Text("No broadcasts yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(broadcasts, key = { it.id }) { b ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        if (b.title.isNotBlank()) {
                            Text(b.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(b.body, color = Color(0xFFF2F5F9), fontSize = 13.sp)
                        if (b.createdAt.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${b.createdAt.take(10)} • ${fmtTime(b.createdAt)}",
                                color = Color(0xFFC4CEDC), fontSize = 10.sp
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Chat thread ──────────────────────────────────────────────────────────

// beta41: club stickers — sent as plain messages; a message that is
// exactly one sticker renders BIG with no bubble (app side).
val CLUB_STICKERS = listOf(
    "⚔️", "🛡️", "🔥", "🏆", "🎮", "👑",
    "⚡", "💪", "😤", "🐺", "🌙", "⭐"
)

@Composable
fun StickerPanel(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, GlassRim, RoundedCornerShape(18.dp))
            .padding(10.dp)
    ) {
        CLUB_STICKERS.chunked(6).forEach { rowStickers ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowStickers.forEach { st ->
                    Text(
                        st, fontSize = 30.sp,
                        modifier = Modifier.clickable { onPick(st) }.padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    session: Session, me: Member, partner: Member,
    realtime: RealtimeClient? = null,
    onCall: (Member, String) -> Unit = { _, _ -> },
    onOpenDetails: (Member) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var blockState by remember { mutableStateOf(false) }
    val ownBubbleColor = listOf(
        Color(0x40FFBE28), Color(0x404A90D9), Color(0x4040C060), Color(0x409060D0)
    )[ChatPrefs.bubbleColour(context, partner.id)]
    LaunchedEffect(partner.id) {
        val blocked = ClubApi.myBlockedIds(session, me.id) +
                ClubApi.blockedByIds(session, me.id)
        blockState = blocked.contains(partner.id)
    }
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Dm>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var showStickers by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var partnerTypingUntil by remember { mutableStateOf(0L) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastTypingSent by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()

    // beta37: open with the phone-cached thread instantly (Messenger-style);
    // loadLatest() below then merges anything newer from the server, and
    // every change is written back to the cache for the next open.
    LaunchedEffect(partner.id) {
        if (messages.isEmpty()) {
            val cached = InboxCache.loadThread(context, me.id, partner.id)
            if (cached.isNotEmpty()) {
                messages = cached
                runCatching { listState.scrollToItem(cached.size - 1) }
            }
        }
    }
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            InboxCache.saveThread(context, me.id, partner.id, messages)
        }
    }

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
                scope.launch {
                    ClubApi.markRead(session, me.id, partner.id)
                    ClubApi.markDelivered(session, me.id, partner.id)
                }
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
        ClubApi.markDelivered(session, me.id, partner.id)
        while (true) {
            delay(30000)
            runCatching { loadLatest() }
            ClubApi.markRead(session, me.id, partner.id)
            ClubApi.markDelivered(session, me.id, partner.id)
        }
    }

    // Tick states (delivered / read) for both sides of this thread
    LaunchedEffect(partner.id) {
        while (true) {
            delay(6000)
            val states = ClubApi.threadStates(session, me.id, partner.id)
            if (states.isNotEmpty()) {
                messages = messages.map { dm ->
                    val st = states[dm.id]
                    if (st == null) dm
                    else dm.copy(
                        isRead = st.first,
                        deliveredAt = st.second ?: dm.deliveredAt
                    )
                }
            }
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

    Box(Modifier.fillMaxSize()) {
        // per-chat background (chat-details page): this chat's own picture
        // wins over the app-wide background; drawn inside the chat only
        val chatBg = remember(partner.id) { BackgroundStore.loadChatBitmap(context, partner.id) }
        if (chatBg != null) {
            Image(
                bitmap = chatBg.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Color(0x59060B14)))
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
            // tapping the name/avatar opens the chat-details page (Point 5)
            Row(
                Modifier.clickable { onOpenDetails(partner) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarImage(url = partner.avatarUrl, name = partner.fullName, size = 38)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        ChatPrefs.displayName(context, partner),
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    Text(
                        partner.username?.let { "@$it" } ?: "Knight Warriors member",
                        color = Color(0xFFC4CEDC), fontSize = 11.sp
                    )
                }
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
                val isSticker = dm.body.trim() in CLUB_STICKERS
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        Modifier.widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSticker) Color.Transparent
                                else if (mine) ownBubbleColor
                                else Color.White.copy(alpha = 0.16f)
                            )
                            .then(
                                if (isSticker) Modifier
                                else Modifier.border(1.dp, GlassRim, RoundedCornerShape(18.dp))
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(dm.body, color = Color.White, fontSize = if (isSticker) 40.sp else 14.sp)
                        Row(
                            Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(fmtTime(dm.createdAt), color = Color(0xFFC4CEDC), fontSize = 9.sp)
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (dm.isRead || dm.deliveredAt != null) "✓✓" else "✓",
                                    color = if (dm.isRead) Color(0xFFFFC94D)
                                        else Color(0xFFC4CEDC),
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
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

        // floating glass input bar (replaced by a notice when blocked)
        if (blockState) {
            Text(
                "You can't reply to this conversation.",
                color = Color(0xFFC4CEDC), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            )
        } else {
        if (showStickers) {
            StickerPanel { sticker ->
                showStickers = false
                error = null
                scope.launch {
                    val res = ClubApi.sendDm(session, me.id, partner.id, sticker)
                    if (res.isSuccess) {
                        ClubApi.sendMessagePushDm(session, partner.id, sticker)
                        runCatching { loadLatest() }
                    } else {
                        error = "Couldn't send — check connection and retry."
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .border(1.dp, GlassRim, RoundedCornerShape(26.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.EmojiEmotions, contentDescription = "Stickers",
                tint = if (showStickers) GoldLight else Color(0xFFC4CEDC),
                modifier = Modifier.size(26.dp).clickable { showStickers = !showStickers }
            )
            Spacer(Modifier.width(4.dp))
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
                                ClubApi.sendMessagePushDm(session, partner.id, text)
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
    }
}
