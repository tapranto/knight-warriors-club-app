package com.knightwarriorsclub.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.BackgroundStore
import com.knightwarriorsclub.app.data.ChatPrefs
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.Squad
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** "2026-10" → "Oct 2026" for the Member-since line. */
private fun memberSince(createdAt: String?): String? = createdAt?.let {
    runCatching {
        YearMonth.parse(it.take(7)).format(DateTimeFormatter.ofPattern("MMM yyyy"))
    }.getOrNull()
}

/**
 * A member's profile page (Pranto's Point 2, 2026-11-02): every member
 * has one. Public facts straight from the database — including the
 * SQUAD line on every profile (captain of / member of / no squad).
 * Game UIDs and team strengths show only on your OWN profile.
 */
@Composable
fun MemberProfileScreen(
    session: Session, me: Member, memberId: Long,
    onBack: () -> Unit,
    onMessage: (Member) -> Unit = {},
    onCall: (Member, String) -> Unit = { _, _ -> }
) {
    var member by remember { mutableStateOf<Member?>(null) }
    var games by remember { mutableStateOf<List<Triple<String, String?, Int?>>>(emptyList()) }
    var squads by remember { mutableStateOf<List<Pair<Squad, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(memberId) {
        member = ClubApi.memberById(session, memberId)
        games = ClubApi.memberGames(session, memberId)
        squads = ClubApi.squadsOf(session, memberId)
        loaded = true
    }

    val m = member
    val own = memberId == me.id
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text("Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        if (m == null) {
            Text("This profile could not be loaded.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialAvatar(m.fullName, 64)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                m.username?.let { "@$it" } ?: "Knight Warriors member",
                                color = Color(0xFFC4CEDC), fontSize = 12.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (m.role == "President") Color(0xFFD4A017)
                                        else Color.White.copy(alpha = 0.18f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    m.role.uppercase(),
                                    color = if (m.role == "President") Ink else Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (!m.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(m.bio, color = Color(0xFFF2F5F9), fontSize = 13.sp)
                    }
                    memberSince(m.createdAt)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Member since $it", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                    }
                    if (!m.favouriteGame.isNullOrBlank()) {
                        Text("Favourite game: ${m.favouriteGame}", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                    }
                }
            }
            if (!own) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Gold)
                                .clickable { onMessage(m) }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("MESSAGE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .border(1.dp, GlassRim, CircleShape)
                                .clickable { onCall(m, "voice") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = "Voice call", tint = GoldLight, modifier = Modifier.size(20.dp))
                        }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .border(1.dp, GlassRim, CircleShape)
                                .clickable { onCall(m, "video") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Videocam, contentDescription = "Video call", tint = GoldLight, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            item {
                Text("SQUAD", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    if (squads.isEmpty()) {
                        Text("No squad", color = Color(0xFFC4CEDC), fontSize = 13.sp)
                    } else {
                        squads.forEach { (sq, _) ->
                            val isCaptain = sq.captainId == m.id
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InitialAvatar(sq.name, 36)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(sq.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        if (isCaptain) "Captain" else "Member",
                                        color = if (isCaptain) GoldLight else Color(0xFFC4CEDC),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (sq != squads.last().first) Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            item {
                Text("GAMES", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    if (games.isEmpty()) {
                        Text("No games added yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
                    } else {
                        games.forEach { (game, uid, strength) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(game, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (own) {
                                    Text(
                                        listOfNotNull(
                                            uid?.let { "UID $it" },
                                            strength?.let { "Strength $it" }
                                        ).joinToString(" • "),
                                        color = Color(0xFFC4CEDC), fontSize = 11.sp
                                    )
                                }
                            }
                            if (game != games.last().first) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * Chat-details page (Pranto's Point 5, approved spec 2026-11-02):
 * opened by tapping the member's name in a chat header. Person block +
 * quick actions, per-chat customisation (background for THIS chat,
 * nickname, bubble colour — all phone-local), mute, block, delete.
 */
@Composable
fun ChatDetailsScreen(
    session: Session, me: Member, partner: Member,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onCall: (Member, String) -> Unit = { _, _ -> },
    onHidden: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(ChatPrefs.nickname(context, partner.id) ?: "") }
    var nickDialog by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(ChatPrefs.isMuted(context, partner.id)) }
    var bubbleIdx by remember { mutableStateOf(ChatPrefs.bubbleColour(context, partner.id)) }
    var hasBg by remember { mutableStateOf(BackgroundStore.hasChatCustom(context, partner.id)) }
    var blockedByMe by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(partner.id) {
        blockedByMe = ClubApi.myBlockedIds(session, me.id).contains(partner.id)
    }

    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (BackgroundStore.saveChatFromUri(context, uri, partner.id)) {
                    hasBg = true
                    note = "Background set for this chat."
                }
            }
        }
    }

    if (nickDialog) {
        var draft by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { nickDialog = false },
            title = { Text("Set nickname") },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text(partner.fullName) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ChatPrefs.setNickname(context, partner.id, draft)
                    nickname = draft.trim()
                    nickDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { nickDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(if (blockedByMe) "Unblock ${partner.fullName}?" else "Block ${partner.fullName}?") },
            text = {
                Text(
                    if (blockedByMe) "They will be able to message and call you again."
                    else "They will no longer be able to message or call you."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    scope.launch {
                        val res = if (blockedByMe) ClubApi.unblockMember(session, me.id, partner.id)
                        else ClubApi.blockMember(session, me.id, partner.id)
                        if (res.isSuccess) {
                            blockedByMe = !blockedByMe
                            note = if (blockedByMe) "Blocked." else "Unblocked."
                        } else {
                            note = "That didn't work — check your connection and try again."
                        }
                    }
                }) { Text(if (blockedByMe) "Unblock" else "Block") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlock = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("It will leave your inbox. If they message you again, it comes back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    ChatPrefs.hideChat(context, partner.id)
                    onHidden()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text("Chat details", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        InitialAvatar(partner.fullName, 72)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            ChatPrefs.nickname(context, partner.id) ?: partner.fullName,
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp
                        )
                        Text(
                            partner.username?.let { "@$it" } ?: "Knight Warriors member",
                            color = Color(0xFFC4CEDC), fontSize = 12.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Gold)
                                    .clickable { onOpenProfile() }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("PROFILE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Box(
                                Modifier.size(42.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .border(1.dp, GlassRim, CircleShape)
                                    .clickable { onCall(partner, "voice") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = "Voice call", tint = GoldLight, modifier = Modifier.size(20.dp))
                            }
                            Box(
                                Modifier.size(42.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .border(1.dp, GlassRim, CircleShape)
                                    .clickable { onCall(partner, "video") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Videocam, contentDescription = "Video call", tint = GoldLight, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text("CUSTOMISE THIS CHAT", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (hasBg) "Chat background: your picture" else "Chat background: app default",
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Text("Only inside this conversation, only on your phone.", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Gold)
                                .clickable {
                                    bgPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("CHANGE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        if (hasBg) {
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White, RoundedCornerShape(12.dp))
                                    .clickable {
                                        BackgroundStore.clearChat(context, partner.id)
                                        hasBg = false
                                        note = "Chat background removed."
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("RESET", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { nickDialog = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Nickname", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                nickname.ifBlank { "Not set — tap to add one" },
                                color = Color(0xFFC4CEDC), fontSize = 11.sp
                            )
                        }
                        Text("EDIT", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Bubble colour", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            Color(0xFFD4A017), Color(0xFF4A90D9),
                            Color(0xFF40C060), Color(0xFF9060D0)
                        ).forEachIndexed { idx, colour ->
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(colour)
                                    .border(
                                        if (bubbleIdx == idx) 2.5.dp else 1.dp,
                                        Color.White, CircleShape
                                    )
                                    .clickable {
                                        ChatPrefs.setBubbleColour(context, partner.id, idx)
                                        bubbleIdx = idx
                                    }
                            )
                        }
                    }
                }
            }
            item {
                Text("NOTIFICATIONS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Mute this conversation", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("No unread badge for this chat in your inbox.", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                        }
                        Switch(
                            checked = muted,
                            onCheckedChange = {
                                ChatPrefs.setMuted(context, partner.id, it)
                                muted = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Gold, checkedTrackColor = Color(0x66555555))
                        )
                    }
                }
            }
            item {
                Text("PRIVACY & SAFETY", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth().clickable { confirmBlock = true }) {
                    Text(
                        if (blockedByMe) "Unblock ${partner.fullName}" else "Block ${partner.fullName}",
                        color = Color(0xFFFF9B9B), fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Text(
                        if (blockedByMe) "Currently blocked — tap to unblock."
                        else "Stop their messages and calls.",
                        color = Color(0xFFC4CEDC), fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth().clickable { confirmDelete = true }) {
                    Text("Delete conversation", color = Color(0xFFFF9B9B), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Removes it from your inbox on this phone.", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                }
            }
            if (note != null) {
                item {
                    Text(note!!, color = Color(0xFFC4CEDC), fontSize = 12.sp)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
