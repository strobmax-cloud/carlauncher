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
        applyBars()
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
        // v1.7.3: + location «all the time» (Android 10: one dialog) — the trip is counted while the navigator is on top
        val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
    // ---- v1.7.3: files / video folder ----
    fun hasFiles() = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    /** ask for file access; when Android will not ask again → the app page («Разрешения») */
    fun askFiles() {
        if (hasFiles()) return
        if (Prefs.askedPerms && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) && filesAsked) {
            ck.showToast("Разрешения → «Хранилище» → Разрешить")
            AppRepo.startSafe(this, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        } else { filesAsked = true; requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 4) }
    }
    private var filesAsked = false
    fun pickVideoTree() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), VideoTree.REQ) }
        catch (_: Throwable) { ck.showToast("На магнитоле нет выбора папок Android — используйте «Выбрать папку…»"); ck.overlays.folderPicker(null) }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VideoTree.REQ || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        val path = VideoTree.toPath(uri)
        if (path == null || java.io.File(path).listFiles() == null) { ck.showToast("Эту папку нельзя открыть как файлы — выберите её в «Выбрать папку…»"); ck.overlays.folderPicker(null); return }
        ck.overlays.useVideoDir(path)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Trip.start(this); Videos.scanAsync(this) { ck.onStorage() }; Eq.open(this)
    }

    override fun onStart() { super.onStart(); started = true; ck.onStart() }
    override fun onResume() { super.onResume(); applyBars(); NavAccess.launcherFront(true); ck.onResume() }
    override fun onPause() { ck.onPause(); NavAccess.launcherFront(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyBars() }

    /**
     * v1.7.5: our window always spans the whole screen; the stage is fitted under the system bar (insets) or, with
     * «Скрывать системную панель», the bar is hidden in the launcher only (immersive sticky: a swipe from the top
     * shows it for a moment). Nothing in Android settings is changed — other apps keep the bar.
     */
    private var barListener = false
    @Suppress("DEPRECATION")
    fun applyBars() {
        // v1.7.6: no LAYOUT_STABLE while hiding — stable insets keep reporting the bar's height although it is gone
        val full = android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val hide = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        try {
            val d = window.decorView
            d.systemUiVisibility = if (K.hideBar) full or hide else full or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.statusBarColor = android.graphics.Color.TRANSPARENT; window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (!barListener) { barListener = true
                // the firmware may show / hide the bar on its own (or ignore our flags) → refit whenever the state changes
                d.setOnSystemUiVisibilityChangeListener { ck.root.refit(); ck.root.requestApplyInsets() } }
            ck.root.refit()
            ck.root.requestApplyInsets()
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
    /** for «О программе»: real screen, our window, the bar */
    fun screenInfo(): String {
        val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
        val r = ck.root
        return "Экран ${dm.widthPixels}×${dm.heightPixels}, окно ${r.width}×${r.height}, системная панель сверху: ${r.topInset} px" +
            (if (K.hideBar) " (скрыта в лаунчере)" else "") + ", масштаб ${"%.2f".format(r.scale)}"
    }
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


/** v1.7.3: Android folder picker → a real path (legacy storage on Android 10: the file is then read directly) */
object VideoTree {
    const val REQ = 73
    /** content://…/tree/primary:Movies → /storage/emulated/0/Movies; …/tree/1A2B-3C4D:CarLauncher → /storage/1A2B-3C4D/CarLauncher */
    fun toPath(uri: android.net.Uri): String? = try { idToPath(android.provider.DocumentsContract.getTreeDocumentId(uri)) } catch (_: Throwable) { null }
    fun idToPath(id: String): String? {
        val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
        val bases = if (vol.equals("primary", true)) listOf("/storage/emulated/0") else listOf("/storage/$vol", "/mnt/media_rw/$vol", "/mnt/usb/$vol")
        val cands = bases.map { if (rel.isEmpty()) it else "$it/$rel" }
        return cands.firstOrNull { java.io.File(it).exists() } ?: cands.first()
    }
}
