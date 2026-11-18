package com.goga.carlauncher

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v1.7.2: crash log + automatic return to Car Launcher after a crash (instead of being left on the stock screen). */
class App : Application() {
    override fun onCreate() {
        super.onCreate(); CrashLog.install(this)
        // v1.7.3 trip: ignition off → on = the unit booted again, or its screen was off ≥ 2 min (sleep mode)
        try {
            K.init(this); Trip.checkBoot()
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) { Trip.screen(i.action == android.content.Intent.ACTION_SCREEN_ON) }
            }, android.content.IntentFilter().apply { addAction(android.content.Intent.ACTION_SCREEN_ON); addAction(android.content.Intent.ACTION_SCREEN_OFF) })
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
}

object CrashLog {
    private var app: Context? = null
    private var prev: Thread.UncaughtExceptionHandler? = null
    private const val MAX = 96 * 1024
    private var softCount = 0

    fun file(c: Context) = File(c.filesDir, "crash.log")
    /** copy readable with a file manager: Android/data/com.goga.carlauncher/files/crash.log */
    private fun ext(c: Context) = try { c.getExternalFilesDir(null)?.let { File(it, "crash.log") } } catch (_: Throwable) { null }

    fun install(c: Context) {
        app = c.applicationContext
        if (Build.FINGERPRINT == "robolectric") return
        prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> onCrash(t, e) }
    }

    private fun onCrash(t: Thread, e: Throwable) {
        val c = app
        try {
            if (c != null) {
                write(c, "СБОЙ (поток ${t.name})", e)
                val sp = c.getSharedPreferences("crash", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val times = (sp.getString("times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 180_000 } + now
                sp.edit().putString("times", times.joinToString(",")).commit()
                // come back by itself unless it keeps crashing (3 times in 3 minutes → leave it to the user)
                if (times.size <= 3) {
                    val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("afterCrash", true)
                    val pi = PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    c.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, now + 1200, pi)
                }
            }
        } catch (_: Throwable) {}
        try { Process.killProcess(Process.myPid()) } catch (_: Throwable) {}
        prev?.uncaughtException(t, e)
        System.exit(10)
    }

    /** crashes in the last 3 minutes (for the safe start) */
    fun recent(c: Context): Int {
        val now = System.currentTimeMillis()
        return (c.getSharedPreferences("crash", Context.MODE_PRIVATE).getString("times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.count { now - it < 180_000 }
    }
    fun clearRecent(c: Context) { c.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().remove("times").apply() }

    /** caught error (the screen survived): logged, at most 30 per run */
    fun soft(e: Throwable) {
        val c = app ?: return
        if (softCount++ > 30) return
        try { write(c, "ошибка (перехвачена)", e) } catch (_: Throwable) {}
    }

    @Synchronized fun write(c: Context, what: String, e: Throwable) {
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Throwable) { "?" }
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
        val entry = "=== " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " · $what · v$ver · Android ${Build.VERSION.RELEASE}\n" + sw.toString().take(6000) + "\n"
        for (f in listOfNotNull(file(c), ext(c))) try {
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + entry).takeLast(MAX))
        } catch (_: Throwable) {}
    }
    fun text(c: Context): String = try { file(c).takeIf { it.exists() }?.readText() ?: "" } catch (_: Throwable) { "" }
    fun clear(c: Context) { file(c).delete(); ext(c)?.delete(); clearRecent(c) }
}
