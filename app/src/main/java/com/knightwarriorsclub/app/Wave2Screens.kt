package com.knightwarriorsclub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.Session
import kotlinx.coroutines.launch

// beta42 (Wave 2 part 2): Club Feed, Polls & MVP, President Console.
// Data lives in the Part 19 tables (feed_posts, polls, mvp_ballots,
// member_reports) plus the existing members / tournament_entries.

@Composable
private fun Wave2Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun FeedScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf<List<ClubApi.FeedPost>>(emptyList()) }
    var byId by remember { mutableStateOf<Map<Long, Member>>(emptyMap()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val isPresident = me.role.equals("President", ignoreCase = true)

    suspend fun load() {
        posts = ClubApi.feedPosts(session)
        byId = ClubApi.allMembers(session).associateBy { it.id }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Wave2Header("Club Feed", onBack)
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Post to the club", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            ClubTextField("Say something to the club…", draft, onChange = { draft = it })
            Spacer(Modifier.height(8.dp))
            GoldButton("POST", enabled = draft.isNotBlank() && !busy) {
                busy = true
                scope.launch {
                    val res = ClubApi.createFeedPost(session, me.id, draft.trim())
                    if (res.isSuccess) {
                        draft = ""
                        load()
                    } else note = "Post failed — try again."
                    busy = false
                }
            }
            note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (posts.isEmpty()) {
                item {
                    Text(
                        "No posts yet — be the first to say something.",
                        color = Color(0xFFC4CEDC), fontSize = 13.sp
                    )
                }
            }
            items(posts, key = { it.id }) { post ->
                val author = byId[post.memberId]
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable { onOpenProfile(post.memberId) }) {
                            AvatarImage(author?.avatarUrl, author?.fullName ?: "?", 36)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                author?.fullName ?: "Member",
                                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                            )
                            Text(fmtTime(post.createdAt), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                        }
                        if (post.memberId == me.id || isPresident) {
                            Text(
                                "Delete", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        ClubApi.deleteFeedPost(session, post.id)
                                        load()
                                    }
                                }.padding(6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(post.body, color = Color(0xFFF2F5F9), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun PollsScreen(
    session: Session, me: Member,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var polls by remember { mutableStateOf<List<ClubApi.Poll>>(emptyList()) }
    var optionsByPoll by remember { mutableStateOf<Map<Long, List<ClubApi.PollOption>>>(emptyMap()) }
    var votesByPoll by remember { mutableStateOf<Map<Long, List<ClubApi.PollVote>>>(emptyMap()) }
    var ballots by remember { mutableStateOf<List<ClubApi.MvpBallot>>(emptyList()) }
    var votesByBallot by remember { mutableStateOf<Map<Long, List<ClubApi.MvpVote>>>(emptyMap()) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var createPollOpen by remember { mutableStateOf(false) }
    var createBallotOpen by remember { mutableStateOf(false) }
    var voteForBallot by remember { mutableStateOf<ClubApi.MvpBallot?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val isPresident = me.role.equals("President", ignoreCase = true)
    val byId = members.associateBy { it.id }

    suspend fun loadPollExtras(pollId: Long) {
        optionsByPoll = optionsByPoll + (pollId to ClubApi.pollOptions(session, pollId))
        votesByPoll = votesByPoll + (pollId to ClubApi.pollVotes(session, pollId))
    }
    suspend fun loadBallotExtras(ballotId: Long) {
        votesByBallot = votesByBallot + (ballotId to ClubApi.mvpVotes(session, ballotId))
    }
    suspend fun load() {
        members = ClubApi.allMembers(session)
        polls = ClubApi.polls(session)
        polls.forEach { loadPollExtras(it.id) }
        ballots = ClubApi.mvpBallots(session)
        ballots.forEach { loadBallotExtras(it.id) }
    }
    LaunchedEffect(Unit) { load() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Wave2Header("Polls & MVP", onBack) }
        note?.let {
            item { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CLUB POLLS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (isPresident) {
                    Text(
                        "+ NEW POLL", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.clickable { createPollOpen = true }.padding(6.dp)
                    )
                }
            }
        }
        if (polls.isEmpty()) {
            item { Text("No polls yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp) }
        }
        items(polls, key = { it.id }) { poll ->
            val options = optionsByPoll[poll.id] ?: emptyList()
            val votes = votesByPoll[poll.id] ?: emptyList()
            val total = votes.size
            val myVote = votes.firstOrNull { it.memberId == me.id }?.optionId
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        poll.question, color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPresident) {
                        Text(
                            "Delete", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    ClubApi.deletePoll(session, poll.id)
                                    load()
                                }
                            }.padding(6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                options.forEach { opt ->
                    val count = votes.count { it.optionId == opt.id }
                    val pct = if (total > 0) count * 100 / total else 0
                    val mine = myVote == opt.id
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(
                                if (mine) Gold.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.08f)
                            )
                            .border(
                                1.dp,
                                if (mine) Gold else GlassRim,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                scope.launch {
                                    val res = ClubApi.votePoll(session, poll.id, opt.id, me.id)
                                    if (res.isSuccess) loadPollExtras(poll.id)
                                    else note = "Vote failed — try again."
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (mine) "✓ " else "") + opt.label,
                                color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text("$count · $pct%", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text("$total votes — tap an option to vote or change", color = Color(0xFFC4CEDC), fontSize = 10.sp)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MVP VOTING", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (isPresident) {
                    Text(
                        "+ NEW BALLOT", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.clickable { createBallotOpen = true }.padding(6.dp)
                    )
                }
            }
        }
        if (ballots.isEmpty()) {
            item { Text("No MVP ballot open right now.", color = Color(0xFFC4CEDC), fontSize = 13.sp) }
        }
        items(ballots, key = { it.id }) { ballot ->
            val votes = votesByBallot[ballot.id] ?: emptyList()
            val myVote = votes.firstOrNull { it.voterId == me.id }?.nomineeId
            val standings = votes.groupingBy { it.nomineeId }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🏆 ${ballot.title}", color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPresident) {
                        Text(
                            "Delete", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    ClubApi.deleteBallot(session, ballot.id)
                                    load()
                                }
                            }.padding(6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (standings.isEmpty()) {
                    Text("No votes yet.", color = Color(0xFFC4CEDC), fontSize = 12.sp)
                } else {
                    standings.forEach { (nomineeId, count) ->
                        Text(
                            "${byId[nomineeId]?.fullName ?: "Member"} — $count votes",
                            color = if (nomineeId == myVote) GoldLight else Color(0xFFF2F5F9),
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (myVote != null) "Your vote: ${byId[myVote]?.fullName ?: "—"} · TAP TO CHANGE"
                    else "TAP TO VOTE",
                    color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.clickable { voteForBallot = ballot }.padding(4.dp)
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (createPollOpen) {
        var question by remember { mutableStateOf("") }
        var optionsText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createPollOpen = false },
            title = { Text("New poll") },
            text = {
                Column {
                    ClubTextField("Question", question, onChange = { question = it })
                    Spacer(Modifier.height(8.dp))
                    ClubTextField(
                        "Options — one per line", optionsText,
                        onChange = { optionsText = it }, singleLine = false
                    )
                    // beta57 (Pranto hit this): say WHY Create is grey —
                    // a poll needs at least 2 options
                    val optCount = optionsText.lines().count { it.isNotBlank() }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (optCount < 2)
                            "Add at least 2 options, one per line — CREATE turns on at 2 (now: $optCount)"
                        else
                            "$optCount options — ready to create",
                        color = if (optCount < 2) Color(0xFFFF9B9B) else Color(0xFF9BE29B),
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = question.isNotBlank() &&
                        optionsText.lines().count { it.isNotBlank() } >= 2,
                    onClick = {
                        val opts = optionsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        createPollOpen = false
                        scope.launch {
                            val res = ClubApi.createPoll(session, me.id, question.trim(), opts)
                            if (res.isSuccess) {
                                // Pranto (beta45): everyone gets a bell
                                // notification; tapping it opens Polls.
                                ClubApi.notifyAllMembers(
                                    session,
                                    members.map { it.id }.filter { it != me.id },
                                    "🗳️ New club poll", question.trim(), "polls"
                                )
                                ClubApi.sendAnnouncePush(
                                    session, "🗳️ New club poll", question.trim()
                                )
                                load()
                            } else note = "Poll creation failed."
                        }
                    }
                ) { Text("CREATE") }
            },
            dismissButton = {
                TextButton(onClick = { createPollOpen = false }) { Text("Cancel") }
            }
        )
    }
    if (createBallotOpen) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createBallotOpen = false },
            title = { Text("New MVP ballot") },
            text = {
                ClubTextField("Title — e.g. MVP of November", title, onChange = { title = it })
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        createBallotOpen = false
                        scope.launch {
                            val res = ClubApi.createBallot(session, me.id, title.trim())
                            if (res.isSuccess) {
                                ClubApi.notifyAllMembers(
                                    session,
                                    members.map { it.id }.filter { it != me.id },
                                    "🏆 MVP voting is open", title.trim(), "polls"
                                )
                                ClubApi.sendAnnouncePush(
                                    session, "🏆 MVP voting is open", title.trim()
                                )
                                load()
                            } else note = "Ballot creation failed."
                        }
                    }
                ) { Text("CREATE") }
            },
            dismissButton = {
                TextButton(onClick = { createBallotOpen = false }) { Text("Cancel") }
            }
        )
    }
    voteForBallot?.let { ballot ->
        AlertDialog(
            onDismissRequest = { voteForBallot = null },
            title = { Text("Vote — ${ballot.title}") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(members, key = { it.id }) { cand ->
                        Text(
                            cand.fullName, color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                voteForBallot = null
                                scope.launch {
                                    val res = ClubApi.voteMvp(session, ballot.id, cand.id, me.id)
                                    if (res.isSuccess) loadBallotExtras(ballot.id)
                                    else note = "Vote failed — try again."
                                }
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { voteForBallot = null }) { Text("Close") }
            }
        )
    }
}

@Composable
fun PresidentConsoleScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<ClubApi.MemberReport>>(emptyList()) }
    var entries by remember { mutableStateOf<List<ClubApi.LegacyEntry>>(emptyList()) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<Member?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val byId = members.associateBy { it.id }

    suspend fun load() {
        members = ClubApi.allMembers(session)
        reports = ClubApi.memberReports(session)
        entries = ClubApi.legacyEntries(session)
    }
    LaunchedEffect(Unit) { load() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Wave2Header("President Console", onBack) }
        note?.let { item { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) } }
        item {
            Text(
                "REPORTS (${reports.size})", color = GoldLight,
                fontWeight = FontWeight.Bold, fontSize = 12.sp
            )
        }
        if (reports.isEmpty()) {
            item { Text("No open reports.", color = Color(0xFFC4CEDC), fontSize = 13.sp) }
        }
        items(reports, key = { it.id }) { report ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "${byId[report.reporterId]?.fullName ?: "Member"} reported " +
                        (byId[report.reportedId]?.fullName ?: "a member"),
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(report.reason, color = Color(0xFFF2F5F9), fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(fmtTime(report.createdAt), color = Color(0xFFC4CEDC), fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Dismiss", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            scope.launch {
                                ClubApi.deleteReport(session, report.id)
                                load()
                            }
                        }.padding(6.dp)
                    )
                }
            }
        }
        item {
            Text(
                "LEGACY ENTRIES (${entries.size})", color = GoldLight,
                fontWeight = FontWeight.Bold, fontSize = 12.sp
            )
        }
        if (entries.isEmpty()) {
            item { Text("No entries yet.", color = Color(0xFFC4CEDC), fontSize = 13.sp) }
        }
        items(entries, key = { it.id }) { entry ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        byId[entry.memberId]?.fullName ?: "Member",
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Remove", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            scope.launch {
                                val res = ClubApi.presidentRemoveEntry(
                                    session, entry.id, "eFootball Tournament Legacy"
                                )
                                if (res.isSuccess) load()
                                else note = "Remove failed — try again."
                            }
                        }.padding(6.dp)
                    )
                }
            }
        }
        item {
            Text(
                "MEMBERS (${members.size})", color = GoldLight,
                fontWeight = FontWeight.Bold, fontSize = 12.sp
            )
        }
        item {
            ClubTextField("Search members", query, onChange = { query = it })
        }
        val shown = if (query.isBlank()) members else members.filter {
            it.fullName.contains(query, ignoreCase = true) ||
                (it.username?.contains(query, ignoreCase = true) == true)
        }
        items(shown, key = { it.id }) { m ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable { onOpenProfile(m.id) }) {
                        AvatarImage(m.avatarUrl, m.fullName, 36)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Member #${m.id} · ${m.role}",
                            color = Color(0xFFC4CEDC), fontSize = 10.sp
                        )
                    }
                    if (m.id != me.id) {
                        Text(
                            "Remove", color = Color(0xFFFF9B9B), fontSize = 11.sp,
                            modifier = Modifier.clickable { removeTarget = m }.padding(6.dp)
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove member?") },
            text = {
                Text(
                    "Remove ${target.fullName} from the club? This deletes their " +
                        "member row (same as the website console). Their login " +
                        "account stays — delete it too in the Supabase dashboard " +
                        "for a permanent removal."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch {
                        val res = ClubApi.deleteMemberRow(session, target.id)
                        if (res.isSuccess) {
                            note = "${target.fullName} removed."
                            load()
                        } else note = "Remove failed — try again."
                    }
                }) { Text("REMOVE") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}
