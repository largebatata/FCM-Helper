package com.largebatata.fcmhelper

import android.content.Context

class SetupPreference(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var completed: Boolean
        get() = preferences.getBoolean(KEY_COMPLETED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_COMPLETED, value).apply()
        }

    private companion object {
        const val PREFS = "first_run_setup"
        const val KEY_COMPLETED = "completed"
    }
}
