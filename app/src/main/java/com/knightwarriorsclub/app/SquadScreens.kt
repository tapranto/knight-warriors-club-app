package com.knightwarriorsclub.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.RealtimeClient
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.Squad
import com.knightwarriorsclub.app.data.SquadMemberRow
import com.knightwarriorsclub.app.data.SquadMsg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A squad_messages row arriving over Realtime, mapped like the REST rows. */
fun recordToSquadMsg(o: JsonObject): SquadMsg? = runCatching {
    SquadMsg(
        id = o["id"]!!.jsonPrimitive.long,
        squadId = o["squad_id"]!!.jsonPrimitive.long,
        senderId = o["sender_member_id"]!!.jsonPrimitive.long,
        body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
        createdAt = o["created_at"]!!.jsonPrimitive.content
    )
}.getOrNull()

/**
 * Member picker WITH SEARCH (Pranto's Point 3 pattern, shared by squad
 * create + add-members): type to filter, tap rows to select.
 */
@Composable
fun MemberPicker(
    members: List<Member>,
    selected: Set<Long>,
    onToggle: (Member) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = members.filter {
        query.isBlank() || it.fullName.contains(query, ignoreCase = true)
    }
    Column {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search members…", color = Color(0xFFC4CEDC)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Gold, unfocusedBorderColor = GlassRim,
                cursorColor = GoldLight
            )
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { m ->
                val isSel = selected.contains(m.id)
                GlassCard(Modifier.fillMaxWidth().clickable { onToggle(m) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarImage(url = m.avatarUrl, name = m.fullName, size = 38)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                m.username?.let { "@$it" } ?: "Knight Warriors member",
                                color = Color(0xFFC4CEDC), fontSize = 11.sp
                            )
                        }
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .background(if (isSel) Gold else Color.Transparent)
                                .border(1.5.dp, if (isSel) Gold else GlassRim, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSel) {
                                Text("✓", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text("No members match that search.", color = Color(0xFFC4CEDC), fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Squad list (More → My Squads) ────────────────────────────────────────

@Composable
fun SquadsScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpenSquad: (Squad) -> Unit
) {
    var squads by remember { mutableStateOf<List<Squad>>(emptyList()) }
    var latest by remember { mutableStateOf<Map<Long, SquadMsg?>>(emptyMap()) }
    var owned by remember { mutableStateOf<Squad?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(me.id) {
        val mine = ClubApi.squadsOf(session, me.id).map { it.first }
        squads = mine
        owned = ClubApi.myOwnedSquad(session, me.id)
        val map = mutableMapOf<Long, SquadMsg?>()
        mine.forEach { sq -> map[sq.id] = ClubApi.latestSquadMessage(session, sq.id) }
        latest = map
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
                Text("My Squads", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Squad chats — open to every member", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loaded && squads.isEmpty()) {
            GoldButton("+ CREATE SQUAD", enabled = true) { onCreate() }
        } else if (owned != null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "You lead “${owned!!.name}” — one squad per Warrior. Disband it to create a different one.",
                    color = Color(0xFFC4CEDC), fontSize = 12.sp
                )
            }
        } else if (loaded) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "You're in a squad — one squad per Warrior. Leave it to create your own.",
                    color = Color(0xFFC4CEDC), fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
        } else if (squads.isEmpty()) {
            Text(
                "No squads yet. Create yours above, or wait for a captain to add you to theirs.",
                color = Color(0xFFC4CEDC), fontSize = 13.sp
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(squads, key = { it.id }) { sq ->
                    val last = latest[sq.id]
                    GlassCard(Modifier.fillMaxWidth().clickable { onOpenSquad(sq) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarImage(url = sq.avatarUrl, name = sq.name, size = 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sq.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                                    last?.body ?: "No messages yet",
                                    color = Color(0xFFC4CEDC), fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            last?.let {
                                Text(fmtTime(it.createdAt), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Create squad ─────────────────────────────────────────────────────────

@Composable
fun CreateSquadScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onCreated: (Squad) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(me.id) {
        members = ClubApi.allMembers(session).filter { it.id != me.id }
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
                Text("Create Squad", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("You become its Captain automatically", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        ClubTextField("Squad name", name, { name = it }, placeholder = "e.g. Night Strikers")
        Spacer(Modifier.height(6.dp))
        Text(
            "Add members (${selected.size} selected)",
            color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            MemberPicker(members, selected) { m ->
                selected = if (selected.contains(m.id)) selected - m.id else selected + m.id
            }
        }
        if (error != null) {
            Text(error!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        GoldButton(if (busy) "CREATING…" else "CREATE SQUAD", enabled = !busy && name.isNotBlank()) {
            busy = true
            error = null
            scope.launch {
                ClubApi.createSquad(session, me.id, name.trim(), selected.toList())
                    .onSuccess { onCreated(it) }
                    .onFailure { error = it.message ?: "Could not create the squad" }
                busy = false
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ── Squad chat thread ────────────────────────────────────────────────────

@Composable
fun SquadChatScreen(
    session: Session, me: Member, squad: Squad,
    realtime: RealtimeClient? = null,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<SquadMsg>>(emptyList()) }
    var namesById by remember { mutableStateOf<Map<Long, Member>>(emptyMap()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val channel = "app-squad-${squad.id}"

    suspend fun loadLatest() {
        val page = ClubApi.squadThreadPage(session, squad.id)
        if (messages.isEmpty()) {
            messages = page
            hasMore = page.size >= 100
            if (page.isNotEmpty()) listState.scrollToItem(page.size - 1)
        } else {
            val known = messages.map { it.id }.toSet()
            val fresh = page.filter { it.id !in known }
            if (fresh.isNotEmpty()) {
                val wasAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                        (messages.size - 1)
                messages = messages + fresh
                if (wasAtBottom) listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    DisposableEffect(squad.id) {
        val rt = realtime
        rt?.joinInserts(channel, "squad_messages") { record ->
            val msg = recordToSquadMsg(record) ?: return@joinInserts
            if (msg.squadId != squad.id) return@joinInserts
            if (messages.none { it.id == msg.id }) {
                messages = messages + msg
                scope.launch { listState.animateScrollToItem(messages.size - 1) }
            }
        }
        onDispose { rt?.leave(channel) }
    }

    LaunchedEffect(squad.id) {
        namesById = ClubApi.allMembers(session).associateBy { it.id } + (me.id to me)
        runCatching { loadLatest() }
        while (true) {
            delay(15000)
            runCatching { loadLatest() }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlowCompat(listState).collect { idx ->
            if (idx == 0 && hasMore && !loadingOlder && messages.isNotEmpty()) {
                loadingOlder = true
                val older = runCatching {
                    ClubApi.squadThreadPage(session, squad.id, beforeIso = messages.first().createdAt)
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
            AvatarImage(url = squad.avatarUrl, name = squad.name, size = 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.clickable { onOpenDetails() }) {
                Text(squad.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Squad chat — tap for members & settings", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
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
            items(messages, key = { it.id }) { msg ->
                val mine = msg.senderId == me.id
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
                        if (!mine) {
                            Text(
                                namesById[msg.senderId]?.fullName ?: "Member",
                                color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 10.sp
                            )
                        }
                        Text(msg.body, color = Color.White, fontSize = 14.sp)
                        Text(
                            fmtTime(msg.createdAt), color = Color(0xFFC4CEDC), fontSize = 9.sp,
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
                            val res = ClubApi.sendSquadMessage(session, squad.id, me.id, text)
                            if (res.isSuccess) {
                                ClubApi.sendMessagePushSquad(session, squad.id, text)
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

/** snapshotFlow on the list's first visible index (same pattern as ChatScreen). */
private fun snapshotFlowCompat(state: androidx.compose.foundation.lazy.LazyListState) =
    androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }

// ── Squad profile (identity, roster & captain tools) ─────────────────────

/** "2026-11" → "Nov 2026" for the squad's Since chip. */
private fun squadSince(createdAt: String?): String? = createdAt?.let {
    runCatching {
        java.time.YearMonth.parse(it.take(7))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"))
    }.getOrNull()
}

@Composable
private fun SquadChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.13f))
            .border(1.dp, GlassRim, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
fun SquadDetailsScreen(
    session: Session, me: Member, squad: Squad,
    onBack: () -> Unit,
    onGone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isCaptain = squad.captainId == me.id
    var squadName by remember { mutableStateOf(squad.name) }
    var rows by remember { mutableStateOf<List<SquadMemberRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var allMembers by remember { mutableStateOf<List<Member>>(emptyList()) }
    var addSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editDialog by remember { mutableStateOf(false) }
    var avatarUrl by remember { mutableStateOf(squad.avatarUrl) }
    var squadMotto by remember { mutableStateOf(squad.motto ?: "") }
    var photoBusy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoBusy = true
            scope.launch {
                val bytes = decodeDownscaledJpeg(context, uri)
                val url = bytes?.let {
                    ClubApi.uploadSquadAvatar(session, squad.id, it)
                }?.getOrNull()
                if (url != null) {
                    avatarUrl = url
                    note = "Squad picture updated."
                } else {
                    note = "Picture upload failed — check your connection."
                }
                photoBusy = false
            }
        }
    }
    var removeTarget by remember { mutableStateOf<Member?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDisband by remember { mutableStateOf(false) }

    suspend fun reload() {
        rows = ClubApi.squadMembers(session, squad.id)
        loaded = true
    }

    LaunchedEffect(squad.id) {
        reload()
        allMembers = ClubApi.allMembers(session)
    }

    if (editDialog) {
        var draftName by remember { mutableStateOf(squadName) }
        var draftMotto by remember { mutableStateOf(squadMotto) }
        AlertDialog(
            onDismissRequest = { editDialog = false },
            title = { Text("Edit squad") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftName, onValueChange = { draftName = it },
                        label = { Text("Squad name") }, singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draftMotto, onValueChange = { draftMotto = it },
                        label = { Text("Motto") },
                        placeholder = { Text("e.g. We never miss a final") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editDialog = false
                    val newName = draftName.trim()
                    if (newName.isNotBlank()) {
                        scope.launch {
                            val res = ClubApi.updateSquadInfo(
                                session, squad.id, newName,
                                draftMotto.trim().ifBlank { null }
                            )
                            if (res.isSuccess) {
                                squadName = newName
                                squadMotto = draftMotto.trim()
                            } else {
                                note = "Edit didn't work — captains and the President can edit the squad."
                            }
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editDialog = false }) { Text("Cancel") }
            }
        )
    }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${target.fullName}?") },
            text = { Text("They will leave this squad chat.") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch {
                        val res = ClubApi.removeSquadMember(session, squad.id, target.id)
                        if (res.isSuccess) reload() else note = "Remove didn't work."
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this squad?") },
            text = { Text("You can be added back by the captain.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        val res = ClubApi.removeSquadMember(session, squad.id, me.id)
                        if (res.isSuccess) onGone() else note = "Leave didn't work."
                    }
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmDisband) {
        AlertDialog(
            onDismissRequest = { confirmDisband = false },
            title = { Text("Disband this squad?") },
            text = { Text("The squad and its chat history will be deleted for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisband = false
                    scope.launch {
                        val res = ClubApi.deleteSquad(session, squad.id)
                        if (res.isSuccess) onGone() else note = "Disband didn't work."
                    }
                }) { Text("Disband") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisband = false }) { Text("Cancel") }
            }
        )
    }

    if (adding) {
        val inSquad = rows.map { it.member.id }.toSet()
        val candidates = allMembers.filter { it.id !in inSquad }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable { adding = false }
                )
                Spacer(Modifier.width(10.dp))
                Text("Add members", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                MemberPicker(candidates, addSelected) { m ->
                    addSelected = if (addSelected.contains(m.id)) addSelected - m.id else addSelected + m.id
                }
            }
            GoldButton("ADD ${addSelected.size} MEMBER(S)", enabled = addSelected.isNotEmpty()) {
                scope.launch {
                    val res = ClubApi.addSquadMembers(session, squad.id, addSelected.toList())
                    if (res.isSuccess) {
                        addSelected = emptySet()
                        adding = false
                        reload()
                    } else {
                        note = "Adding didn't work."
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text("Squad Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(10.dp))
        // cover banner + overlapping squad picture (captain gets the pen)
        Box(Modifier.fillMaxWidth().height(196.dp)) {
            Image(
                painter = painterResource(R.drawable.profile_cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(150.dp)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(20.dp))
            )
            Box(Modifier.padding(start = 14.dp).align(Alignment.BottomStart)) {
                AvatarImage(
                    url = avatarUrl, name = squadName, size = 84,
                    showEditPen = isCaptain && !photoBusy,
                    onEditClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                )
            }
        }
        if (photoBusy) {
            Text("Uploading picture…", color = Color(0xFFC4CEDC), fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(squadName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        if (squadMotto.isNotBlank()) {
            Text("“$squadMotto”", color = GoldLight, fontSize = 13.sp)
        }
        Text(
            if (isCaptain) "You are the Captain" else "Squad",
            color = Color(0xFFC4CEDC), fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquadChip("Squad #${squad.id}")
            squad.game?.let { SquadChip(it) }
            squadSince(squad.createdAt)?.let { SquadChip("Since $it") }
            SquadChip("${rows.size} members")
        }
        if (isCaptain) {
            Spacer(Modifier.height(10.dp))
            GoldButton("EDIT SQUAD", enabled = true) { editDialog = true }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "MEMBERS (${rows.size})", color = GoldLight,
            fontWeight = FontWeight.Bold, fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.member.id }) { row ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarImage(url = row.member.avatarUrl, name = row.member.fullName, size = 38)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.member.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    if (row.member.id == squad.captainId) "Captain" else row.squadRole,
                                    color = if (row.member.id == squad.captainId) GoldLight else Color(0xFFC4CEDC),
                                    fontSize = 11.sp
                                )
                            }
                            if (isCaptain && row.member.id != me.id) {
                                Text(
                                    "REMOVE", color = Color(0xFFFF9B9B),
                                    fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                    modifier = Modifier.clickable { removeTarget = row.member }.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (isCaptain) {
            GoldButton("+ ADD MEMBERS", enabled = true) { adding = true }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFFFF9B9B), RoundedCornerShape(14.dp))
                    .clickable { confirmDisband = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("DISBAND SQUAD", color = Color(0xFFFF9B9B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        } else {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFFFF9B9B), RoundedCornerShape(14.dp))
                    .clickable { confirmLeave = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("LEAVE SQUAD", color = Color(0xFFFF9B9B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}
