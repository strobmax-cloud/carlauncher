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
 * «Car Launcher — кнопки навигации»: optional accessibility service used ONLY for performGlobalAction(BACK / HOME /
 * RECENTS) and, since v1.7.5, to show the floating ◁ ○ □ pill over other apps (TYPE_ACCESSIBILITY_OVERLAY — no
 * «поверх других окон» permission needed).
 * SAFETY: it does not filter key events (no flagRequestFilterKeyEvents → steering-wheel and panel keys go to the unit
 * as before), cannot read window content (canRetrieveWindowContent=false), ignores every accessibility event, and
 * changes no settings. The user switches it on once in Android → Спец. возможности and off the same way.
 */
class NavAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: NavAccess? = null; private set
        @Volatile private var front = false
        fun enabled(c: Context): Boolean = instance != null ||
            (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(c.packageName + "/")

        /** Returns false if the service is off (caller explains). */
        fun act(action: Int): Boolean = try { instance?.performGlobalAction(action) ?: false } catch (_: Throwable) { false }
        /** our launcher is in front → the floating pill hides (the launcher has its own ◁ ○ □) */
        fun launcherFront(v: Boolean) { front = v; instance?.let { s -> s.h.post { s.syncFloat() } } }
        /** settings changed (on / size / dim) */
        fun refresh() { instance?.let { s -> s.h.post { s.removeFloat(); s.syncFloat() } } }
    }
    internal val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var pill: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private val dimR = Runnable { if (K.floatDim) pill?.animate()?.alpha(0.38f)?.setDuration(500)?.start() }

    override fun onServiceConnected() {
        instance = this
        try { K.init(this); wm = getSystemService(WindowManager::class.java); syncFloat() } catch (t: Throwable) { CrashLog.soft(t) }
    }
    override fun onUnbind(intent: Intent?): Boolean { removeFloat(); instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { removeFloat(); instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    internal fun syncFloat() {
        try {
            val want = K.ready && K.floatOn && !front
            if (want && pill == null) addFloat()
            pill?.visibility = if (want) View.VISIBLE else View.GONE
            if (want) wake()
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
    internal fun removeFloat() { h.removeCallbacks(dimR); pill?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }; pill = null }
    private fun wake() { val p = pill ?: return; h.removeCallbacks(dimR); p.animate().cancel(); p.alpha = 1f; h.postDelayed(dimR, 4000) }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloat() {
        val w = wm ?: return
        val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(dm)
        val px = minOf(dm.widthPixels / 1280f, dm.heightPixels / 720f)
        fun d(v: Int) = Math.round(v * px)
        val bs = d(intArrayOf(56, 68, 80)[K.floatSize.coerceIn(0, 2)])
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(4), d(8), d(4))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = bs / 2f + d(4); setColor(0x99101218.toInt()); setStroke(Math.max(1, d(1)), 0x40FFFFFF) }
            contentDescription = "Плавающие кнопки"
        }
        val grip = FrameLayout(this).apply { contentDescription = "Перетащить" }
        grip.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = d(3).toFloat(); setColor(0x80FFFFFF.toInt()) } },
            FrameLayout.LayoutParams(d(5), bs / 2, Gravity.CENTER))
        row.addView(grip, LinearLayout.LayoutParams(d(28), bs))
        fun b(icon: String, desc: String, action: Int) {
            val f = FrameLayout(this).apply { contentDescription = desc }
            val iv = android.widget.ImageView(this).apply { setImageResource(Ck.iconRes(this@NavAccess, icon)); imageTintList = android.content.res.ColorStateList.valueOf(0xF0FFFFFF.toInt()) }
            f.addView(iv, FrameLayout.LayoutParams(bs * 9 / 20, bs * 9 / 20, Gravity.CENTER))
            f.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { wake(); v.alpha = .55f }
                    MotionEvent.ACTION_UP -> { v.alpha = 1f; if (e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) performGlobalAction(action) }
                    MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                true
            }
            row.addView(f, LinearLayout.LayoutParams(bs, bs).apply { leftMargin = d(2) })
        }
        b("sys_back", "Назад", GLOBAL_ACTION_BACK); b("sys_home", "Домой", GLOBAL_ACTION_HOME); b("sys_rec", "Недавние", GLOBAL_ACTION_RECENTS)
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = row.measuredWidth; val ph = row.measuredHeight
        val l = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            // default: bottom-right, above the bottom edge
            x = (if (K.floatX >= 0) K.floatX else dm.widthPixels - pw - d(24)).coerceIn(0, Math.max(0, dm.widthPixels - pw))
            y = (if (K.floatY >= 0) K.floatY else dm.heightPixels - ph - d(110)).coerceIn(0, Math.max(0, dm.heightPixels - ph))
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { wake(); sx = e.rawX; sy = e.rawY; ox = l.x; oy = l.y }
                MotionEvent.ACTION_MOVE -> {
                    l.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, Math.max(0, dm.widthPixels - pw)); l.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, Math.max(0, dm.heightPixels - ph))
                    try { w.updateViewLayout(row, l) } catch (_: Throwable) {}
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { K.floatX = l.x; K.floatY = l.y; wake() }
            }
            true
        }
        lp = l; pill = row
        w.addView(row, l)
    }
}

object NavKeys {
    fun back(c: Context) { if (!NavAccess.act(AccessibilityService.GLOBAL_ACTION_BACK)) explain(c) }
    fun recents(c: Context) { if (!NavAccess.act(AccessibilityService.GLOBAL_ACTION_RECENTS)) explain(c) }
    /** Home = our launcher (no service needed). */
    fun home(c: Context) {
        try { c.startActivity(Intent(c, ClassicActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Throwable) { NavAccess.act(AccessibilityService.GLOBAL_ACTION_HOME) }
    }
    const val HOW = "«Недавние» и плавающие кнопки ◁ ○ □ поверх других приложений работают через службу «Car Launcher — кнопки навигации».\n\n" +
        "Включить один раз:\n1. Нажмите «Открыть настройки».\n2. Спец. возможности (Accessibility) → «Car Launcher — кнопки навигации».\n3. Включите и подтвердите «Разрешить».\n4. Вернитесь кнопкой «Домой».\n\n" +
        "Служба только нажимает «Назад», «Домой» и «Недавние» и показывает плавающие кнопки. Она не читает экран, не перехватывает кнопки руля и магнитолы и не меняет настройки. Выключается там же."
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
        /** Called from ClassicActivity: keep the service in line with the setting. */
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
