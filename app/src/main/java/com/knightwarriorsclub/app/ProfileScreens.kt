package com.knightwarriorsclub.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import com.knightwarriorsclub.app.data.AppPrefs
import com.knightwarriorsclub.app.data.BackgroundStore
import com.knightwarriorsclub.app.data.ChatPrefs
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.Squad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
/** Loads a remote image (member photos live in Supabase Storage). */
@Composable
fun rememberRemoteBitmap(url: String?): android.graphics.Bitmap? {
    val context = LocalContext.current
    var bmp by remember(url) { mutableStateOf(url?.let { avatarBitmapCache[it] }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) {
            bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    // beta41: low-data mode decodes pictures at quarter
                    // size (avatars and logos are small on screen anyway).
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        if (AppPrefs.lowData(context)) inSampleSize = 4
                    }
                    conn.inputStream.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }
                }.getOrNull()?.also { avatarBitmapCache[url] = it }
            }
        }
    }
    return bmp
}

private val avatarBitmapCache = mutableMapOf<String, android.graphics.Bitmap>()

/**
 * A member's round photo — the REAL picture from the database when they
 * have one (avatar_url), initials otherwise. On your own profile it can
 * carry the gold pen badge: tap it to change the photo (Pranto, 2026-11-02).
 */
@Composable
fun AvatarImage(
    url: String?, name: String, size: Int,
    showEditPen: Boolean = false,
    onEditClick: () -> Unit = {}
) {
    val bmp = rememberRemoteBitmap(url)
    Box {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(CircleShape)
                    .border(1.5.dp, Gold, CircleShape)
            )
        } else {
            InitialAvatar(name, size)
        }
        if (showEditPen) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size((size / 3).coerceAtLeast(26).dp)
                    .clip(CircleShape).background(Gold)
                    .border(1.5.dp, Ink, CircleShape)
                    .clickable { onEditClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Edit, contentDescription = "Change photo",
                    tint = Ink, modifier = Modifier.size((size / 5).coerceAtLeast(15).dp)
                )
            }
        }
    }
}

/** Decode a picked photo, downscale to 512px like the website, JPEG-encode. */
suspend fun decodeDownscaledJpeg(
    context: android.content.Context, uri: android.net.Uri, maxDim: Int = 512
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w > maxDim || h > maxDim) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()
}

private fun gameSymbol(game: String): String = when (game) {
    "eFootball" -> "●"
    "PUBG" -> "◎"
    "Clash of Clans" -> "♜"
    "Call of Duty" -> "✛"
    else -> "◆"
}

