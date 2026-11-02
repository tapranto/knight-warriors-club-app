package com.knightwarriorsclub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Phase 3.1 — FCM call doorbell (Pranto, 2026-11-02).
 *
 * Calls ring while the app is open via Supabase Realtime. When the app is
 * closed, this service receives the data push the club's send-call-push
 * function sends, and posts a full-screen incoming-call notification —
 * the same trick WhatsApp uses. Tapping it (or the full-screen intent)
 * opens MainActivity with the call's details; MainActivity hands them to
 * the CallController, which joins the call channel and rings for real.
 *
 * Data payload keys (set by the Edge Function):
 *   type = "call", call_id, kind ("voice"/"video"),
 *   from_id, from_name, from_avatar
 */

data class PushInviteData(
    val callId: String,
    val fromId: Long,
    val fromName: String,
    val fromAvatar: String?,
    val kind: String
)

object PushStore {
    private const val PREFS = "kwc_push"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("fcm_token", null)

    fun saveCall(context: Context, callId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("ringing_call_id", callId).apply()
    }

    fun ringingCallId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("ringing_call_id", null)

    fun clearCall(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("ringing_call_id").apply()
    }
}

object PushNotifier {
    const val CHANNEL_ID = "kwc_calls"

    fun showCallNotification(context: Context, data: PushInviteData) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("kwc_call_id", data.callId)
            putExtra("kwc_from_id", data.fromId)
            putExtra("kwc_from_name", data.fromName)
            putExtra("kwc_from_avatar", data.fromAvatar)
            putExtra("kwc_kind", data.kind)
        }
        val pi = PendingIntent.getActivity(
            context, data.callId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(data.fromName)
            .setContentText(
                if (data.kind == "video") "Incoming video call…"
                else "Incoming voice call…"
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(35_000)
            .build()
        nm.notify(data.callId.hashCode(), notif)
        PushStore.saveCall(context, data.callId)
    }

    fun clearCallNotification(context: Context) {
        val callId = PushStore.ringingCallId(context) ?: return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(callId.hashCode())
        PushStore.clearCall(context)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming club calls"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                null
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 900, 500, 900, 500, 900)
        }
        nm.createNotificationChannel(channel)
    }
}

class KwcMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // ClubApp registers the token with Supabase at next sign-in/start;
        // it is also stored here immediately.
        PushStore.saveToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "call") return
        val callId = data["call_id"] ?: return
        val invite = PushInviteData(
            callId = callId,
            fromId = data["from_id"]?.toLongOrNull() ?: return,
            fromName = data["from_name"] ?: "Member",
            fromAvatar = data["from_avatar"]?.takeIf { it.isNotBlank() },
            kind = if (data["kind"] == "video") "video" else "voice"
        )
        PushNotifier.showCallNotification(this, invite)
    }
}
