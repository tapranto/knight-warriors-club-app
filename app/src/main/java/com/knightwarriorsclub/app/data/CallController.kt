package com.knightwarriorsclub.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.random.Random

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, IN_CALL, ENDED }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val partnerName: String = "",
    val partnerId: Long = 0L,
    val kind: String = "voice",
    val statusText: String = "",
    val durationSec: Int = 0,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val videoOn: Boolean = true
)

/**
 * 1-to-1 voice/video calls — a native port of the website's Layer 7, wire
 * compatible with it: same Realtime broadcast channels, event names and
 * payloads, same ICE servers, same ring timeouts, and the caller writes
 * the same "📞 …" chat-log row. App-only win: real earpiece routing.
 *
 * Channels:
 *   kwc-call-user-<memberId>  personal — 'invite' in; 'declined'/'busy'/'cancel' back
 *   kwc-call-<callId>         per-call — 'ready', 'offer', 'answer', 'ice', 'bye'
 */
class CallController(private val context: Context) {

    companion object {
        private const val RING_MS = 30000L        // caller rings this long (web parity)
        private const val CALLEE_RING_MS = 40000L // callee-side stale-ring safety (web parity)
        private const val DISCONNECT_GRACE_MS = 6000L
        private const val CONNECT_MS = 20000L      // max time in Connecting… before failing the call
        private val ICE_SERVERS = listOf(
            "stun:stun.l.google.com:19302" to null,
            "stun:stun1.l.google.com:19302" to null,
            "turn:openrelay.metered.ca:80" to "openrelayproject",
            "turn:openrelay.metered.ca:443" to "openrelayproject",
            "turn:openrelay.metered.ca:443?transport=tcp" to "openrelayproject"
        )
    }

    var uiState by mutableStateOf(CallUiState())
        private set

    /** Set by the activity: (permissions, callback(granted)) — runs the system prompt. */
    var permissionRequester: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var session: Session? = null
    private var me: Member? = null
    private var rt: RealtimeClient? = null
    private var scope: CoroutineScope? = null

    // active call state (mirrors the website's `call` object)
    private var callId: String? = null
    private var role: String = "" // "caller" | "callee"
    private var partner: Member? = null
    private var kind: String = "voice"
    private var answered = false
    private var startedAt = 0L
    private var logged = false

    private var pc: PeerConnection? = null
    private val iceQueue = mutableListOf<IceCandidate>()
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var factory: PeerConnectionFactory? = null
    val eglBase: EglBase = EglBase.create()

    private var toneGen: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ringLoop: Runnable? = null
    private var ringTimer: Runnable? = null
    private var discTimer: Runnable? = null
    private var durationTimer: Runnable? = null
    private var connectTimer: Runnable? = null

    // ── lifecycle ────────────────────────────────────────────────────────

    fun start(session: Session, me: Member, rt: RealtimeClient, scope: CoroutineScope) {
        this.session = session
        this.me = me
        this.rt = rt
        this.scope = scope
        rt.joinBroadcast("kwc-call-user-${me.id}", { event, payload ->
            when (event) {
                "invite" -> onInvite(payload)
                "declined" -> onPersonalEnd(payload, "declined")
                "busy" -> onPersonalEnd(payload, "busy")
                "cancel" -> onCancel(payload)
            }
        })
    }

    fun stop() {
        endCallInternal("gone", silent = true, fromRemote = true)
        rt?.leave("kwc-call-user-${me?.id}")
        session = null
        me = null
        rt = null
        scope = null
    }

    fun bindLocalRenderer(r: SurfaceViewRenderer?) {
        localRenderer = r
        if (r != null) localVideo?.addSink(r)
    }

    fun bindRemoteRenderer(r: SurfaceViewRenderer?) {
        remoteRenderer = r
        if (r != null) remoteVideo?.addSink(r)
    }

    // ── outgoing ─────────────────────────────────────────────────────────

    fun startCall(partnerMember: Member, callKind: String) {
        if (uiState.phase != CallPhase.IDLE) return
        val needed = if (callKind == "video")
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        withPermissions(needed) { granted ->
            if (granted) beginOutgoing(partnerMember, callKind)
            else endWithMessage("Microphone/camera permission is needed to call.")
        }
    }