@Composable
fun StatPill(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.13f))
            .border(1.dp, GlassRim, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/**
 * A member's profile page — redesigned 2026-11-02 (Pranto: "looks
 * boring"): club cover banner, big photo overlapping it (real photo
 * from the database; gold pen on your own to change it), stat pills,
 * game cards, honours, and the SQUAD line for every member.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberProfileScreen(
    session: Session, me: Member, memberId: Long,
    onBack: () -> Unit,
    onMessage: (Member) -> Unit = {},
    onOpenProfile: (Member) -> Unit = {},
    onOpenSquad: (Squad) -> Unit = {},
    onOpenPlayerCard: (Member) -> Unit = {},
    onCall: (Member, String) -> Unit = { _, _ -> },
    onEdit: () -> Unit = {},
    onMeChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var member by remember { mutableStateOf<Member?>(null) }
    var games by remember { mutableStateOf<List<Triple<String, String?, Int?>>>(emptyList()) }
    var squads by remember { mutableStateOf<List<Pair<Squad, String>>>(emptyList()) }
    var squadHonours by remember { mutableStateOf<List<ClubApi.SquadHonour>>(emptyList()) }
    var playerHonours by remember { mutableStateOf<List<ClubApi.MemberHonour>>(emptyList()) }
    // beta37 profile fill-out (Pranto): friends, my friendship state with
    // this member, and the official match record from the website data.
    var friends by remember { mutableStateOf<List<Member>>(emptyList()) }
    var friendRel by remember { mutableStateOf<ClubApi.Friendship?>(null) }
    var friendBusy by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf<List<Int>>(listOf(0, 0, 0, 0)) }
    var awardPlayerDialog by remember { mutableStateOf(false) }
    // beta42: report-a-member (Part 19 member_reports)
    var reportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var friendsDialog by remember { mutableStateOf(false) }
    var removePlayerHonour by remember { mutableStateOf<ClubApi.MemberHonour?>(null) }
    val isPresident = me.role.equals("President", ignoreCase = true)
    var loaded by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    var refreshing by remember { mutableStateOf(false) }
    // beta41 (Pranto): pull down on the profile to reload it — the
    // friend state used to go stale ("Request sent" after acceptance)
    // until you left and came back.
    suspend fun loadProfile() {
        // beta66 (Pranto): a one-off network hiccup used to dead-end this
        // screen ("could not be loaded", 2026-11-04) — retry once before
        // giving up, and the error state below offers a manual retry too
        var mm = ClubApi.memberById(session, memberId)
        if (mm == null) {
            kotlinx.coroutines.delay(700)
            mm = ClubApi.memberById(session, memberId)
        }
        member = mm
        games = ClubApi.memberGames(session, memberId)
        squads = ClubApi.squadsOf(session, memberId)
        squadHonours = ClubApi.memberSquadHonours(session, memberId)
        playerHonours = ClubApi.memberHonours(session, memberId)
        friends = ClubApi.friendsOf(session, memberId)
        if (mm != null) record = ClubApi.matchRecord(mm.fullName)
        if (memberId != me.id) {
            friendRel = ClubApi.friendshipsOf(session, me.id).firstOrNull {
                (it.requesterId == me.id && it.addresseeId == memberId) ||
                    (it.requesterId == memberId && it.addresseeId == me.id)
            }
        }
        loaded = true
    }
    LaunchedEffect(memberId) { loadProfile() }

    if (reportDialog) {
        AlertDialog(
            onDismissRequest = { reportDialog = false },
            title = { Text("Report this member") },
            text = {
                Column {
                    Text(
                        "The President will see your report. Reports are not public.",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    ClubTextField("Reason", reportReason, onChange = { reportReason = it })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reportReason.isNotBlank(),
                    onClick = {
                        reportDialog = false
                        scope.launch {
                            val res = ClubApi.reportMember(
                                session, me.id, memberId, reportReason.trim()
                            )
                            note = if (res.isSuccess) "Report sent to the President."
                            else "Report failed — try again."
                            reportReason = ""
                        }
                    }
                ) { Text("SEND REPORT") }
            },
            dismissButton = {
                TextButton(onClick = { reportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (friendsDialog) {
        AlertDialog(
            onDismissRequest = { friendsDialog = false },
            title = { Text("Friends (${friends.size})") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(friends, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                friendsDialog = false
                                onOpenProfile(f)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarImage(url = f.avatarUrl, name = f.fullName, size = 36)
                            Spacer(Modifier.width(10.dp))
                            Text(f.fullName, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { friendsDialog = false }) { Text("Close") }
            }
        )
    }

    val m = member
    val own = memberId == me.id
    var awardLogoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val awardLogoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> awardLogoUri = uri }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && m != null) {
            photoBusy = true
            scope.launch {
                val bytes = decodeDownscaledJpeg(context, uri)
                val uploaded = bytes?.let { ClubApi.uploadAvatar(session, it) }
                val url = uploaded?.getOrNull()
                if (url != null) {
                    val upd = ClubApi.updateMemberProfile(
                        session, m.id, mapOf("avatar_url" to JsonPrimitive(url))
                    )
                    if (upd.isSuccess) {
                        member = m.copy(avatarUrl = url)
                        note = "Profile photo updated."
                        onMeChanged()
                    } else {
                        note = "Photo uploaded but the profile link failed — try again."
                    }
                } else {
                    note = "Photo upload failed — check your connection."
                }
                photoBusy = false
            }
        }
    }

    if (awardPlayerDialog) {
        var draftTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { awardPlayerDialog = false },
            title = { Text("Award a trophy") },
            text = {
                Column {
                    Text(
                        "An individual honour for ${m?.fullName ?: "this player"} — " +
                            "it appears on their profile.",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draftTitle, onValueChange = { draftTitle = it },
                        label = { Text("Award name") },
                        placeholder = { Text("e.g. Player of the Tournament") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            awardLogoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) {
                            Text(if (awardLogoUri == null) "Add award logo (optional)" else "Change logo")
                        }
                        if (awardLogoUri != null) {
                            Text("✓ Logo picked", color = GoldLight, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = draftTitle.trim()
                    awardPlayerDialog = false
                    if (t.isNotBlank()) {
                        scope.launch {
                            var logoUrl: String? = null
                            val uri = awardLogoUri
                            if (uri != null) {
                                val bytes = decodeDownscaledJpeg(context, uri)
                                if (bytes != null) {
                                    logoUrl = ClubApi.uploadAwardLogo(session, bytes).getOrNull()
                                }
                            }
                            awardLogoUri = null
                            val res = ClubApi.awardMemberHonour(session, memberId, t, me.id, logoUrl)
                            if (res.isSuccess) {
                                playerHonours = ClubApi.memberHonours(session, memberId)
                                note = "Trophy awarded."
                            } else {
                                note = "Award didn't work — only the President can award trophies."
                            }
                        }
                    }
                }) { Text("Award") }
            },
            dismissButton = {
                TextButton(onClick = { awardPlayerDialog = false }) { Text("Cancel") }
            }
        )
    }
    removePlayerHonour?.let { h ->
        AlertDialog(
            onDismissRequest = { removePlayerHonour = null },
            title = { Text("Remove this trophy?") },
            text = { Text("“${h.title}” will be removed from this profile.") },
            confirmButton = {
                TextButton(onClick = {
                    removePlayerHonour = null
                    scope.launch {
                        val res = ClubApi.deleteMemberHonour(session, h.id)
                        if (res.isSuccess) {
                            playerHonours = ClubApi.memberHonours(session, memberId)
                        } else {
                            note = "Remove didn't work."
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removePlayerHonour = null }) { Text("Cancel") }
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
            Text("Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        if (m == null) {
            Text("This profile could not be loaded.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                loaded = false
                scope.launch { loadProfile() }
            }) { Text("Tap to retry") }
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    loadProfile()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // cover banner + overlapping photo
                val coverBmp = rememberRemoteBitmap(m.coverUrl)
                Box(Modifier.fillMaxWidth().height(238.dp)) {
                    if (coverBmp != null) {
                        Image(
                            bitmap = coverBmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(192.dp)
                                .align(Alignment.TopCenter)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.profile_cover),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(192.dp)
                                .align(Alignment.TopCenter)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    }
                    Box(Modifier.padding(start = 14.dp).align(Alignment.BottomStart)) {
                        AvatarImage(
                            url = m.avatarUrl, name = m.fullName, size = 92,
                            showEditPen = own && !photoBusy,
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
                    Text("Uploading photo…", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 2.dp)) {
                    Text(m.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text(
                        m.username?.let { "@$it" } ?: "Knight Warriors member",
                        color = Color(0xFFC4CEDC), fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // beta39 (Pranto): the MEMBER role pill is removed.
                    memberSince(m.createdAt)?.let {
                        Text("Member since $it", color = Color(0xFFC4CEDC), fontSize = 12.sp)
                    }
                    if (!m.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(m.bio, color = Color(0xFFF2F5F9), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    // beta37: fuller stat set — games, squads, member number,
                    // favourite game, game IDs and friends at a glance.
                    // beta39 (Pranto): no Games-count pill any more;
                    // Member # lives in the row of three below.
                    val pills = mutableListOf<String>()
                    if (!m.favouriteGame.isNullOrBlank()) pills.add("★ ${m.favouriteGame}")
                    val idCount = games.count { it.second != null }
                    if (idCount > 0) pills.add("$idCount Game IDs")
                    // beta43 (Pranto): the Friends pill is separate and
                    // TAPPABLE — it opens the friend list (below).
                    // beta38 (Pranto): the squad NAME lives here, not a
                    // count \u2014 and tapping it opens the squad page.
                    val mySquad = squads.firstOrNull()?.first
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatPill("Member #${m.id}")
                        if (mySquad != null) {
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(Gold.copy(alpha = 0.16f))
                                    .border(1.dp, Gold, RoundedCornerShape(50))
                                    .clickable { onOpenSquad(mySquad) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "\uD83D\uDEE1\uFE0F ${mySquad.name}",
                                    color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                            }
                        } else {
                            StatPill("No Squad")
                        }
                        if (squads.any { it.first.captainId == m.id }) {
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(Gold.copy(alpha = 0.16f))
                                    .border(1.dp, Gold, RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "🛡️ Squad Captain",
                                    color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    pills.chunked(3).forEach { rowPills ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowPills.forEach { StatPill(it) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (friends.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.13f))
                                    .border(1.dp, Gold, RoundedCornerShape(50))
                                    .clickable { friendsDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "${friends.size} Friends ›",
                                    color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // badges derived from what the member actually is/has
                    val badges = mutableListOf<String>()
                    if (m.id <= 10L) badges.add("⚔️ Original Warrior")
                    if (games.size >= 3) badges.add("🎮 Multi-Game Player")
                    if (badges.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            badges.forEach { b ->
                                Box(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(Gold.copy(alpha = 0.16f))
                                        .border(1.dp, Gold, RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(b, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (own) {
                item {
                    GoldButton("EDIT PROFILE", enabled = true) { onEdit() }
                }
            } else {
                item {
                    // beta41 (Pranto): MESSAGE and the friend button share
                    // ONE row; the call/video round buttons are gone from
                    // the profile (calls live inside the chat). Friend
                    // labels are shortened to fit the half row.
                    val rel = friendRel
                    val sentByMe = rel != null && rel.status == "pending" && rel.requesterId == me.id
                    val label = when {
                        rel == null || rel.status == "declined" -> "＋ ADD FRIEND"
                        rel.status == "accepted" -> "✓ FRIENDS"
                        sentByMe -> "REQUEST SENT"
                        else -> "ACCEPT REQUEST"
                    }
                    val goldBtn = label == "＋ ADD FRIEND" || label == "ACCEPT REQUEST"
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
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (goldBtn) Gold else Color.White.copy(alpha = 0.12f))
                            .then(
                                if (goldBtn) Modifier
                                else Modifier.border(1.dp, GlassRim, RoundedCornerShape(14.dp))
                            )
                            .clickable(enabled = !friendBusy && !sentByMe) {
                                val r = friendRel
                                friendBusy = true
                                scope.launch {
                                    when {
                                        r == null || r.status == "declined" -> {
                                            val res = ClubApi.sendFriendRequest(session, me.id, m.id)
                                            if (res.isSuccess) {
                                                friendRel = ClubApi.Friendship(me.id, m.id, "pending")
                                                note = "Friend request sent to ${m.fullName}."
                                            } else note = "Request failed — try again."
                                        }
                                        r.status == "accepted" -> {
                                            val res = ClubApi.removeFriendship(session, me.id, m.id)
                                            if (res.isSuccess) {
                                                friendRel = null
                                                friends = ClubApi.friendsOf(session, memberId)
                                                note = "Friend removed."
                                            } else note = "That didn't work — try again."
                                        }
                                        r.status == "pending" && r.addresseeId == me.id -> {
                                            val res = ClubApi.respondFriendRequest(session, me.id, m.id, true)
                                            if (res.isSuccess) {
                                                friendRel = r.copy(status = "accepted")
                                                friends = ClubApi.friendsOf(session, memberId)
                                                note = "You and ${m.fullName} are now friends."
                                            } else note = "That didn't work — try again."
                                        }
                                    }
                                    friendBusy = false
                                }
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, color = if (goldBtn) Ink else Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                    }
                }
            }
            item {
                // beta41: Player Card entry (every profile)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Gold, RoundedCornerShape(14.dp))
                        .clickable { onOpenPlayerCard(m) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "🏆 PLAYER CARD", color = GoldLight,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                }
            }
            if (!own) {
                item {
                    Text(
                        "🚩 Report this member",
                        color = Color(0xFFC4CEDC), fontSize = 12.sp,
                        modifier = Modifier.clickable { reportDialog = true }.padding(4.dp)
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TOURNAMENT HONOURS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (isPresident) {
                        Text(
                            "AWARD 🏆", color = GoldLight,
                            fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.clickable { awardPlayerDialog = true }.padding(6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (playerHonours.isEmpty() && squadHonours.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("♛  No honours yet", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Win a club tournament and it appears here.",
                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        playerHonours.forEach { h ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (h.logoUrl != null) {
                                        AvatarImage(url = h.logoUrl, name = h.title, size = 34)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "🏆  ${h.title}", color = Color.White,
                                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                        )
                                        Text(
                                            "Individual honour",
                                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                                        )
                                    }
                                    if (isPresident) {
                                        Text(
                                            "REMOVE", color = Color(0xFFFF9B9B),
                                            fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                            modifier = Modifier.clickable { removePlayerHonour = h }
                                                .padding(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        squadHonours.forEach { h ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (h.logoUrl != null) {
                                        AvatarImage(url = h.logoUrl, name = h.title, size = 34)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "🏆  ${h.title}", color = Color.White,
                                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                        )
                                        Text(
                                            "Won with ${h.squadName ?: "a squad"}",
                                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("GAMES", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                if (games.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("No games added yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        games.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { (game, uid, strength) ->
                                    GlassCard(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(gameSymbol(game), color = GoldLight, fontSize = 17.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(game, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                // beta37: UID + team strength on the
                                                // profile itself (Pranto's fill-out list)
                                                val sub = listOfNotNull(
                                                    uid?.let { "UID $it" },
                                                    strength?.let { "⚔ $it" }
                                                ).joinToString(" • ")
                                                if (sub.isNotBlank()) {
                                                    Text(sub, color = Color(0xFFC4CEDC), fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                Text("MATCH RECORD", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    if (record[0] == 0) {
                        Text("No official tournament matches yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp)
                    } else {
                        val winPct = record[1] * 100 / record[0]
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatPill("Played ${record[0]}")
                            StatPill("Won ${record[1]}")
                            StatPill("Win $winPct%")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Drawn ${record[2]} \u00b7 Lost ${record[3]} \u2014 from official club tournaments on the website.",
                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                        )
                    }
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
}


/**
 * Edit your own profile (Pranto, 2026-11-02): photo (with the pen),
 * name, username, bio, favourite game, and each game's UID + team
 * strength — the same fields the website's Edit Profile offers.
 */
@Composable
fun EditProfileScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var favourite by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf<Member?>(null) }
    var gameRows by remember { mutableStateOf<List<Triple<String, String?, Int?>>>(emptyList()) }
    val gameEdits = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var busy by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(me.id) {
        val fresh = ClubApi.memberById(session, me.id)
        if (fresh != null) {
            original = fresh
            avatarUrl = fresh.avatarUrl
            fullName = fresh.fullName
            username = fresh.username ?: ""
            bio = fresh.bio ?: ""
            favourite = fresh.favouriteGame
        }
        val rows = ClubApi.memberGames(session, me.id)
        gameRows = rows
        rows.forEach { (g, uid, st) ->
            gameEdits[g] = (uid ?: "") to (st?.toString() ?: "")
        }
        loaded = true
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoBusy = true
            scope.launch {
                val bytes = decodeDownscaledJpeg(context, uri)
                val url = bytes?.let { ClubApi.uploadAvatar(session, it) }?.getOrNull()
                if (url != null) {
                    val upd = ClubApi.updateMemberProfile(
                        session, me.id, mapOf("avatar_url" to JsonPrimitive(url))
                    )
                    if (upd.isSuccess) {
                        avatarUrl = url
                        error = null
                    } else {
                        error = "Photo uploaded but saving it failed — try again."
                    }
                } else {
                    error = "Photo upload failed — check your connection."
                }
                photoBusy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text("Edit Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        url = avatarUrl, name = fullName.ifBlank { me.fullName }, size = 76,
                        showEditPen = !photoBusy,
                        onEditClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Profile photo", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (photoBusy) "Uploading…" else "Tap the gold pen to change it",
                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                        )
                    }
                }
            }
            item {
                ClubTextField("Full name", fullName, { fullName = it })
            }
            item {
                ClubTextField("Username", username, { username = it }, placeholder = "for your profile link")
            }
            item {
                ClubTextField("Bio", bio, { bio = it }, placeholder = "A line about you")
            }
            item {
                Text("FAVOURITE GAME", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("eFootball", "PUBG", "Clash of Clans", "Call of Duty").forEach { g ->
                        val sel = favourite == g
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) Gold else Color.White.copy(alpha = 0.13f))
                                .border(1.dp, if (sel) Gold else GlassRim, RoundedCornerShape(50))
                                .clickable { favourite = if (sel) null else g }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                g, color = if (sel) Ink else Color.White,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            if (gameRows.isNotEmpty()) {
                item {
                    Text("MY GAMES — UID & TEAM STRENGTH", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                items(gameRows, key = { it.first }) { (game, _, _) ->
                    val edit = gameEdits[game] ?: ("" to "")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(game, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        ClubTextField("Game UID", edit.first, { v ->
                            gameEdits[game] = v to edit.second
                        }, placeholder = "your in-game ID")
                        Spacer(Modifier.height(8.dp))
                        ClubTextField("Team strength", edit.second, { v ->
                            gameEdits[game] = edit.first to v.filter { ch -> ch.isDigit() }
                        }, placeholder = "e.g. 3200")
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        if (error != null) {
            Text(error!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        GoldButton(if (busy) "SAVING…" else "SAVE CHANGES", enabled = !busy && fullName.isNotBlank()) {
            busy = true
            error = null
            scope.launch {
                val orig = original
                val fields = mutableMapOf<String, JsonElement>()
                if (orig == null || fullName.trim() != orig.fullName) {
                    fields["full_name"] = JsonPrimitive(fullName.trim())
                }
                val newUser = username.trim().lowercase().removePrefix("@")
                if (orig == null || newUser != (orig.username ?: "")) {
                    fields["username"] =
                        if (newUser.isBlank()) JsonNull else JsonPrimitive(newUser)
                }
                val newBio = bio.trim()
                if (orig == null || newBio != (orig.bio ?: "")) {
                    fields["bio"] = if (newBio.isBlank()) JsonNull else JsonPrimitive(newBio)
                }
                if (orig == null || favourite != orig.favouriteGame) {
                    fields["favourite_game"] =
                        favourite?.let { JsonPrimitive(it) } ?: JsonNull
                }
                var failure: String? = null
                if (fields.isNotEmpty()) {
                    val res = ClubApi.updateMemberProfile(session, me.id, fields)
                    if (res.isFailure) {
                        failure = "Couldn't save — if you changed your username, it may already be taken."
                    }
                }
                if (failure == null) {
                    for ((game, origRow) in gameRows.associateBy { it.first }) {
                        val edit = gameEdits[game] ?: continue
                        val newUid = edit.first.trim().ifBlank { null }
                        val newStrength = edit.second.toIntOrNull()
                        if (newUid != origRow.second || newStrength != origRow.third) {
                            val r = ClubApi.updateMemberGame(session, me.id, game, newUid, newStrength)
                            if (r.isFailure) {
                                failure = "Profile saved, but the $game details didn't update."
                                break
                            }
                        }
                    }
                }
                busy = false
                if (failure != null) {
                    error = failure
                } else {
                    onSaved()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
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
                        AvatarImage(url = partner.avatarUrl, name = partner.fullName, size = 72)
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

// beta39 (Pranto): native member search, opened from the header
// magnifier. Type to filter every club member by name or @username;
// tapping a result opens that member's profile.
@Composable
fun MemberSearchScreen(
    session: Session,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { members = ClubApi.allMembers(session) }
    }
    val results = remember(query, members) {
        // Pranto (2026-11-03): no list on open — results appear only
        // once he starts typing.
        if (query.isBlank()) emptyList()
        else members.filter {
            it.fullName.contains(query, ignoreCase = true) ||
                (it.username?.contains(query, ignoreCase = true) == true)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Search members", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        ClubTextField("Name or @username", query, onChange = { query = it })
        Spacer(Modifier.height(12.dp))
        if (query.isBlank()) {
            Text(
                "Type a name or @username to search.",
                color = Color(0xFFC4CEDC), fontSize = 13.sp
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { m ->
                GlassCard(Modifier.fillMaxWidth().clickable { onOpenProfile(m.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarImage(m.avatarUrl, m.fullName, 40)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(m.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                m.username?.let { "@$it" } ?: m.role,
                                color = Color(0xFFC4CEDC), fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
