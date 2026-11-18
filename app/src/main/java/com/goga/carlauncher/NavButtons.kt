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
 * v1.7.7: «−10» / «+10» seek buttons on the pill while the app in front plays (see [Seek]); to know the app in front the
 * service receives TYPE_WINDOW_STATE_CHANGED and keeps ONLY its package name.
 * SAFETY: it does not filter key events (no flagRequestFilterKeyEvents → steering-wheel and panel keys go to the unit
 * as before), cannot read window content (canRetrieveWindowContent=false), uses no event besides the package name of
 * the window in front, and changes no settings. The user switches it on once in Android → Спец. возможности and off the same way.
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
    /** package of the app in front (from window-state events; null = unknown) */
    @Volatile var fg: String? = null; private set
    private val seekViews = ArrayList<View>()
    private var seekShown = false
    private val seekPoll = object : Runnable { override fun run() { syncSeek(); if (pill != null && pill?.visibility == View.VISIBLE) h.postDelayed(this, 1000) } }

    override fun onServiceConnected() {
        instance = this
        try { K.init(this); wm = getSystemService(WindowManager::class.java); syncFloat() } catch (t: Throwable) { CrashLog.soft(t) }
    }
    override fun onUnbind(intent: Intent?): Boolean { removeFloat(); instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { removeFloat(); instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // only the package name of the window in front; our own pill / the system bar / IME do not count
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui" || p.contains("inputmethod")) return
        if (p != fg) { fg = p; h.post { syncSeek() } }
    }
    override fun onInterrupt() {}

    internal fun syncFloat() {
        try {
            val want = K.ready && K.floatOn && !front
            if (want && pill == null) addFloat()
            pill?.visibility = if (want) View.VISIBLE else View.GONE
            if (want) { wake(); h.removeCallbacks(seekPoll); h.post(seekPoll) } else h.removeCallbacks(seekPoll)
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
    internal fun removeFloat() { h.removeCallbacks(dimR); h.removeCallbacks(seekPoll); seekViews.clear(); seekShown = false; pill?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }; pill = null }
    private fun wake() { val p = pill ?: return; h.removeCallbacks(dimR); p.animate().cancel(); p.alpha = 1f; h.postDelayed(dimR, 4000) }

    /** show / hide «−10» «+10»; the pill keeps its right edge where it was (it sits near the right side by default) */
    internal fun syncSeek() {
        val row = pill ?: return; val l = lp ?: return; val w = wm ?: return
        val want = K.ready && K.seekOn && try { Seek.available(this, fg) } catch (_: Throwable) { false }
        if (want == seekShown) return
        val before = row.width.takeIf { it > 0 } ?: row.measuredWidth
        seekShown = want
        for (v in seekViews) v.visibility = if (want) View.VISIBLE else View.GONE
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val after = row.measuredWidth
        val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(dm)
        l.x = (l.x + before - after).coerceIn(0, Math.max(0, dm.widthPixels - after))
        try { w.updateViewLayout(row, l) } catch (_: Throwable) {}
        wake()
    }

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
        // «−10» / «+10»: only while the app in front plays (syncSeek)
        for (sign in intArrayOf(-1, 1)) {
            val tv = android.widget.TextView(this).apply {
                text = (if (sign < 0) "−" else "+") + K.seekStep; setTextColor(0xF0FFFFFF.toInt()); gravity = Gravity.CENTER
                textSize = 0f; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bs * .34f); typeface = android.graphics.Typeface.DEFAULT_BOLD
                contentDescription = if (sign < 0) "Назад на ${K.seekStep} секунд" else "Вперёд на ${K.seekStep} секунд"
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = bs / 2f; setColor(0x26FFFFFF) }
                visibility = View.GONE
            }
            tv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { wake(); v.alpha = .55f }
                    MotionEvent.ACTION_UP -> { v.alpha = 1f; if (e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) Seek.seek(this, fg, sign * K.seekStep * 1000L) }
                    MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                true
            }
            row.addView(tv, LinearLayout.LayoutParams(bs * 6 / 5, bs).apply { leftMargin = d(2) })
            seekViews.add(tv)
        }
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
    /** Home = our launcher (MainActivity, singleTask; no service needed). v1.7.7: it used to open the old 1.7.0 screen. */
    fun home(c: Context) {
        try { c.startActivity(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Throwable) { NavAccess.act(AccessibilityService.GLOBAL_ACTION_HOME) }
    }
    const val HOW = "«Недавние» и плавающие кнопки ◁ ○ □ поверх других приложений работают через службу «Car Launcher — кнопки навигации».\n\n" +
        "Включить один раз:\n1. Нажмите «Открыть настройки».\n2. Спец. возможности (Accessibility) → «Car Launcher — кнопки навигации».\n3. Включите и подтвердите «Разрешить».\n4. Вернитесь кнопкой «Домой».\n\n" +
        "Служба только нажимает «Назад», «Домой» и «Недавние», показывает плавающие кнопки и узнаёт, какое приложение открыто (только его название — для кнопок перемотки −10/+10). Она не читает экран, не перехватывает кнопки руля и магнитолы и не меняет настройки. Выключается там же."
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
