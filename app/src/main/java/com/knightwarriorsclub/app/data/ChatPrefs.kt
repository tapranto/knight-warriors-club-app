package com.knightwarriorsclub.app.data

import android.content.Context

/**
 * Phone-local chat preferences (v0.4.0): everything here lives ONLY on
 * this device — hidden conversations, per-chat nicknames, muted chats
 * and bubble colours are personal view settings, never synced.
 */
object ChatPrefs {
    private const val PREFS = "kwc_chat_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- hidden conversations (long-press → Delete chat) ----------
    // Stored as partnerId -> epoch millis when hidden. The chat returns
    // to the inbox when a newer message arrives after that moment.

    fun hideChat(context: Context, partnerId: Long) {
        prefs(context).edit()
            .putLong("hidden_$partnerId", System.currentTimeMillis()).apply()
    }

    fun hiddenAt(context: Context, partnerId: Long): Long =
        prefs(context).getLong("hidden_$partnerId", 0L)

    fun unhideChat(context: Context, partnerId: Long) {
        prefs(context).edit().remove("hidden_$partnerId").apply()
    }

    // ---------- nicknames (chat-details page) ----------

    fun nickname(context: Context, partnerId: Long): String? =
        prefs(context).getString("nick_$partnerId", null)?.takeIf { it.isNotBlank() }

    fun setNickname(context: Context, partnerId: Long, name: String) {
        val e = prefs(context).edit()
        if (name.isBlank()) e.remove("nick_$partnerId")
        else e.putString("nick_$partnerId", name.trim())
        e.apply()
    }

    /** Display name for a partner: nickname when set, else their real name. */
    fun displayName(context: Context, partner: Member): String =
        nickname(context, partner.id) ?: partner.fullName

    // ---------- muted chats (no unread badge) ----------

    fun isMuted(context: Context, partnerId: Long): Boolean =
        prefs(context).getBoolean("mute_$partnerId", false)

    fun setMuted(context: Context, partnerId: Long, muted: Boolean) {
        prefs(context).edit().putBoolean("mute_$partnerId", muted).apply()
    }

    // ---------- bubble colours (own bubbles, per chat) ----------
    // 0 = club gold (default), 1 = blue, 2 = green, 3 = purple.

    fun bubbleColour(context: Context, partnerId: Long): Int =
        prefs(context).getInt("bubble_$partnerId", 0)

    fun setBubbleColour(context: Context, partnerId: Long, idx: Int) {
        prefs(context).edit().putInt("bubble_$partnerId", idx).apply()
    }
}
