package com.aurviz.agent.ui.components

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import com.aurviz.agent.ui.theme.ThemeMode

enum class HapticKind { Light, Soft, Medium, Selection, Success, Error }

class AurvizHaptics(
    private val view: View,
    private val enabledProvider: () -> Boolean,
) {
    private var lastPlayedMs = 0L

    fun play(kind: HapticKind) {
        if (!enabledProvider()) return
        val now = System.currentTimeMillis()
        if (now - lastPlayedMs < 40L) return
        lastPlayedMs = now

        val constant = when (kind) {
            HapticKind.Medium -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.Success -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            HapticKind.Error -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            HapticKind.Selection -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            HapticKind.Light, HapticKind.Soft -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }
}

object UiPrefs {
    private const val PREFS = "aurviz_ui_prefs"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_PROVIDER = "selected_provider"
    private const val KEY_CONFIRM_EMAIL = "confirm_send_email"
    private const val KEY_DISABLED_SCHEDULES = "disabled_schedule_ids"
    private const val KEY_ALLOW_ALWAYS = "allow_always_recipients"
    private const val KEY_MEMORY_FACET = "memory_facet"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hapticsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HAPTICS, true)
    fun setHapticsEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HAPTICS, on).apply()

    fun themeMode(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs(context).getString(KEY_THEME, "SYSTEM") ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
    fun setThemeMode(context: Context, mode: ThemeMode) = prefs(context).edit().putString(KEY_THEME, mode.name).apply()

    fun selectedProvider(context: Context): String? = prefs(context).getString(KEY_PROVIDER, null)
    fun setSelectedProvider(context: Context, provider: String?) = prefs(context).edit().putString(KEY_PROVIDER, provider).apply()

    fun confirmSendEmail(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_EMAIL, true)
    fun setConfirmSendEmail(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_CONFIRM_EMAIL, on).apply()

    fun disabledScheduleIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_SCHEDULES, emptySet()).orEmpty().toSet()
    fun setDisabledScheduleIds(context: Context, ids: Set<String>) =
        prefs(context).edit().putStringSet(KEY_DISABLED_SCHEDULES, ids).apply()

    // Ticket #68: revocable per-recipient allow-always grants
    // (GLOSSARY Preference). Stored normalized; the live store is the
    // decision input, these prefs are just persistence.
    fun allowAlways(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOW_ALWAYS, emptySet()).orEmpty().toSet()
    fun setAllowAlways(context: Context, addresses: Set<String>) =
        prefs(context).edit().putStringSet(KEY_ALLOW_ALWAYS, addresses).apply()

    // Ticket #89: persisted Memory facet selection (title-case chip row).
    fun memoryFacet(context: Context): String =
        prefs(context).getString(KEY_MEMORY_FACET, "All") ?: "All"
    fun setMemoryFacet(context: Context, facet: String) =
        prefs(context).edit().putString(KEY_MEMORY_FACET, facet).apply()

    fun onboardingCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    fun setOnboardingCompleted(context: Context, done: Boolean) =
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, done).apply()
}

val LocalHaptics = staticCompositionLocalOf<AurvizHaptics?> { null }

@Composable
fun rememberAurvizHaptics(enabled: Boolean): AurvizHaptics {
    val view = LocalView.current
    return remember(view, enabled) { AurvizHaptics(view) { enabled } }
}
