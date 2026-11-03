package com.knightwarriorsclub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

/**
 * Notifications page (beta37, Pranto 2026-11-03): the bell in the header
 * opens this — the same rows the website shows (broadcasts, squad
 * notices, club info) plus pending friend requests with Accept/Decline,
 * exactly like the website's Inbox page. Opening it marks the
 * notifications read.
 */
@Composable
fun NotificationsScreen(
    session: Session, me: Member,
    onBack: () -> Unit,
    onOpenEveryone: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenProfile: (Member) -> Unit
) {
    val scope = rememberCoroutineScope()
    var notifs by remember { mutableStateOf<List<ClubApi.ClubNotification>>(emptyList()) }
    var requests by remember { mutableStateOf<List<Member>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(me.id) {
        notifs = ClubApi.notifications(session, me.id)
        requests = ClubApi.pendingFriendRequests(session, me.id)
        loaded = true
        ClubApi.markNotificationsRead(session, me.id)
    }

    fun notifIcon(type: String): String = when (type) {
        "broadcast" -> "📣"
        "squad_invite" -> "🛡️"
        "friend_request" -> "🤝"
        "system" -> "⚙️"
        else -> "🔔"
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
                Text("Notifications", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Club news, squads and friend requests", color = Color(0xFFC4CEDC), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!loaded) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            if (requests.isNotEmpty()) {
                item {
                    Text(
                        "FRIEND REQUESTS", color = GoldLight,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                }
                items(requests, key = { "req-${it.id}" }) { req ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clickable { onOpenProfile(req) }) {
                                AvatarImage(url = req.avatarUrl, name = req.fullName, size = 44)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(req.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("wants to be your friend", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(Gold)
                                    .clickable {
                                        scope.launch {
                                            val res = ClubApi.respondFriendRequest(session, me.id, req.id, true)
                                            if (res.isSuccess) {
                                                requests = requests.filter { it.id != req.id }
                                                note = "You and ${req.fullName} are now friends."
                                            } else note = "That didn't work — try again."
                                        }
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ACCEPT", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, GlassRim, RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch {
                                            val res = ClubApi.respondFriendRequest(session, me.id, req.id, false)
                                            if (res.isSuccess) {
                                                requests = requests.filter { it.id != req.id }
                                            } else note = "That didn't work — try again."
                                        }
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("DECLINE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(2.dp)) }
            }
            if (notifs.isNotEmpty()) {
                item {
                    Text(
                        "NOTIFICATIONS", color = GoldLight,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                }
                items(notifs, key = { it.id }) { n ->
                    GlassCard(
                        Modifier.fillMaxWidth().clickable {
                            if (n.type == "broadcast") onOpenEveryone() else onOpenInbox()
                        },
                        fill = if (n.isRead) GlassFill else Color.White.copy(alpha = 0.20f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notifIcon(n.type), fontSize = 20.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    n.title, color = Color.White,
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                )
                                if (!n.body.isNullOrBlank()) {
                                    Text(n.body, color = Color(0xFFC4CEDC), fontSize = 12.sp)
                                }
                                Text(fmtTime(n.createdAt), color = Color(0xFF8FA1B8), fontSize = 10.sp)
                            }
                            if (!n.isRead) {
                                Box(
                                    Modifier.size(9.dp).clip(CircleShape).background(Gold)
                                )
                            }
                        }
                    }
                }
            }
            if (requests.isEmpty() && notifs.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Nothing here yet", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Club broadcasts, squad news and friend requests will land on this page.",
                            color = Color(0xFFC4CEDC), fontSize = 11.sp
                        )
                    }
                }
            }
            if (note != null) {
                item { Text(note!!, color = Color(0xFFC4CEDC), fontSize = 12.sp) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
