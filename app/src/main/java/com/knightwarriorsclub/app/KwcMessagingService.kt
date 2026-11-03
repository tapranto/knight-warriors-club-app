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

    fun saveCallInfo(context: Context, invite: PushInviteData) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ringing_call_id", invite.callId)
            .putLong("ringing_from_id", invite.fromId)
            .putString("ringing_from_name", invite.fromName)
            .putString("ringing_kind", invite.kind)
            .apply()
    }

    fun callInfo(context: Context): PushInviteData? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("ringing_call_id", null) ?: return null
        return PushInviteData(
            callId = id,
            fromId = p.getLong("ringing_from_id", 0L),
            fromName = p.getString("ringing_from_name", null) ?: "Member",
            fromAvatar = null,
            kind = p.getString("ringing_kind", null) ?: "voice"
        )
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
            .build()
        nm.notify(data.callId.hashCode(), notif)
        PushStore.saveCallInfo(context, data)
        scheduleMissedCheck(context, data.callId)
    }

    fun clearCallNotification(context: Context) {
        val callId = PushStore.ringingCallId(context) ?: return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(callId.hashCode())
        cancelMissedCheck(context, callId)
        PushStore.clearCall(context)
    }

    /** Local safety net (beta18): after the ring window, if the call is
     *  still sitting unanswered in PushStore, convert it into a missed
     *  call notification — even if the caller's side never sends the
     *  server missed-push (older app/website) or that push gets lost. */
    fun scheduleMissedCheck(context: Context, callId: String) {
        runCatching {
            val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 40_000,
                missedCheckIntent(context, callId)
            )
        }
    }

    fun cancelMissedCheck(context: Context, callId: String) {
        runCatching {
            val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            am.cancel(missedCheckIntent(context, callId))
        }
    }

    private fun missedCheckIntent(context: Context, callId: String): PendingIntent {
        val intent = Intent(context, MissedCallReceiver::class.java).apply {
            action = "com.knightwarriorsclub.app.MISSED_CHECK"
            putExtra("call_id", callId)
        }
        return PendingIntent.getBroadcast(
            context, ("missedcheck_$callId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The persistent "Missed call" notification (tap opens the chat). */
    fun showMissedNotification(
        context: Context, callId: String, fromId: Long, fromName: String, kind: String
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        cancelMissedCheck(context, callId)
        nm.cancel(callId.hashCode()) // stop the ringing notification
        PushStore.clearCall(context)
        ensureMessagesChannel(context, nm, ALERTS_CHANNEL_ID, "Missed calls")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("kwc_open_chat", fromId)
        }
        val pi = PendingIntent.getActivity(
            context, ("missed_$callId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Missed call")
            .setContentText("$fromName — $kind call")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("missed_$callId").hashCode(), notif)
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
        PushNotifier.showMissedNotification(this, callId, fromId, fromName, kind)
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
        // Chat bubbles (beta32): publish a conversation shortcut and
        // attach bubble metadata — whether the chat actually floats is
        // the member's phone setting (Floating notifications → Bubbles).
        val shortcutId = if (squad) "sq_$squadId" else "dm_$fromId"
        val personName = if (squad) squadName else fromName
        val person = androidx.core.app.Person.Builder()
            .setName(personName).setKey(shortcutId).build()
        val previewText = if (squad) "$fromName: $preview" else preview
        runCatching {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, shortcutId)
                .setLongLived(true)
                .setIntent(intent)
                .setShortLabel(personName)
                .setPerson(person)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
        val bubblePi = PendingIntent.getActivity(
            this, ("bubble_$shortcutId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val builder = NotificationCompat.Builder(this, PushNotifier.MESSAGES_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(if (squad) squadName else fromName)
            .setContentText(previewText)
            .setStyle(
                NotificationCompat.MessagingStyle(person)
                    .addMessage(previewText, System.currentTimeMillis(), person)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(groupKey)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setShortcutId(shortcutId)
            .addPerson(person)
        runCatching {
            val bubbleData = NotificationCompat.BubbleMetadata.Builder(
                bubblePi,
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    this, applicationInfo.icon
                )
            ).setDesiredHeight(560).build()
            builder.setBubbleMetadata(bubbleData)
        }
        nm.notify(groupKey.hashCode(), builder.build())
    }

    private fun isForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}


/** Fires ~40 s after a call push: converts an unanswered ring into a
 *  missed-call notification locally (see PushNotifier.scheduleMissedCheck). */
class MissedCallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val callId = intent?.getStringExtra("call_id") ?: return
        val info = PushStore.callInfo(context) ?: return
        if (info.callId != callId) return
        PushNotifier.showMissedNotification(
            context, info.callId, info.fromId, info.fromName, info.kind
        )
    }
}
