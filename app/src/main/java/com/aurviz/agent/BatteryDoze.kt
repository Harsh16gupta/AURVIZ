package com.aurviz.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// Ticket #34: Battery and Doze UX.
// Doze + OEM battery managers can delay or kill exact alarms and Workers,
// so Settings carries an in-app battery-optimization prompt (request
// ignore-battery-optimizations where needed) plus OEM autostart guidance.
// Revoked SCHEDULE_EXACT_ALARM already falls back to inexact (see
// registerRoutineAlarm); the exact-alarm-need flag keeps that visible.

// Vendor autostart paths shown verbatim in Settings. Kept as a const so
// the UI and the test read the same source of truth.
const val OEM_AUTOSTART_GUIDANCE =
    "Some phones need one extra step beyond the prompt above. " +
        "Xiaomi (MIUI): Settings > Battery > App battery saver > AIRA > No restrictions, " +
        "then Security > Autostart > allow AIRA. " +
        "Oppo/OnePlus (ColorOS/OxygenOS): Settings > Battery > App battery management > " +
        "AIRA > Allow background activity + Allow auto-launch. " +
        "Vivo (Funtouch OS): Settings > Battery > Background power consumption > allow AIRA, " +
        "then Settings > Apps > Autostart > allow AIRA. " +
        "Huawei (EMUI): Settings > Battery > App launch > AIRA > Manage manually > " +
        "allow Auto-launch, Secondary launch, Run in background. " +
        "Samsung (One UI): Settings > Battery > Background usage limits > Never sleeping apps > " +
        "add AIRA."

// Pure decision: the prompt shows exactly when the app is not exempted.
// Takes the PowerManager answer as input so JVM tests need no framework.
fun batteryPromptNeeded(isIgnoringBatteryOptimizations: Boolean): Boolean =
    !isIgnoringBatteryOptimizations

// Pure package-Uri shape (JVM-testable; the Intent builder below just wraps it).
fun ignoreBatteryPackageUri(packageName: String): String = "package:$packageName"

// Intent that asks the system to exempt this package. The caller starts it
// from an Activity context; a fallback list intent exists for devices whose
// vendor ROM drops the request action.
fun ignoreBatteryIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse(ignoreBatteryPackageUri(packageName)),
    )

fun batteryOptimizationListIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

// One line of user-facing timing truth shared by the ViewModel status line
// and the Settings footer: exact when granted, inexact fallback otherwise.
fun scheduleTimingNote(exactGranted: Boolean): String =
    if (exactGranted) {
        "Exact alarm set — Schedules fire at their saved time."
    } else {
        "Exact alarms are off — Schedules still run via inexact timing, " +
            "just not exactly on time. Allow them in Settings."
    }

// Framework seam: thin read of PowerManager, kept out of the pure decision
// above so tests stay on the JVM.
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

fun refreshBatteryNeeded(context: Context): Boolean =
    batteryPromptNeeded(isIgnoringBatteryOptimizations(context))

// Opens the request intent; vendor ROMs that lack the action fall back to
// the system exemption list instead of crashing.
fun openBatteryExemption(context: Context) {
    val request = ignoreBatteryIntent(context.packageName)
    try {
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(request)
    } catch (_: Exception) {
        try {
            context.startActivity(
                batteryOptimizationListIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}
