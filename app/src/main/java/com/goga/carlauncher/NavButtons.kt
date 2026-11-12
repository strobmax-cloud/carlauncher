package com.goga.carlauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.goga.carlauncher.Ui.u

/**
 * v1.7.1 «Кнопки навигации»: optional accessibility service used ONLY for performGlobalAction(BACK / RECENTS / HOME).
 * It does not filter key events (steering-wheel and panel keys go to the unit as before), does not read windows,
 * reacts to no events. The user switches it on once in Android → Спец. возможности; off = the buttons explain how.
 */
class NavAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: NavAccess? = null; private set
        fun enabled(c: Context): Boolean = instance != null ||
            (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(c.packageName + "/")

        /** Returns false if the service is off (caller explains). */
        fun act(action: Int): Boolean = try { instance?.performGlobalAction(action) ?: false } catch (_: Throwable) { false }
    }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

object NavKeys {
    fun back(c: Context) { if (!NavAccess.act(AccessibilityService.GLOBAL_ACTION_BACK)) explain(c) }
    fun recents(c: Context) { if (!NavAccess.act(AccessibilityService.GLOBAL_ACTION_RECENTS)) explain(c) }
    /** Home = our launcher (no service needed). */
    fun home(c: Context) {
        try { c.startActivity(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Throwable) { NavAccess.act(AccessibilityService.GLOBAL_ACTION_HOME) }
    }
    const val HOW = "Кнопки «Назад» и «Недавние» работают через службу «Car Launcher — кнопки навигации».\n\n" +
        "Включить один раз: Настройки Android → Спец. возможности (Accessibility) → Car Launcher — кнопки навигации → Вкл.\n\n" +
        "Служба только нажимает «Назад» / «Недавние». Она не читает экран и не трогает кнопки руля и магнитолы. Выключается там же."
    fun explain(c: Context) {
        if (c is android.app.Activity) {
            val d = android.app.AlertDialog.Builder(c, R.style.Dialog).setTitle("Кнопки навигации").setMessage(HOW)
                .setPositiveButton("Открыть настройки") { _, _ -> openSettings(c) }.setNegativeButton("Позже", null).create()
            d.window?.setWindowAnimations(0); d.show()
        } else {
            android.widget.Toast.makeText(c, "Включите «Car Launcher — кнопки навигации» в Спец. возможностях", android.widget.Toast.LENGTH_LONG).show()
            openSettings(c)
        }
    }
    fun openSettings(c: Context) = AppRepo.startSafe(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}

/**
 * v1.7.1 optional floating «Назад / Домой / Недавние» pill over other apps (the system bar of the TEYES sits under the
 * phone holder). Overlay permission, off by default, draggable by its grip, hidden while the launcher is in front.
 */
class FloatNav : Service() {
    companion object {
        private const val CH = "floatnav"
        const val ACTION_STOP = "com.goga.carlauncher.FLOATNAV_STOP"
        @Volatile var instance: FloatNav? = null; private set
        private var launcherInFront = true
        fun start(c: Context): Boolean {
            if (!Settings.canDrawOverlays(c)) return false
            val i = Intent(c, FloatNav::class.java)
            return try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i); true } catch (_: Exception) { false }
        }
        fun stop(c: Context) { try { c.stopService(Intent(c, FloatNav::class.java)) } catch (_: Exception) {} }
        fun setLauncherInFront(v: Boolean) { launcherInFront = v; instance?.apply() }
        /** Called from MainActivity: keep the service in line with the setting. */
        fun sync(c: Context) { if (Prefs.floatNav && Settings.canDrawOverlays(c)) { if (instance == null) start(c) } else if (instance != null) stop(c) }
    }
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: View? = null

    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        startForeground(8, notification())
        val dm = resources.displayMetrics
        if (Ui.s == 1f) Ui.s = minOf(maxOf(dm.widthPixels, dm.heightPixels) / 1280f, minOf(dm.widthPixels, dm.heightPixels) / 720f)
        wm = getSystemService(WindowManager::class.java)
        try { build(); instance = this; apply() } catch (e: Exception) { stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Prefs.floatNav = false; stopSelf() }
        return START_STICKY
    }
    override fun onDestroy() {
        instance = null
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }; root = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Кнопки Назад / Домой", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 2, Intent(this, FloatNav::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_back).setContentTitle("Плавающие кнопки Назад / Домой").setContentText("Нажмите, чтобы убрать")
            .setContentIntent(stop).setOngoing(true).build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val size = u(72)
        val pill = Ui.hbox(this).apply { setPadding(u(6), u(6), u(8), u(6)); background = Ui.round(0xB3101012.toInt(), u(44)) }
        val grip = FrameLayout(this).apply { contentDescription = "Перетащить" }
        grip.addView(View(this).apply { background = Ui.round(0x80FFFFFF.toInt(), u(3)) }, Ui.frame(u(6), u(36)))
        pill.addView(grip, Ui.lin(u(34), size))
        fun b(res: Int, desc: String, f: () -> Unit) = Ui.iconButton(this, res, 72, 34, 0x33FFFFFF, Ui.TEXT, 0x66FFFFFF) { f() }.apply { contentDescription = desc }
        pill.addView(b(R.drawable.ic_back, "Назад") { NavKeys.back(this) }, Ui.lin(size, size))
        pill.addView(b(R.drawable.ic_home, "Домой") { NavKeys.home(this) }, Ui.lin(size, size).apply { leftMargin = u(10) })
        pill.addView(b(R.drawable.ic_recents, "Недавние") { NavKeys.recents(this) }, Ui.lin(size, size).apply { leftMargin = u(10) })
        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = Prefs.floatX.takeIf { it >= 0 } ?: (dm.widthPixels - u(320)); y = Prefs.floatY.takeIf { it >= 0 } ?: (dm.heightPixels - u(110))
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = (ox + (e.rawX - sx)).toInt().coerceAtLeast(0); lp.y = (oy + (e.rawY - sy)).toInt().coerceAtLeast(0); try { wm.updateViewLayout(root, lp) } catch (_: Exception) {} }
                MotionEvent.ACTION_UP -> { Prefs.floatX = lp.x; Prefs.floatY = lp.y }
            }
            true
        }
        root = pill
        wm.addView(pill, lp)
    }
    fun apply() { root?.visibility = if (launcherInFront) View.GONE else View.VISIBLE }
    @Suppress("unused") private fun unused(l: LinearLayout) {}
}