    private fun beginOutgoing(partnerMember: Member, callKind: String) {
        val my = me ?: return
        partner = partnerMember
        kind = callKind
        role = "caller"
        answered = false
        logged = false
        iceQueue.clear()
        callId = newCallId()
        if (!initMedia(callKind)) {
            endWithMessage("No usable microphone/camera was found for this call.")
            return
        }
        uiState = CallUiState(
            phase = CallPhase.OUTGOING,
            partnerName = partnerMember.fullName,
            partnerId = partnerMember.id,
            kind = callKind,
            statusText = "Calling…",
            speakerOn = callKind == "video"
        )
        startAudioRouting()
        startRingback()
        val id = callId!!
        rt?.joinBroadcast("kwc-call-$id", { event, payload -> onCallEvent(event, payload) }) {
            // channel live → deliver the invite on the partner's personal channel
            rt?.joinBroadcast("kwc-call-user-${partnerMember.id}", { _, _ -> }) {
                rt?.broadcast(
                    "kwc-call-user-${partnerMember.id}", "invite",
                    buildJsonObject {
                        put("callId", id)
                        put("kind", callKind)
                        put("from", buildJsonObject {
                            put("id", my.id)
                            put("name", my.fullName)
                            put("avatar_url", my.avatarUrl ?: "")
                        })
                    }
                )
            }
            ringTimer = Runnable { if (!answered) endCallInternal("timeout") }
            main.postDelayed(ringTimer!!, RING_MS)
        }
    }

    // ── incoming ─────────────────────────────────────────────────────────

    private fun onInvite(payload: JsonObject) {
        val fromObj = payload["from"]?.jsonObject ?: return
        val fromId = fromObj["id"]?.jsonPrimitive?.longOrNull ?: return
        val incCallId = payload["callId"]?.jsonPrimitive?.contentOrNull ?: return
        val incKind =
            if (payload["kind"]?.jsonPrimitive?.contentOrNull == "video") "video" else "voice"
        if (uiState.phase != CallPhase.IDLE) {
            sendPersonal(fromId, "busy", incCallId)
            return
        }
        callId = incCallId
        role = "callee"
        kind = incKind
        answered = false
        logged = false
        iceQueue.clear()
        partner = Member(
            id = fromId,
            fullName = fromObj["name"]?.jsonPrimitive?.contentOrNull ?: "Member",
            username = null,
            avatarUrl = fromObj["avatar_url"]?.jsonPrimitive?.contentOrNull
        )
        uiState = CallUiState(
            phase = CallPhase.INCOMING,
            partnerName = partner!!.fullName,
            partnerId = fromId,
            kind = incKind,
            statusText = if (incKind == "video") "Incoming video call…" else "Incoming voice call…",
            speakerOn = incKind == "video"
        )
        startRingtone()
        ringTimer = Runnable {
            if (uiState.phase == CallPhase.INCOMING) endCallInternal("gone", silent = true)
        }
        main.postDelayed(ringTimer!!, CALLEE_RING_MS)
    }

    fun accept() {
        if (uiState.phase != CallPhase.INCOMING) return
        val needed = if (kind == "video")
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        withPermissions(needed) { granted ->
            val p = partner ?: return@withPermissions
            if (!granted) {
                sendPersonal(p.id, "declined", callId ?: "")
                endCallInternal("gone", silent = true)
                return@withPermissions
            }
            if (!initMedia(kind)) {
                sendPersonal(p.id, "declined", callId ?: "")
                endCallInternal("gone", silent = true)
                return@withPermissions
            }
            stopRing()
            startAudioRouting()
            uiState = uiState.copy(phase = CallPhase.CONNECTING, statusText = "Connecting…")
            armConnectWatchdog()
            val id = callId ?: return@withPermissions
            rt?.joinBroadcast("kwc-call-$id", { event, payload -> onCallEvent(event, payload) }) {
                rt?.broadcast("kwc-call-$id", "ready", buildJsonObject {})
            }
        }
    }

    fun decline() {
        if (uiState.phase != CallPhase.INCOMING) return
        partner?.let { sendPersonal(it.id, "declined", callId ?: "") }
        endCallInternal("gone", silent = true)
    }

    // ── in-call controls ─────────────────────────────────────────────────

    fun hangup() {
        when (uiState.phase) {
            CallPhase.OUTGOING -> endCallInternal("cancelled")
            CallPhase.INCOMING -> decline()
            CallPhase.CONNECTING, CallPhase.IN_CALL -> endCallInternal("bye")
            else -> {}
        }
    }

