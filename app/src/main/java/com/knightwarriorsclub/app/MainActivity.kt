package com.knightwarriorsclub.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.AppPrefs
import com.knightwarriorsclub.app.data.BackgroundStore
import com.knightwarriorsclub.app.data.CallController
import com.knightwarriorsclub.app.data.CallPhase
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Member
import com.knightwarriorsclub.app.data.RealtimeClient
import com.knightwarriorsclub.app.data.Session
import com.knightwarriorsclub.app.data.SessionStore
import com.knightwarriorsclub.app.data.Squad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Knight Warriors Club app.
 * Phase 1: Liquid Glass screens standing (one artwork on every screen —
 * the Home lantern-forest, Pranto's rule 2026-11-01).
 * Phase 2: real Supabase sign-in + inbox + DM chat against the SAME
 * database as knightwarriorsclub.com.
 * Phase 2.1 (Pranto's feedback batch): register (website-identical form),
 * forgot password, real club logo on the sign-in card, password eye
 * toggles, and Rule 1 — double-tap Back to exit.
 * Phase 3: live chat over Supabase Realtime (typing indicators, instant
 * inbox refresh) + native 1-to-1 voice/video calls wire-compatible with
 * the website (earpiece routing included). Ring-when-closed (Firebase)
 * and chat bubbles land in Phase 3.1 after the Firebase setup session.
 * v0.3.1 (Pranto's running-app feedback round): Messenger-style inbox
 * (conversations only + pinned Everyone Club Room + new-chat picker),
 * "@null" parsing fix, personal phone-only background, real crest in the
 * header, no JOIN button in-app, and a static About the Club page.
 */

val Gold = Color(0xFFD4A017)
// the website brand font (Luckiest Guy, --font-tag on the site) —
// header wordmark only, Pranto 2026-11-03
val BrandFont = FontFamily(Font(R.font.luckiest_guy))
val GoldLight = Color(0xFFF2C33B)
val Ink = Color(0xFF0A101C)
val GlassFill = Color.White.copy(alpha = 0.13f)
val GlassRim = Color.White.copy(alpha = 0.38f)

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    News("News", Icons.Filled.Newspaper),
    Events("Events", Icons.Filled.Flag),
    More("More", Icons.Filled.Menu)
}

private sealed interface Screen {
    data object Tabs : Screen
    data object Inbox : Screen
    data object Everyone : Screen
    data object Notifications : Screen
    data object Search : Screen
    data object Feed : Screen
    data object Polls : Screen
    data object Console : Screen
    data class Chat(val partner: Member) : Screen
    // v0.4.0 routes
    data class Profile(val memberId: Long, val backTo: Screen) : Screen
    data class PlayerCard(val memberId: Long, val backTo: Screen) : Screen
    data object ProfileEdit : Screen
    data class ChatDetails(val partner: Member) : Screen
    data object Squads : Screen
    data object AllSquads : Screen
    data object CreateSquad : Screen
    data class SquadChat(val squad: Squad, val backTo: Screen) : Screen
    data class SquadDetails(val squad: Squad, val backTo: Screen) : Screen
    data class WebPage(val url: String, val title: String) : Screen
}

data class KwcDeepLink(val chatMemberId: Long = 0L, val squadId: Long = 0L)

class MainActivity : ComponentActivity() {
    /** Phase 3.1: the latest call invite delivered by FCM push (if any). */
    private val pushInviteState = mutableStateOf<PushInviteData?>(null)

    /** A notification tap asking to open a specific chat. */
    private val deepLinkState = mutableStateOf<KwcDeepLink?>(null)

    private var pendingPermCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        pendingPermCallback?.invoke(granted)
        pendingPermCallback = null
    }

    /**
     * Screen-off calls (Pranto, 2026-11-02): the full-screen intent
     * fired and the phone rang, but with the screen off/locked the
     * incoming-call page stayed hidden behind the lock screen — the
     * activity was never flagged to show over the keyguard or to wake
     * the display. Flag it when (and only when) we were launched by a
     * call notification.
     */
    private fun applyCallScreenFlags(intent: Intent) {
        if (!intent.hasExtra("kwc_open_call")) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyCallScreenFlags(intent)
        pushInviteState.value = intent.toPushInvite()
        deepLinkState.value = intent.toDeepLink()
        val store = SessionStore(this)
        setContent {
            // Dark club theme for ALL Material components (dialogs, menus,
            // switches): without it, popups render stock light-purple —
            // Pranto's "this popup looks boring" report, 2026-11-02.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Gold,
                    secondary = GoldLight,
                    background = Ink,
                    surface = Color(0xFF141E30),
                    surfaceVariant = Color(0xFF1B2942),
                    onBackground = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFFC4CEDC)
                )
            ) {
                ClubApp(
                    store,
                    requestPermissions = { perms, cb ->
                        pendingPermCallback = cb
                        permLauncher.launch(perms)
                    },
                    pushInvite = pushInviteState.value,
                    onPushInviteConsumed = { pushInviteState.value = null },
                    deepLink = deepLinkState.value,
                    onDeepLinkConsumed = { deepLinkState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyCallScreenFlags(intent)
        setIntent(intent)
        pushInviteState.value = intent.toPushInvite()
        deepLinkState.value = intent.toDeepLink()
    }
}

private fun Intent?.toDeepLink(): KwcDeepLink? {
    val chat = this?.getLongExtra("kwc_open_chat", 0L) ?: 0L
    val squad = this?.getLongExtra("kwc_open_squad", 0L) ?: 0L
    if (chat <= 0L && squad <= 0L) return null
    return KwcDeepLink(chatMemberId = chat, squadId = squad)
}

private fun Intent?.toPushInvite(): PushInviteData? {
    val callId = this?.getStringExtra("kwc_call_id") ?: return null
    return PushInviteData(
        callId = callId,
        fromId = getLongExtra("kwc_from_id", 0L),
        fromName = getStringExtra("kwc_from_name") ?: "Member",
        fromAvatar = getStringExtra("kwc_from_avatar"),
        kind = getStringExtra("kwc_kind") ?: "voice"
    )
}

@Composable
fun ClubApp(
    store: SessionStore,
    requestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
    pushInvite: PushInviteData? = null,
    onPushInviteConsumed: () -> Unit = {},
    deepLink: KwcDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf<Session?>(null) }
    var me by remember { mutableStateOf<Member?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Tabs) }

    // Notification tap → open that chat (Phase 3.1+ deep link).
    LaunchedEffect(deepLink, session, me) {
        val d = deepLink; val s = session; val m = me
        if (d != null && s != null && m != null) {
            if (d.chatMemberId > 0) {
                ClubApi.memberById(s, d.chatMemberId)?.let { screen = Screen.Chat(it) }
            } else if (d.squadId > 0) {
                ClubApi.squadsOf(s, m.id).firstOrNull { it.first.id == d.squadId }
                    ?.let { screen = Screen.SquadChat(it.first, Screen.Inbox) }
            }
            onDeepLinkConsumed()
        } else if (d != null && !checking && session == null) {
            onDeepLinkConsumed()
        }
    }
    var tab by remember { mutableStateOf(Tab.Home) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var unreadTotal by remember { mutableStateOf(0) }
    var notifUnread by remember { mutableStateOf(0) }
    var authView by remember { mutableStateOf("login") }
    var registeredEmail by remember { mutableStateOf("") }

    // restore a saved session on launch (sign in once per device)
    LaunchedEffect(Unit) {
        val rt = store.refreshToken()
        if (rt != null) {
            ClubApi.refresh(rt).onSuccess { s ->
                session = s
                store.save(s)
                me = runCatching { ClubApi.ensureMember(s) }.getOrNull()
            }.onFailure { store.clear() }
        }
        checking = false
    }

    // Back behaviour — Rule 1 (Pranto): at the root, Back asks once
    // ("press back again to exit") and only a quick second press exits.
    val context = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = session != null && screen is Screen.Tabs) {
        // beta38 (Pranto): inside the hybrid News/Events tabs, Back first
        // walks back through the web pages he opened — only when the web
        // history is exhausted does Rule 1 below apply.
        if (tab == Tab.News && WebPool.goBack("tab-news")) return@BackHandler
        if (tab == Tab.Events && WebPool.goBack("tab-events")) return@BackHandler
        // Rule 1, refined by Pranto (2026-11-02): Back on any other tab
        // goes HOME first; only at Home does double-tap exit apply
        if (tab != Tab.Home) {
            tab = Tab.Home
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = session != null && screen is Screen.Inbox) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Notifications) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Search) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Feed) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Polls) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Console) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.Everyone) { screen = Screen.Inbox }
    BackHandler(enabled = session != null && screen is Screen.Chat) { screen = Screen.Inbox }
    BackHandler(enabled = session != null && screen is Screen.ChatDetails) {
        screen = Screen.Chat((screen as Screen.ChatDetails).partner)
    }
    BackHandler(enabled = session != null && screen is Screen.PlayerCard) {
        screen = (screen as Screen.PlayerCard).backTo
    }
    BackHandler(enabled = session != null && screen is Screen.Profile) {
        screen = (screen as Screen.Profile).backTo
    }
    BackHandler(enabled = session != null && screen is Screen.ProfileEdit) {
        val mid = me?.id
        screen = if (mid != null) Screen.Profile(mid, Screen.Tabs) else Screen.Tabs
    }
    BackHandler(enabled = session != null && screen is Screen.Squads) { screen = Screen.Tabs }
    BackHandler(enabled = session != null && screen is Screen.CreateSquad) { screen = Screen.Squads }
    BackHandler(enabled = session != null && screen is Screen.SquadChat) {
        screen = (screen as Screen.SquadChat).backTo
    }
    BackHandler(enabled = session != null && screen is Screen.SquadDetails) {
        screen = (screen as Screen.SquadDetails).backTo
    }
    BackHandler(enabled = session != null && screen is Screen.WebPage) {
        // beta38: Back walks the page's own web history first (Pranto:
        // "back goes to home, not the back page I opened")
        val url = (screen as Screen.WebPage).url
        if (!WebPool.goBack("page-$url")) screen = Screen.Tabs
    }
    BackHandler(enabled = session == null && authView != "login") { authView = "login" }

    // envelope badge = real unread DMs, bell badge = unread notifications;
    // both refreshed when back on the tabs
    LaunchedEffect(session, me, screen) {
        val s = session; val m = me
        if (s != null && m != null && screen is Screen.Tabs) {
            unreadTotal = runCatching {
                ClubApi.unreadCounts(s, m.id).values.sum()
            }.getOrDefault(0)
            notifUnread = ClubApi.unreadNotificationCount(s, m.id)
        }
    }

    // Phase 3: one realtime socket + one call controller per signed-in member
    val appContext = LocalContext.current.applicationContext
    val realtime = remember(session, me) {
        val s = session; val m = me
        if (s != null && m != null) {
            RealtimeClient().also { it.connect(s.accessToken) }
        } else null
    }
    val callController = remember(session, me, realtime) {
        val s = session; val m = me; val rt = realtime
        if (s != null && m != null && rt != null) {
            CallController(appContext).also { ctrl ->
                ctrl.permissionRequester = requestPermissions
                ctrl.pushSender = { calleeId, cId, k ->
                    scope.launch { ClubApi.sendCallPush(s, calleeId, cId, k) }
                }
                ctrl.missedPushSender = { calleeId, cId, k ->
                    scope.launch { ClubApi.sendMissedPush(s, calleeId, cId, k) }
                }
                ctrl.start(s, m, rt, scope)
            }
        } else null
    }
    DisposableEffect(realtime) { onDispose { realtime?.disconnect() } }

    // Live message feed (beta19, Pranto 2026-11-02): the badge used to
    // refresh only when he navigated, so new DMs surfaced late. Now the
    // app holds one always-on insert subscription: the moment a DM for
    // me lands, the envelope badge updates and — unless I am already in
    // that chat — a banner drops in, WhatsApp-style.
    var dmBanner by remember(session, me) { mutableStateOf<Pair<Member, String>?>(null) }
    DisposableEffect(session, me, realtime) {
        val s = session; val m = me; val rt = realtime
        if (s == null || m == null || rt == null) return@DisposableEffect onDispose {}
        val channel = "kwc-home-feed-" + m.id
        rt.joinInserts(channel, "direct_messages") { record ->
            val dm = recordToDm(record) ?: return@joinInserts
            if (dm.recipientId != m.id) return@joinInserts
            scope.launch {
                unreadTotal = runCatching {
                    ClubApi.unreadCounts(s, m.id).values.sum()
                }.getOrDefault(unreadTotal)
                val inThatChat = (screen as? Screen.Chat)?.partner?.id == dm.senderId
                if (!inThatChat && !dm.body.startsWith("\uD83D\uDCDE")) {
                    ClubApi.memberById(s, dm.senderId)?.let { sender ->
                        dmBanner = sender to dm.body
                        delay(4500)
                        if (dmBanner?.first?.id == sender.id) dmBanner = null
                    }
                }
            }
        }
        onDispose { rt.leave(channel) }
    }
    DisposableEffect(callController) { onDispose { callController?.stop() } }

    // ── Phase 3.1: Firebase doorbell wiring ──────────────────────────
    // Ask for the notification permission once signed in (Android 13+).
    LaunchedEffect(session) {
        if (session != null && android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }
    // Android 14+: without the full-screen permission the doorbell rings
    // but shows nothing — prompt once per app run until it is granted.
    var fsiDismissed by remember(session) { mutableStateOf(false) }
    val fsiGranted = if (android.os.Build.VERSION.SDK_INT >= 34) {
        appContext.getSystemService(android.app.NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: true
    } else true
    if (session != null && me != null && !fsiGranted && !fsiDismissed) {
        AlertDialog(
            onDismissRequest = { fsiDismissed = true },
            title = { Text("Allow call alerts") },
            text = {
                Text(
                    "Android is blocking incoming club calls from showing on your " +
                        "screen when the app is closed — the call would ring silently " +
                        "in the background. Allow full-screen alerts so calls pop up " +
                        "like WhatsApp."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fsiDismissed = true
                    runCatching {
                        appContext.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                            ).setData(android.net.Uri.parse("package:" + appContext.packageName))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("OPEN SETTINGS", color = GoldLight) }
            },
            dismissButton = {
                TextButton(onClick = { fsiDismissed = true }) {
                    Text("Later", color = Color(0xFFC4CEDC))
                }
            }
        )
    }
    // Register this phone's FCM token against the member (Part 12).
    LaunchedEffect(session, me) {
        val s = session; val m = me
        if (s != null && m != null) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { tok ->
                    scope.launch { ClubApi.registerDeviceToken(s, m.id, tok) }
                }
        }
    }
    // A call invite delivered by push (app was closed) → ring for real.
    LaunchedEffect(pushInvite, callController) {
        val p = pushInvite; val cc = callController
        if (p != null && cc != null) {
            cc.onPushInvite(p)
            onPushInviteConsumed()
        } else if (p != null && !checking && session == null) {
            onPushInviteConsumed()
        }
    }
    // Once the call is no longer ringing, remove the push notification.
    val callPhase = callController?.uiState?.phase
    LaunchedEffect(callPhase) {
        if (callPhase != null && callPhase != CallPhase.INCOMING) {
            PushNotifier.clearCallNotification(appContext)
        }
    }

    // Personal background (Pranto): club artwork by default; a member's own
    // picked picture replaces it on THIS phone only (BackgroundStore).
    var bgVersion by remember { mutableStateOf(0) }
    val customBg = remember(bgVersion) { BackgroundStore.loadBitmap(appContext) }
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (BackgroundStore.saveFromUri(appContext, uri)) bgVersion++
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (customBg != null) {
            Image(
                bitmap = customBg.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(R.drawable.bg_home),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0x59060B14))) // navy veil for contrast

        when {
            checking -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = Color.White, fontSize = 15.sp)
                }
            }
            session == null -> {
                when (authView) {
                    "register" -> RegisterScreen(
                        onBack = { authView = "login" },
                        onCheckEmail = { email ->
                            registeredEmail = email
                            authView = "verifycode"
                        },
                        onSignedIn = { s ->
                            session = s
                            store.save(s)
                            scope.launch {
                                me = runCatching { ClubApi.ensureMember(s) }.getOrNull()
                            }
                        }
                    )
                    "forgot" -> ForgotPasswordScreen(onBack = { authView = "login" })
                    "verifycode" -> VerifyCodeScreen(
                        email = registeredEmail,
                        onVerified = { s ->
                            session = s
                            store.save(s)
                            scope.launch {
                                me = runCatching { ClubApi.ensureMember(s) }.getOrNull()
                            }
                        },
                        onBack = { authView = "login" }
                    )
                    else -> LoginScreen(
                        busy = loginBusy, error = loginError,
                        onSignIn = { email, password ->
                            loginBusy = true
                            loginError = null
                            scope.launch {
                                ClubApi.signIn(email, password)
                                    .onSuccess { s ->
                                        session = s
                                        store.save(s)
                                        me = runCatching { ClubApi.ensureMember(s) }.getOrNull()
                                        if (me == null) {
                                            loginError = "Signed in, but no member profile was found for this account."
                                            session = null
                                            store.clear()
                                        }
                                    }
                                    .onFailure { e ->
                                        val msg = e.message ?: "Sign-in failed"
                                        if (msg.contains("not confirmed", ignoreCase = true)) {
                                            registeredEmail = email
                                            authView = "verifycode"
                                        } else {
                                            loginError = msg
                                        }
                                    }
                                loginBusy = false
                            }
                        },
                        onRegister = { loginError = null; authView = "register" },
                        onForgot = { loginError = null; authView = "forgot" }
                    )
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
                            realtime = realtime,
                            onCall = { p, k -> callController?.startCall(p, k) },
                            onOpenDetails = { screen = Screen.ChatDetails(it) },
                            onBack = { screen = Screen.Inbox }
                        )
                        is Screen.Inbox -> InboxScreen(
                            session = s, me = myProfile ?: return@Column,
                            realtime = realtime,
                            onOpenChat = { screen = Screen.Chat(it) },
                            onOpenEveryone = { screen = Screen.Everyone },
                            onOpenSquad = { sq -> screen = Screen.SquadChat(sq, Screen.Inbox) },
                            onOpenProfile = { m -> screen = Screen.Profile(m.id, Screen.Inbox) },
                            onBack = { screen = Screen.Tabs }
                        )
                        is Screen.Everyone -> EveryoneScreen(
                            session = s,
                            onBack = { screen = Screen.Inbox }
                        )
                        is Screen.Notifications -> NotificationsScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs },
                            onOpenEveryone = { screen = Screen.Everyone },
                            onOpenInbox = { screen = Screen.Inbox },
                            onOpenPolls = { screen = Screen.Polls },
                            onOpenProfile = { m ->
                                screen = Screen.Profile(m.id, Screen.Notifications)
                            }
                        )
                        is Screen.Feed -> FeedScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs },
                            onOpenProfile = { id ->
                                screen = Screen.Profile(id, Screen.Feed)
                            }
                        )
                        is Screen.Polls -> PollsScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs }
                        )
                        is Screen.Console -> PresidentConsoleScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs },
                            onOpenProfile = { id ->
                                screen = Screen.Profile(id, Screen.Console)
                            }
                        )
                        is Screen.Search -> MemberSearchScreen(
                            session = s,
                            onBack = { screen = Screen.Tabs },
                            onOpenProfile = { id ->
                                screen = Screen.Profile(id, Screen.Search)
                            }
                        )
                        is Screen.PlayerCard -> PlayerCardScreen(
                            session = s, memberId = scr.memberId,
                            onBack = { screen = scr.backTo }
                        )
                        is Screen.Profile -> MemberProfileScreen(
                            session = s, me = myProfile ?: return@Column,
                            memberId = scr.memberId,
                            onBack = { screen = scr.backTo },
                            onMessage = { m -> screen = Screen.Chat(m) },
                            onOpenProfile = { fm ->
                                screen = Screen.Profile(fm.id, scr)
                            },
                            onOpenSquad = { sq ->
                                screen = Screen.SquadDetails(sq, scr)
                            },
                            onOpenPlayerCard = { pm ->
                                screen = Screen.PlayerCard(pm.id, scr)
                            },
                            onCall = { m, k -> callController?.startCall(m, k) },
                            onEdit = { screen = Screen.ProfileEdit },
                            onMeChanged = {
                                scope.launch {
                                    me = runCatching { ClubApi.ensureMember(s) }.getOrNull() ?: me
                                }
                            }
                        )
                        is Screen.ProfileEdit -> {
                            val myId = (myProfile ?: return@Column).id
                            EditProfileScreen(
                                session = s, me = myProfile ?: return@Column,
                                onBack = { screen = Screen.Profile(myId, Screen.Tabs) },
                                onSaved = {
                                    scope.launch {
                                        me = runCatching { ClubApi.ensureMember(s) }.getOrNull() ?: me
                                        val mid = me?.id
                                        screen = if (mid != null) {
                                            Screen.Profile(mid, Screen.Tabs)
                                        } else Screen.Tabs
                                    }
                                }
                            )
                        }
                        is Screen.ChatDetails -> ChatDetailsScreen(
                            session = s, me = myProfile ?: return@Column,
                            partner = scr.partner,
                            onBack = { screen = Screen.Chat(scr.partner) },
                            onOpenProfile = {
                                screen = Screen.Profile(scr.partner.id, scr)
                            },
                            onCall = { m, k -> callController?.startCall(m, k) },
                            onHidden = { screen = Screen.Inbox }
                        )
                        is Screen.Squads -> SquadsScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs },
                            onCreate = { screen = Screen.CreateSquad },
                            onOpenSquad = { sq -> screen = Screen.SquadDetails(sq, Screen.Squads) },
                            onAutoOpenSquad = { sq -> screen = Screen.SquadDetails(sq, Screen.Tabs) }
                        )
                        is Screen.AllSquads -> AllSquadsScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Tabs },
                            onOpenSquad = { sq -> screen = Screen.SquadDetails(sq, Screen.AllSquads) }
                        )
                        is Screen.CreateSquad -> CreateSquadScreen(
                            session = s, me = myProfile ?: return@Column,
                            onBack = { screen = Screen.Squads },
                            onCreated = { sq -> screen = Screen.SquadDetails(sq, Screen.Squads) }
                        )
                        is Screen.SquadChat -> SquadChatScreen(
                            session = s, me = myProfile ?: return@Column,
                            squad = scr.squad,
                            realtime = realtime,
                            onBack = { screen = scr.backTo },
                            onOpenDetails = { screen = Screen.SquadDetails(scr.squad, scr) }
                        )
                        is Screen.SquadDetails -> SquadDetailsScreen(
                            session = s, me = myProfile ?: return@Column,
                            squad = scr.squad,
                            onBack = { screen = scr.backTo },
                            onGone = { screen = Screen.Squads },
                            onOpenChat = { screen = Screen.SquadChat(scr.squad, scr) },
                            onOpenProfile = { id -> screen = Screen.Profile(id, scr) }
                        )
                        is Screen.WebPage -> Column(Modifier.fillMaxSize()) {
                            // hybrid pages keep the APP header (logo, club
                            // name, bell, envelope) — Pranto, 2026-11-02;
                            // the Android Back button returns to the tabs
                            ClubHeader(unreadTotal, notifUnread, onSearchClick = { screen = Screen.Search }, onBellClick = { screen = Screen.Notifications }) { screen = Screen.Inbox }
                            WebPageView(
                                scr.url, Modifier.weight(1f).fillMaxWidth(),
                                bridgeSession = s, poolKey = "page-" + scr.url
                            )
                            // Hybrid pages keep the bottom tab bar too
                            // (Pranto, 2026-11-02: it vanished on Community
                            // and Leaderboard opened from More). Tapping a
                            // tab leaves the web page for that tab.
                            GlassTabBar(tab) { chosen ->
                                tab = chosen
                                screen = Screen.Tabs
                            }
                        }
                        is Screen.Tabs -> {
                            ClubHeader(unreadTotal, notifUnread, onSearchClick = { screen = Screen.Search }, onBellClick = { screen = Screen.Notifications }) { screen = Screen.Inbox }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    Tab.Home -> HomeScreen(
                                        onOpenEvents = { tab = Tab.Events },
                                        onOpenNews = { tab = Tab.News },
                                        onOpenInbox = { screen = Screen.Inbox },
                                        onOpenSquads = { screen = Screen.Squads },
                                        onOpenMaker = {
                                            screen = Screen.WebPage(
                                                "https://knightwarriorsclub.com/#maker",
                                                "Tournament Maker"
                                            )
                                        },
                                        onOpenLeaderboard = {
                                            screen = Screen.WebPage(
                                                "https://knightwarriorsclub.com/#leaderboard",
                                                "Leaderboard"
                                            )
                                        }
                                    )
                                    // hybrid (Pranto, 2026-11-02): News + Events are
                                    // website pages shown inside the app
                                    Tab.News -> WebPageView(
                                        "https://knightwarriorsclub.com/#news",
                                        bridgeSession = s, poolKey = "tab-news"
                                    )
                                    Tab.Events -> WebPageView(
                                        "https://knightwarriorsclub.com/#events",
                                        bridgeSession = s, poolKey = "tab-events"
                                    )
                                    Tab.More -> MorePanel(
                                        me = myProfile,
                                        onOpenProfile = { mid ->
                                            screen = Screen.Profile(mid, Screen.Tabs)
                                        },
                                        onOpenSquads = { screen = Screen.Squads },
                                        onOpenAllSquads = { screen = Screen.AllSquads },
                                        onOpenFeed = { screen = Screen.Feed },
                                        onOpenPolls = { screen = Screen.Polls },
                                        onOpenConsole = { screen = Screen.Console },
                                        onOpenWeb = { url, title ->
                                            screen = Screen.WebPage(url, title)
                                        },
                                        hasCustomBg = customBg != null,
                                        onPickBackground = {
                                            bgPicker.launch(
                                                PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                        onResetBackground = {
                                            BackgroundStore.clear(appContext)
                                            bgVersion++
                                        },
                                        onLogout = {
                                            store.clear()
                                            // also sign the website (WebView) out:
                                            // the bridged session lives in its storage
                                            android.webkit.WebStorage.getInstance().deleteAllData()
                                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                            session = null
                                            me = null
                                            screen = Screen.Tabs
                                            tab = Tab.Home
                                            authView = "login"
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

        // beta49: in-app update notice — the app checks the club
        // website's data/app-version.json once per sign-in; a newer
        // build offers a download, and "Later" silences that version.
        var appUpdate by remember { mutableStateOf<ClubApi.AppUpdate?>(null) }
        LaunchedEffect(session) {
            if (session != null) {
                val latest = ClubApi.latestAppVersion()
                if (latest != null) {
                    val current = runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).longVersionCode
                    }.getOrDefault(0L)
                    if (latest.versionCode.toLong() > current &&
                        latest.versionCode != AppPrefs.dismissedUpdateCode(context)
                    ) {
                        appUpdate = latest
                    }
                }
            }
        }

        // live DM banner (beta19): drops in over any screen but calls
        val bannerNow = dmBanner
        if (session != null && bannerNow != null && callController?.uiState?.phase == CallPhase.IDLE) {
            DmBanner(
                sender = bannerNow.first,
                preview = bannerNow.second,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                onTap = {
                    dmBanner = null
                    screen = Screen.Chat(bannerNow.first)
                },
                onDismiss = { dmBanner = null }
            )
        }

        // the call overlay floats above everything while a call is active
        val cc = callController
        if (cc != null && cc.uiState.phase != CallPhase.IDLE) {
            CallOverlay(cc)
        }

        appUpdate?.let { upd ->
            AlertDialog(
                onDismissRequest = {
                    AppPrefs.setDismissedUpdateCode(context, upd.versionCode)
                    appUpdate = null
                },
                title = { Text("Update available") },
                text = {
                    Text(
                        "Knight Warriors Club v${upd.versionName} is ready." +
                            if (upd.notes.isNotBlank()) "\n\n${upd.notes}" else ""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(upd.apkUrl)
                                )
                            )
                        }
                        appUpdate = null
                    }) { Text("UPDATE") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        AppPrefs.setDismissedUpdateCode(context, upd.versionCode)
                        appUpdate = null
                    }) { Text("Later") }
                }
            )
        }
    }
}

