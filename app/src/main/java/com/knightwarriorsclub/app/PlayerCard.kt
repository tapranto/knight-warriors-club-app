package com.knightwarriorsclub.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.Squad
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * beta41 (Wave 2): the Player Card — a member's club identity on one
 * designed card (crest, photo, squad, games, match record, honours),
 * shareable as a PNG image via the Android share sheet.
 */
@Composable
fun PlayerCardScreen(
    session: Session, memberId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var member by remember { mutableStateOf<Member?>(null) }
    var games by remember { mutableStateOf<List<Triple<String, String?, Int?>>>(emptyList()) }
    var squads by remember { mutableStateOf<List<Pair<Squad, String>>>(emptyList()) }
    var honourTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var record by remember { mutableStateOf<List<Int>>(listOf(0, 0, 0, 0)) }
    var note by remember { mutableStateOf<String?>(null) }
    val graphicsLayer = rememberGraphicsLayer()

    LaunchedEffect(memberId) {
        val mm = ClubApi.memberById(session, memberId)
        member = mm
        games = ClubApi.memberGames(session, memberId)
        squads = ClubApi.squadsOf(session, memberId)
        val player = ClubApi.memberHonours(session, memberId).map { it.title }
        val squadH = ClubApi.memberSquadHonours(session, memberId).map { it.title }
        honourTitles = player + squadH
        if (mm != null) record = ClubApi.matchRecord(mm.fullName)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Player Card", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        val m = member
        if (m == null) {
            Text("Loading…", color = Color(0xFFC4CEDC), fontSize = 13.sp)
            return@Column
        }
        // ---- the card itself (this is what gets shared) ----
        Box(
            Modifier.fillMaxWidth()
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                }
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF13233D), Color(0xFF0A101C))
                    )
                )
                .border(1.5.dp, Gold, RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.club_logo),
                    contentDescription = "Club crest",
                    modifier = Modifier.size(54.dp).clip(CircleShape)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "KNIGHT WARRIORS", color = GoldLight, fontFamily = BrandFont, fontSize = 15.sp
                )
                Spacer(Modifier.height(12.dp))
                AvatarImage(m.avatarUrl, m.fullName, 84)
                Spacer(Modifier.height(10.dp))
                Text(
                    m.fullName, color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 21.sp
                )
                Text(
                    m.username?.let { "@$it" } ?: "Knight Warriors member",
                    color = Color(0xFFC4CEDC), fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatPill("Member #${m.id}")
                    val sq = squads.firstOrNull()?.first
                    if (sq != null) StatPill("\uD83D\uDEE1\uFE0F ${sq.name}") else StatPill("No Squad")
                    if (!m.favouriteGame.isNullOrBlank()) StatPill("\u2605 ${m.favouriteGame}")
                }
                Spacer(Modifier.height(14.dp))
                if (games.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        games.forEach { (game, uid, strength) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(game, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (uid != null) Text(uid, color = Color(0xFFC4CEDC), fontSize = 11.sp)
                                if (strength != null) Text("  \u00B7  $strength", color = GoldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "MATCHES ${record[0]}  \u00B7  WON ${record[1]}  \u00B7  DRAWN ${record[2]}  \u00B7  LOST ${record[3]}",
                    color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
                if (honourTitles.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    honourTitles.take(3).forEach { t ->
                        Text("\uD83C\uDFC6 $t", color = Color(0xFFFFF4E4), fontSize = 12.sp)
                    }
                    if (honourTitles.size > 3) {
                        Text("+ ${honourTitles.size - 3} more honours", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "EST. 2023 \u00B7 PLAY \u2022 COMPETE \u2022 CONQUER",
                    color = Color(0xFFC4CEDC), fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GoldButton("SHARE CARD", enabled = true) {
            scope.launch {
                runCatching {
                    val bmp = graphicsLayer.toImageBitmap().asAndroidBitmap()
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    val file = File(dir, "player-card-$memberId.png")
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    val uri = FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share player card"))
                }.onFailure { note = "Sharing failed on this phone." }
            }
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp)
        }
    }
}
