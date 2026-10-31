package com.knightwarriorsclub.app.data

import android.content.Context

/**
 * Persists the Supabase session on this device only (app-private storage),
 * so members sign in once — same behaviour as the website.
 * Tokens never leave the device except to Supabase itself.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("kwc_session", Context.MODE_PRIVATE)

    fun save(session: Session) {
        prefs.edit()
            .putString("access", session.accessToken)
            .putString("refresh", session.refreshToken)
            .putString("uid", session.authUserId)
            .putString("email", session.email)
            .apply()
    }

    fun refreshToken(): String? = prefs.getString("refresh", null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
