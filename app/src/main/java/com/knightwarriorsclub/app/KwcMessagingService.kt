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
    const val ALERTS_CHANNEL_ID = "kwc_alerts"
    const val MESSAGES_CHANNEL_ID = "kwc_messages"

    fun ensureMessagesChannel(
        context: Context, nm: NotificationManager, id: String, label: String
    ) {
        if (Build.VERSION.SDK_INT < 26) return
        if (nm.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, label, NotificationManager.IMPORTANCE_HIGH).apply {
            description = label
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        when (message.data["type"]) {
            "call" -> onCallPush(message.data)
            "missed_call" -> onMissedPush(message.data)
            "message" -> onChatMessagePush(message.data)
        }
    }

    private fun onCallPush(data: Map<String, String>) {
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

    /** The caller gave up: swap the ringing notification for a
     *  persistent "Missed call" one that opens the chat. */
    private fun onMissedPush(data: Map<String, String>) {
        val callId = data["call_id"] ?: return
        val fromId = data["from_id"]?.toLongOrNull() ?: return
        val fromName = data["from_name"] ?: "Member"
        val kind = if (data["kind"] == "video") "video" else "voice"
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(callId.hashCode()) // stop the ringing notification
        PushStore.clearCall(this)
        PushNotifier.ensureMessagesChannel(this, nm, PushNotifier.ALERTS_CHANNEL_ID, "Missed calls")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("kwc_open_chat", fromId)
        }
        val pi = PendingIntent.getActivity(
            this, ("missed_$callId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, PushNotifier.ALERTS_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Missed call")
            .setContentText("$fromName — $kind call")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("missed_$callId").hashCode(), notif)
    }

    /** A chat message: notify unless the app is in the foreground
     *  (Realtime already shows it live there). Tap opens the chat. */
    private fun onChatMessagePush(data: Map<String, String>) {
        if (isForeground()) return
        val fromId = data["from_id"]?.toLongOrNull() ?: return
        val fromName = data["from_name"] ?: "Member"
        val preview = data["preview"] ?: ""
        val squad = data["chat_kind"] == "squad"
        val squadId = data["squad_id"]?.toLongOrNull() ?: 0L
        val squadName = data["squad_name"] ?: "Squad"
        val nm = getSystemService(NotificationManager::class.java) ?: return
        PushNotifier.ensureMessagesChannel(this, nm, PushNotifier.MESSAGES_CHANNEL_ID, "Messages")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (squad) putExtra("kwc_open_squad", squadId)
            else putExtra("kwc_open_chat", fromId)
        }
        val groupKey = if (squad) "msg_sq_$squadId" else "msg_dm_$fromId"
        val pi = PendingIntent.getActivity(
            this, groupKey.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, PushNotifier.MESSAGES_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(if (squad) squadName else fromName)
            .setContentText(if (squad) "$fromName: $preview" else preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (squad) "$fromName: $preview" else preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(groupKey)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(groupKey.hashCode(), notif)
    }

    private fun isForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