    fun toggleMute() {
        val newMuted = !uiState.muted
        localAudio?.setEnabled(!newMuted)
        uiState = uiState.copy(muted = newMuted)
    }

    fun toggleSpeaker() {
        val on = !uiState.speakerOn
        uiState = uiState.copy(speakerOn = on)
        audioManager.isSpeakerphoneOn = on
    }

    fun toggleVideo() {
        val on = !uiState.videoOn
        localVideo?.setEnabled(on)
        uiState = uiState.copy(videoOn = on)
    }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {}
                override fun onCameraSwitchError(errorDescription: String) {}
            }
        )
    }

    fun dismissEnded() {
        if (uiState.phase == CallPhase.ENDED) uiState = CallUiState()
    }

    // ── signalling events ────────────────────────────────────────────────

    private fun onCallEvent(event: String, payload: JsonObject) {
        when (event) {
            "ready" -> if (role == "caller") callerOnReady()
            "offer" -> if (role == "callee") calleeOnOffer(payload)
            "answer" -> if (role == "caller") onAnswer(payload)
            "ice" -> onRemoteIce(payload)
            "bye" -> endCallInternal("remote", fromRemote = true)
        }
    }

    /** Connecting… must never hang forever (Pranto, 2026-11-02): if the
     *  voice path has not opened in CONNECT_MS, fail the call cleanly. */
    private fun armConnectWatchdog() {
        connectTimer?.let { main.removeCallbacks(it) }
        connectTimer = Runnable {
            if (uiState.phase == CallPhase.CONNECTING) endCallInternal("connect-failed")
        }
        main.postDelayed(connectTimer!!, CONNECT_MS)
    }

    private fun callerOnReady() {
        if (pc != null) return
        uiState = uiState.copy(phase = CallPhase.CONNECTING, statusText = "Connecting…")
        armConnectWatchdog()
        val conn = buildPeerConnection() ?: return
        conn.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                conn.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val local = conn.localDescription ?: return
                        sendSdp("offer", local.type.canonicalForm(), local.description)
                    }

                    override fun onCreateSuccess(d: SessionDescription) {}
                    override fun onCreateFailure(e: String) {}
                    override fun onSetFailure(e: String) {}
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) {
                endCallInternal("failed")
            }

            override fun onSetFailure(e: String) {}
        }, MediaConstraints())
    }

    private fun calleeOnOffer(payload: JsonObject) {
        val sdpObj = payload["sdp"]?.jsonObject ?: return
        val type = sdpObj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val sdp = sdpObj["sdp"]?.jsonPrimitive?.contentOrNull ?: return
        val conn = buildPeerConnection() ?: return
        conn.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushIce(conn)
                conn.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        conn.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val local = conn.localDescription ?: return
                                sendSdp("answer", local.type.canonicalForm(), local.description)
                            }

                            override fun onCreateSuccess(d: SessionDescription) {}
                            override fun onCreateFailure(e: String) {}
                            override fun onSetFailure(e: String) {}
                        }, desc)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String) {}
                    override fun onSetFailure(e: String) {}
                }, MediaConstraints())
            }

            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {
                endCallInternal("failed")
            }

            override fun onSetFailure(e: String) {}
        }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
    }

    private fun onAnswer(payload: JsonObject) {
        val conn = pc ?: return
        val sdpObj = payload["sdp"]?.jsonObject ?: return
        val type = sdpObj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val sdp = sdpObj["sdp"]?.jsonPrimitive?.contentOrNull ?: return
        conn.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushIce(conn)
            }

            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
            override fun onSetFailure(e: String) {}
        }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
    }

    private fun onRemoteIce(payload: JsonObject) {
        val cand = payload["candidate"]?.jsonObject ?: return
        val sdp = cand["candidate"]?.jsonPrimitive?.contentOrNull ?: return
        val mid = cand["sdpMid"]?.jsonPrimitive?.contentOrNull ?: ""
        val idx = cand["sdpMLineIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val ice = IceCandidate(mid, idx, sdp)
        val conn = pc
        if (conn != null && conn.remoteDescription != null) {
            runCatching { conn.addIceCandidate(ice) }
        } else {
            iceQueue.add(ice)
        }
    }

    private fun flushIce(conn: PeerConnection) {
        val queued = iceQueue.toList()
        iceQueue.clear()
        queued.forEach { runCatching { conn.addIceCandidate(it) } }
    }

    private fun onPersonalEnd(payload: JsonObject, reason: String) {
        val endedId = payload["callId"]?.jsonPrimitive?.contentOrNull ?: return
        if (role == "caller" && endedId == callId && !answered) {
            endCallInternal(reason)
        }
    }

    private fun onCancel(payload: JsonObject) {
        val endedId = payload["callId"]?.jsonPrimitive?.contentOrNull ?: return
        if (role == "callee" && endedId == callId && uiState.phase == CallPhase.INCOMING) {
            endCallInternal("gone", silent = true)
        }
    }

    private fun sendPersonal(memberId: Long, event: String, forCallId: String) {
        val my = me ?: return
        if (memberId == my.id) return
        val name = "kwc-call-user-$memberId"
        rt?.joinBroadcast(name, { _, _ -> }) {
            rt?.broadcast(
                name, event,
                buildJsonObject { put("callId", forCallId) }
            )
            // keep the tx channel briefly, like the website's 1.5 s pool
            main.postDelayed({ if (uiState.partnerId != memberId) rt?.leave(name) }, 1500)
        }
    }

    private fun sendSdp(event: String, type: String, sdp: String) {
        val id = callId ?: return
        rt?.broadcast(
            "kwc-call-$id", event,
            buildJsonObject {
                put("sdp", buildJsonObject {
                    put("type", type)
                    put("sdp", sdp)
                })
            }
        )
    }

    // ── WebRTC plumbing ──────────────────────────────────────────────────

    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        return runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            adm.release()
            factory = f
            f
        }.getOrNull()
    }

    private fun initMedia(callKind: String): Boolean {
        val f = ensureFactory() ?: return false
        return runCatching {
            audioSource = f.createAudioSource(MediaConstraints())
            localAudio = f.createAudioTrack("kwc-audio", audioSource)
            if (callKind == "video") {
                val enumerator = Camera2Enumerator(context)
                val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                    ?: return false
                val cap = enumerator.createCapturer(device, null) ?: return false
                capturer = cap
                surfaceHelper = SurfaceTextureHelper.create("kwc-capture", eglBase.eglBaseContext)
                videoSource = f.createVideoSource(false)
                cap.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
                cap.startCapture(960, 720, 30)
                localVideo = f.createVideoTrack("kwc-video", videoSource)
                localRenderer?.let { localVideo?.addSink(it) }
            }
            true
        }.getOrDefault(false)
    }

    private fun buildPeerConnection(): PeerConnection? {
        val f = ensureFactory() ?: return null
        val iceServers = ICE_SERVERS.map { (url, user) ->
            val b = PeerConnection.IceServer.builder(url)
            if (user != null) {
                b.setUsername(user)
                b.setPassword(user)
            }
            b.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        val conn = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val id = callId ?: return
                rt?.broadcast(
                    "kwc-call-$id", "ice",
                    buildJsonObject {
                        put("candidate", buildJsonObject {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid ?: "")
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    }
                )
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                main.post { onConnectionState(newState) }
            }

            override fun onAddTrack(
                receiver: RtpReceiver,
                mediaStreams: Array<out MediaStream>
            ) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    remoteVideo = track
                    main.post { remoteRenderer?.let { track.addSink(it) } }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        pc = conn
        localAudio?.let { conn.addTrack(it, listOf("kwc-stream")) }
        localVideo?.let { conn.addTrack(it, listOf("kwc-stream")) }
        return conn
    }

    private fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                discTimer?.let { main.removeCallbacks(it) }
                discTimer = null
                connectTimer?.let { main.removeCallbacks(it) }
                connectTimer = null
                if (!answered) {
                    answered = true
                    startedAt = System.currentTimeMillis()
                    stopRing()
                    uiState = uiState.copy(
                        phase = CallPhase.IN_CALL, statusText = ""
                    )
                    durationTimer = object : Runnable {
                        override fun run() {
                            if (uiState.phase == CallPhase.IN_CALL) {
                                uiState = uiState.copy(
                                    durationSec =
                                        ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                                )
                                main.postDelayed(this, 1000)
                            }
                        }
                    }
                    main.postDelayed(durationTimer!!, 1000)
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> endCallInternal("failed")
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                if (discTimer == null) {
                    discTimer = Runnable { endCallInternal("failed") }
                    main.postDelayed(discTimer!!, DISCONNECT_GRACE_MS)
                }
            }
            else -> {}
        }
    }

    private fun withPermissions(needed: Array<String>, done: (Boolean) -> Unit) {
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            done(true)
            return
        }
        val requester = permissionRequester
        if (requester == null) {
            done(false)
            return
        }
        requester(missing.toTypedArray(), done)
    }

    private fun newCallId(): String {
        val t = System.currentTimeMillis().toString(36)
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val r = (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "$t-$r"
    }

    // ── ringing + audio routing ──────────────────────────────────────────

    private fun startRingback() {
        stopRing()
        val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
        toneGen = tg
        val loop = object : Runnable {
            override fun run() {
                runCatching { tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1800) }
                main.postDelayed(this, 3800)
            }
        }
        ringLoop = loop
        loop.run()
    }

    private fun startRingtone() {
        stopRing()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val rtng = RingtoneManager.getRingtone(context, uri)
            ringtone = rtng
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rtng?.isLooping = true
            }
            rtng?.play()
        }
        runCatching {
            val vib = context.getSystemService(Vibrator::class.java)
            vibrator = vib
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), 0))
        }
    }

    private fun stopRing() {
        ringLoop?.let { main.removeCallbacks(it) }
        ringLoop = null
        runCatching { toneGen?.stopTone() }
        runCatching { toneGen?.release() }
        toneGen = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startAudioRouting() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = uiState.speakerOn
        }
    }

    private fun stopAudioRouting() {
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    // ── ending + the chat log (website wording, word for word) ──────────

    private fun fmtDur(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun logText(reason: String): String {
        val label = if (kind == "video") "Video" else "Voice"
        if (answered) return "📞 $label call · ${fmtDur(System.currentTimeMillis() - startedAt)}"
        if (reason == "declined") return "📞 Call declined"
        return "📞 Missed ${label.lowercase()} call"
    }

    private fun writeCallLog(reason: String) {
        if (role != "caller" || logged) return
        logged = true
        val s = session ?: return
        val my = me ?: return
        val p = partner ?: return
        val body = logText(reason)
        scope?.launch {
            runCatching { ClubApi.sendDm(s, my.id, p.id, body) }
        }
    }

    private fun endWithMessage(message: String) {
        callId = null
        role = ""
        partner = null
        uiState = CallUiState(phase = CallPhase.ENDED, statusText = message)
    }

    private fun endCallInternal(reason: String, silent: Boolean = false, fromRemote: Boolean = false) {
        if (callId == null && uiState.phase == CallPhase.IDLE) return
        val wasAnswered = answered
        val endingPartner = partner
        val endingKind = kind
        stopRing()
        ringTimer?.let { main.removeCallbacks(it) }
        ringTimer = null
        discTimer?.let { main.removeCallbacks(it) }
        discTimer = null
        connectTimer?.let { main.removeCallbacks(it) }
        connectTimer = null
        durationTimer?.let { main.removeCallbacks(it) }
        durationTimer = null
        if (!fromRemote) {
            val id = callId
            if (role == "caller" && reason == "cancelled" && !wasAnswered && id != null) {
                endingPartner?.let { sendPersonal(it.id, "cancel", id) }
            }
            if (id != null) {
                rt?.broadcast("kwc-call-$id", "bye", buildJsonObject {})
            }
        }
        writeCallLog(reason)
        cleanupMedia()
        callId?.let { rt?.leave("kwc-call-$it") }
        stopAudioRouting()
        callId = null
        val pName = endingPartner?.fullName ?: ""
        val pId = endingPartner?.id ?: 0L
        partner = null
        role = ""
        answered = false
        if (silent) {
            uiState = CallUiState()
        } else {
            val text = when {
                reason == "declined" -> "Call declined"
                reason == "busy" -> "$pName is busy"
                reason == "timeout" -> "No answer"
                reason == "connect-failed" -> "Couldn't connect — the network blocked the voice path"
                reason == "failed" -> "Call connection failed — check your connection and try again."
                wasAnswered -> "Call ended"
                else -> "Call ended"
            }
            uiState = CallUiState(
                phase = CallPhase.ENDED,
                partnerName = pName,
                partnerId = pId,
                kind = endingKind,
                statusText = text
            )
            main.postDelayed({
                if (uiState.phase == CallPhase.ENDED) uiState = CallUiState()
            }, 1800)
        }
    }

    private fun cleanupMedia() {
        runCatching { pc?.close() }
        pc = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { localVideo?.dispose() }
        localVideo = null
        runCatching { localAudio?.dispose() }
        localAudio = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        remoteVideo = null
        iceQueue.clear()
    }
}
