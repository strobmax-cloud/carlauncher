package com.goga.carlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/**
 * «Сменить телефон»: opens the list of paired Bluetooth phones so another phone can be connected.
 * Order: FYT Bluetooth app (com.syu.bt) — its own paired-devices / settings activity if one is exported → Android
 * Bluetooth settings → the FYT BT app's main screen. Nothing is changed by the launcher itself.
 *
 * Optional (off by default): first ask Android to close a wireless CarPlay / Android Auto adapter app (ZLink, Autokit…),
 * because such apps often keep the phone connected over BT. Without root a launcher can only call
 * ActivityManager.killBackgroundProcesses(), which does NOT stop an app that runs a foreground service — so this is
 * best effort; if the adapter keeps the phone, disconnect it in the adapter app or on the phone.
 */
object PhoneSwitch {
    const val FYT_BT = "com.syu.bt"
    val WIRELESS = listOf("com.zjinnova.zlink", "cn.manstep.phonemirrorBox", "com.carlinkit.autokit", "com.ludashi.zlink", "com.syu.carlink", "com.suding.carplay")
    private val KEYS = listOf("pair", "device", "devlist", "set", "connect", "search")

    /** Exported activity of the FYT BT app that looks like a device list / settings page, if any. */
    fun fytDeviceActivity(a: Activity): ComponentName? = try {
        @Suppress("DEPRECATION")
        val info = a.packageManager.getPackageInfo(FYT_BT, PackageManager.GET_ACTIVITIES)
        info.activities?.filter { it.exported && it.enabled }?.firstOrNull { act -> KEYS.any { act.name.lowercase().substringAfterLast('.').contains(it) } }
            ?.let { ComponentName(FYT_BT, it.name) }
    } catch (e: Exception) { null }

    fun installedWireless(a: Activity) = WIRELESS.filter { Tiles.installed(a, it) && it != Tiles.INTERNAL_MEDIA }

    /** Returns which target was opened: "fyt-devices" | "settings" | "fyt" | null. */
    fun open(a: Activity): String? {
        if (Prefs.closeWireless) {
            val am = a.getSystemService(ActivityManager::class.java)
            val apps = installedWireless(a)
            apps.forEach { try { am.killBackgroundProcesses(it) } catch (_: Exception) {} }
            if (apps.isNotEmpty()) Toast.makeText(a, "Попросил Android закрыть " + apps.joinToString { Tiles.label(a, it) } + " (если не закроется — отключите телефон в нём)", Toast.LENGTH_LONG).show()
        }
        fytDeviceActivity(a)?.let { cn ->
            if (start(a, Intent().setComponent(cn))) return "fyt-devices"
        }
        if (start(a, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return "settings"
        a.packageManager.getLaunchIntentForPackage(FYT_BT)?.let { if (start(a, it)) return "fyt" }
        Toast.makeText(a, "Не нашёл экран Bluetooth на этой магнитоле", Toast.LENGTH_LONG).show()
        return null
    }

    private fun start(a: Activity, i: Intent): Boolean = try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
}
