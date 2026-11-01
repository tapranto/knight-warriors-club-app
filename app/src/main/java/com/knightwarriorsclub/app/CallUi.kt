package com.knightwarriorsclub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.knightwarriorsclub.app.data.CallController
import com.knightwarriorsclub.app.data.CallPhase
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private fun fmtDuration(totalSec: Int): String =
    "%d:%02d".format(totalSec / 60, totalSec % 60)

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFFC4CEDC), fontSize = 10.sp)
    }
}

/**
 * Full-screen call overlay (incoming / outgoing / connecting / in-call /
 * ended), drawn above everything else while a call is active. Video uses
 * real WebRTC renderers; voice shows the member avatar.
 */
@Composable
fun CallOverlay(controller: CallController) {
    val state = controller.uiState
    val isVideo = state.kind == "video"

    // the ended card dismisses itself after a couple of seconds
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.ENDED) {
            delay(2400)
            controller.dismissEnded()
        }
    }

    DisposableEffect(controller, state.phase) {
        onDispose {
            controller.bindLocalRenderer(null)
            controller.bindRemoteRenderer(null)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xE6060B14)),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (isVideo && state.phase != CallPhase.ENDED) {
                Box(
                    Modifier.fillMaxWidth().height(340.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0C0C13))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(controller.eglBase.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                setEnableHardwareScaler(true)
                                controller.bindRemoteRenderer(this)
                            }
                        },
                        onRelease = {
                            controller.bindRemoteRenderer(null)
                            it.release()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(controller.eglBase.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                setMirror(true)
                                setEnableHardwareScaler(true)
                                controller.bindLocalRenderer(this)
                            }
                        },
                        onRelease = {
                            controller.bindLocalRenderer(null)
                            it.release()
                        },
                        modifier = Modifier.size(width = 92.dp, height = 122.dp)
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, GlassRim, RoundedCornerShape(10.dp))
                    )
                }
                Spacer(Modifier.height(14.dp))
            } else {
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    InitialAvatar(state.partnerName, 96)
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                state.partnerName, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 19.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                if (state.phase == CallPhase.IN_CALL) fmtDuration(state.durationSec)
                else state.statusText,
                color = Color(0xFFC4CEDC), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(20.dp))

            when (state.phase) {
                CallPhase.INCOMING -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallControlButton(
                            Icons.Filled.CallEnd, "Decline", Color(0xFFC8102E)
                        ) { controller.decline() }
                        CallControlButton(
                            Icons.Filled.Call, "Accept", Color(0xFF1FAA55)
                        ) { controller.accept() }
                    }
                }
                CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.IN_CALL -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top
                    ) {
                        CallControlButton(
                            if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            "Mute",
                            if (state.muted) Color(0xFFC8102E) else Color.White.copy(alpha = 0.16f)
                        ) { controller.toggleMute() }
                        CallControlButton(
                            Icons.Filled.VolumeUp,
                            "Speaker",
                            if (state.speakerOn) Color(0xFF1FAA55) else Color.White.copy(alpha = 0.16f)
                        ) { controller.toggleSpeaker() }
                        if (isVideo) {
                            CallControlButton(
                                if (state.videoOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                "Video",
                                if (state.videoOn) Color.White.copy(alpha = 0.16f) else Color(0xFFC8102E)
                            ) { controller.toggleVideo() }
                            CallControlButton(
                                Icons.Filled.Cameraswitch,
                                "Flip",
                                Color.White.copy(alpha = 0.16f)
                            ) { controller.switchCamera() }
                        }
                        CallControlButton(
                            Icons.Filled.CallEnd, "End", Color(0xFFC8102E)
                        ) { controller.hangup() }
                    }
                }
                CallPhase.ENDED -> {
                    Box(Modifier.align(Alignment.CenterHorizontally)) {
                        CallControlButton(
                            Icons.Filled.CallEnd, "Close", Color.White.copy(alpha = 0.16f)
                        ) { controller.dismissEnded() }
                    }
                }
                CallPhase.IDLE -> {}
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