/** WhatsApp-style in-app banner for a DM arriving while the app is open. */
@Composable
private fun DmBanner(
    sender: Member,
    preview: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier.padding(horizontal = 12.dp).padding(top = 10.dp)) {
        // Frosted, more solid than the standard glass card (Pranto,
        // 2026-11-02: "more blur shape") — deep-tinted fill, bright rim,
        // wide soft corners, floating shadow.
        Box(
            Modifier.fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xF2243247), Color(0xF2141E2C))
                    )
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .clickable { onTap() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = sender.avatarUrl, name = sender.fullName, size = 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        sender.fullName, color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        preview, color = Color(0xFFC4CEDC), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Dismiss",
                        tint = Color(0xFFC4CEDC), modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubHeader(
    unreadTotal: Int, notifUnread: Int = 0,
    onSearchClick: () -> Unit = {},
    onBellClick: () -> Unit = {},
    onEnvelopeClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // the real club crest, round beside the club name (Pranto, 2026-11-01)
        Image(
            painter = painterResource(R.drawable.club_logo),
            contentDescription = "Knight Warriors Club logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .border(1.5.dp, Gold, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("KNIGHT WARRIORS", color = GoldLight, fontFamily = BrandFont, fontSize = 18.sp)
            Text("EST. 2023 • ESPORTS CLUB", color = Color(0xFFC4CEDC), fontSize = 9.sp)
        }
        // beta39 (Pranto): search beside the bell.
        Box(Modifier.clickable { onSearchClick() }) {
            Icon(
                Icons.Filled.Search, contentDescription = "Search members",
                tint = Color.White, modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        // bell LEFT, envelope RIGHT — Pranto's header order.
        // beta37: the bell now opens the Notifications page and carries
        // its own unread badge (it was decorative before — Pranto).
        Box(Modifier.clickable { onBellClick() }) {
            Icon(
                Icons.Filled.Notifications, contentDescription = "Notifications",
                tint = Color.White, modifier = Modifier.size(26.dp)
            )
            if (notifUnread > 0) {
                Box(
                    Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                        .clip(CircleShape).background(Color(0xFFE23B3B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (notifUnread > 9) "9+" else "$notifUnread",
                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
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
private fun QuickTile(
    icon: ImageVector, label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(GlassFill)
            .border(1.dp, GlassRim, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = GoldLight, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Home — redesigned 2026-11-02 (Pranto: duplicate doors "look idiotic").
 * One element, one job: hero = identity (no button); ONE featured
 * tournament card (live from the website's tournaments.json — a newly
 * published tournament appears here with no app update); Quick Actions
 * = four DIFFERENT doors; Latest News = the newest announcement
 * headline, also live; About + Games = plain information.
 */
@Composable
private fun HomeScreen(
    onOpenEvents: () -> Unit = {},
    onOpenNews: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    onOpenSquads: () -> Unit = {},
    onOpenMaker: () -> Unit = {},
    onOpenLeaderboard: () -> Unit = {}
) {
    var featured by remember { mutableStateOf<ClubApi.FeaturedTournament?>(null) }
    var newsTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        featured = ClubApi.featuredTournament()
        newsTitle = ClubApi.latestAnnouncementTitle()
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                // beta39 (Pranto): full name on ONE line, motto under
                // it, small line kept as it was.
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append("KNIGHT WARRIORS ") }
                        withStyle(SpanStyle(color = GoldLight)) { append("CLUB") }
                    },
                    fontWeight = FontWeight.Bold, fontSize = 23.sp, maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Play \u2022 Compete \u2022 Conquer",
                    color = GoldLight, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(2.dp))
                Text("Play together. Rise together.", color = Color(0xFFFFF4E4), fontSize = 13.sp)
            }
        }
        featured?.let { t ->
            item {
                Text("FEATURED TOURNAMENT", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GlassCard(
                    Modifier.fillMaxWidth().clickable { onOpenEvents() },
                    fill = Color(0x47FFBE28)
                ) {
                    val running = t.status == "running" || t.status == "live"
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (running) Color(0xFF1E7A34) else Gold)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (running) "● RUNNING" else "REGISTRATION OPEN",
                            color = if (running) Color.White else Ink,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "♛  ${t.name}", color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                            Text(
                                listOfNotNull(
                                    t.game.takeIf { it.isNotBlank() },
                                    t.format?.replaceFirstChar { c -> c.uppercase() }
                                ).joinToString(" • ") +
                                        if (running) " • Live now" else " • Tap to join",
                                color = Color(0xFFFFF0C8), fontSize = 12.sp
                            )
                        }
                        Text("→", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }
        }
        item {
            Text("QUICK ACTIONS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Filled.Email, "Inbox", Modifier.weight(1f)) { onOpenInbox() }
                QuickTile(Icons.Filled.Groups, "My Squads", Modifier.weight(1f)) { onOpenSquads() }
                QuickTile(Icons.Filled.Edit, "Maker", Modifier.weight(1f)) { onOpenMaker() }
                QuickTile(Icons.Filled.EmojiEvents, "Rankings", Modifier.weight(1f)) { onOpenLeaderboard() }
            }
        }
        item {
            Text("LATEST NEWS", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth().clickable { onOpenNews() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            newsTitle ?: "Club News & Announcements",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Text(
                            "Tap to read the club news", color = Color(0xFFE8EDF4), fontSize = 12.sp
                        )
                    }
                    Text("→", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
private fun MorePanel(
    me: Member?,
    onOpenProfile: (Long) -> Unit = {},
    onOpenSquads: () -> Unit = {},
    onOpenAllSquads: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenPolls: () -> Unit = {},
    onOpenConsole: () -> Unit = {},
    onOpenWeb: (String, String) -> Unit = { _, _ -> },
    hasCustomBg: Boolean,
    onPickBackground: () -> Unit,
    onResetBackground: () -> Unit,
    onLogout: () -> Unit
) {
    var section by remember { mutableStateOf<String?>(null) }
    // keep the list's scroll position while Settings/About are open —
    // the state must live HERE, not inside the list, or going Back
    // rebuilds the list from the top (Pranto, 2026-11-02)
    val moreListState = rememberLazyListState()
    BackHandler(enabled = section != null) { section = null }
    when (section) {
        "About the Club" -> {
            AboutScreen { section = null }
            return
        }
        "Settings" -> {
            SettingsScreen(hasCustomBg, onPickBackground, onResetBackground) { section = null }
            return
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        state = moreListState,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTitle("More", "Everything else in the club") }
        item {
            GlassCard(Modifier.fillMaxWidth().clickable(enabled = me != null) {
                me?.let { onOpenProfile(it.id) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (me != null) AvatarImage(url = me.avatarUrl, name = me.fullName, size = 46)
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
            "Club Feed" to "Club posts from members",
            "Polls & MVP" to "Vote in club polls & MVP",
            "My Squads" to "Your teams",
            "All Squads" to "Every squad in the club — President",
            "President Console" to "Members, entries, reports — President",
            "Community" to "Meet the committee",
            "Leaderboard" to "Club rankings",
            "Tournaments" to "All club tournaments",
            "Tournament Maker" to "Build your own tournament",
            "About the Club" to "Since 2023",
            "Settings" to "Background, alerts, data"
        ).filter { (t, _) ->
            // All Squads is the President's window on the whole club
            (t != "All Squads" && t != "President Console") ||
                (me?.role?.equals("President", ignoreCase = true) == true)
        }.forEach { (t, s) ->
            item {
                GlassCard(Modifier.fillMaxWidth().clickable {
                    when (t) {
                        "About the Club", "Settings" -> section = t
                        "Profile" -> me?.let { onOpenProfile(it.id) }
                        "Community" -> onOpenWeb(
                            "https://knightwarriorsclub.com/#community", "Community"
                        )
                        "My Squads" -> onOpenSquads()
                        "All Squads" -> onOpenAllSquads()
                        "Club Feed" -> onOpenFeed()
                        "Polls & MVP" -> onOpenPolls()
                        "President Console" -> onOpenConsole()
                        "Leaderboard" -> onOpenWeb(
                            "https://knightwarriorsclub.com/#leaderboard", "Leaderboard"
                        )
                        "Tournaments" -> onOpenWeb(
                            "https://knightwarriorsclub.com/#events", "Tournaments"
                        )
                        "Tournament Maker" -> onOpenWeb(
                            "https://knightwarriorsclub.com/#maker", "Tournament Maker"
                        )
                    }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoreThumbArt(moreThumb(t))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(s, color = Color(0xFFC4CEDC), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().clickable { onLogout() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoreThumbArt(R.drawable.thumb_logout)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Log out", color = Color(0xFFFF9B9B), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Sign out of this device", color = Color(0xFFC4CEDC), fontSize = 11.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** beta50 (Pranto): every More row carries a small circular club-art
 *  thumbnail beside its name. The art ships inside the APK (66 KB for
 *  the whole set), generated in the club's teal-and-gold night style. */
private fun moreThumb(title: String): Int = when (title) {
    "Club Feed" -> R.drawable.thumb_feed
    "Polls & MVP" -> R.drawable.thumb_polls
    "My Squads" -> R.drawable.thumb_mysquads
    "All Squads" -> R.drawable.thumb_allsquads
    "President Console" -> R.drawable.thumb_console
    "Community" -> R.drawable.thumb_community
    "Leaderboard" -> R.drawable.thumb_leaderboard
    "Tournaments" -> R.drawable.thumb_tournaments
    "Tournament Maker" -> R.drawable.thumb_maker
    "About the Club" -> R.drawable.thumb_about
    "Settings" -> R.drawable.thumb_settings
    else -> R.drawable.club_logo
}

@Composable
private fun MoreThumbArt(resId: Int) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .border(1.5.dp, GoldLight, CircleShape)
    )
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
