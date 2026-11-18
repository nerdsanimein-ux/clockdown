package com.rishabh.clockdown.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Light, dark, or follow the phone: the Settings choice and the Home screen's moon/sun button. Every Compose screen reads
 * [current]; on Android 12+ it is also handed to the system (per-app night mode), so the LMS pages and the status bar
 * follow it too.
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    var current by mutableStateOf(SYSTEM)
        private set

    fun init(ctx: Context) {
        current = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("theme", SYSTEM) ?: SYSTEM
    }

    fun set(ctx: Context, mode: String) {
        current = mode
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            ctx.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (mode) { LIGHT -> UiModeManager.MODE_NIGHT_NO; DARK -> UiModeManager.MODE_NIGHT_YES; else -> UiModeManager.MODE_NIGHT_AUTO },
            )
        }
    }

    @Composable
    fun isDark(): Boolean = when (current) { LIGHT -> false; DARK -> true; else -> isSystemInDarkTheme() }
}
