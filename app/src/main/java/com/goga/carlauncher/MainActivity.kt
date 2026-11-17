package com.goga.carlauncher

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * v1.7.1 home activity: the new two-screen interface (Главная / Музыка) in style C «Линза» or D «Стекло», see [Cockpit].
 * The 1.7.0 screens are still in the app as [ClassicActivity] (Настройки → О программе).
 *
 * SAFETY (owner's rule): this activity never intercepts keys (no onKeyDown / dispatchKeyEvent override → steering-wheel
 * buttons go to the system as before), never touches the MCU or system settings; «Вернуть штатный экран» is in
 * Настройки → О программе.
 */
class MainActivity : Activity() {
    lateinit var ck: Cockpit
        private set
    private var started = false

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { AppRepo.invalidate(); if (::ck.isInitialized) ck.onPackages() }
    }
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (::ck.isInitialized) ck.onStorage() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        Prefs.init(this); K.init(this); Ck.init(this)
        Updater.host = java.lang.ref.WeakReference(this)
        Updater.cleanupLeftovers(this)
        Syu.bind(this)   // TEYES/FYT volume service, bound early so the first press already works
        Wx.init(); Trip.checkDay()
        // v1.7.2 safe start: crashed twice in 3 minutes → the heavy extras off (video, animations), the owner is told
        val crashes = CrashLog.recent(this)
        if (crashes >= 2) {
            if (K.bg == "video") K.bg = "theme"
            K.waves = false; K.particles = false; K.safeAt = System.currentTimeMillis()
            CrashLog.clearRecent(this)
        }
        ck = Cockpit(this)
        if (crashes >= 2) ck.showToast("Car Launcher перезапустился после сбоя: видео и анимации выключены. Журнал — Настройки → О программе")
        else if (intent?.getBooleanExtra("afterCrash", false) == true) ck.showToast("Car Launcher перезапустился после сбоя (записано в журнал)")
        setContentView(ck.root)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        registerReceiver(mediaReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file")
        })
        if (!Prefs.askedPerms) { Prefs.askedPerms = true; requestPerms() }
        AppRepo.loadAsync(this) {}
    }

    fun requestPerms() {
        val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Trip.start(this); Videos.scanAsync(this) { ck.onStorage() }; Eq.open(this)
    }

    override fun onStart() { super.onStart(); started = true; ck.onStart() }
    override fun onResume() { super.onResume(); ck.onResume() }
    override fun onPause() { ck.onPause(); super.onPause() }
    override fun onStop() { started = false; ck.onStop(); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we are in front → close overlays and go to «Главная»
        if (Intent.ACTION_MAIN == intent.action) ck.home()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!ck.back()) ck.home() }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_UI_HIDDEN) AppRepo.trimIcons() }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaReceiver) } catch (_: Exception) {}
        ck.destroy()
        super.onDestroy()
    }
}
