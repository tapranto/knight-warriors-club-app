package com.knightwarriorsclub.app.data

import android.content.Context

/** Small on-phone app preferences (not account data). */
object AppPrefs {
    private const val FILE = "kwc_app_prefs"

    // beta41: low-data mode — remote pictures decode at quarter size.
    fun lowData(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("low_data", false)

    // beta49: which update notice the member already dismissed —
    // the same version never nags twice.
    fun dismissedUpdateCode(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt("dismissed_update", 0)

    fun setDismissedUpdateCode(context: Context, code: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt("dismissed_update", code).apply()
    }

    fun setLowData(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("low_data", value).apply()
    }
}
