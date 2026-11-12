package com.goga.carlauncher

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ui.u

abstract class Screen(val a: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}
    open fun onPackagesChanged() {}
}

class MainActivity : Activity() {
    companion object {
        const val REQ_BG = 42
        /** screen indices (rail order differs: Главная, Прилож., Медиа, Записи, Настр.) */
        const val HOME = 0; const val APPS = 1; const val DVR = 2; const val SETTINGS = 3; const val MEDIA = 4
        const val RAIL_W = 112; const val STRIP_W = 76; const val DOCK_H = 92
    }
    private lateinit var content: FrameLayout
    private val screens = arrayOfNulls<Screen>(5)
    private val rail = ArrayList<Pair<FrameLayout, ImageView>>()
    private val railLabels = ArrayList<TextView>()
    private val railIdx = ArrayList<Int>()
    var current = 0
        private set
    var glow: AmbientGlow? = null
        private set
    private var resumed = false

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            AppRepo.invalidate()
            screens.forEach { it?.onPackagesChanged() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        Prefs.init(this)
        Gallery.migrate(this)
        Gallery.maybeRotate(this, launch = true)
        Prefs.stoppedAt = 0L   // this start already counted as a launch
        applyTheme()
        computeScale()
        build()
        show(0)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        if (!Prefs.askedPerms) { Prefs.askedPerms = true; requestPerms() }
        AppRepo.loadAsync(this) {}
    }

    private fun applyTheme() {
        Themes.current = Themes.active()
        Ui.accent = Themes.current.accent
    }

    private lateinit var wallIv: ImageView
    private lateinit var dimView: View
    private val wallListener: () -> Unit = {
        wallIv.setImageBitmap(Wallpaper.full)
        Glass.invalidateAll()
    }

    /**
     * v1.7.0: scale from the REAL window (TEYES/FYT status bar takes part of the 720 px) and the menu arrangement:
     * the home layouts are designed for a 1168×720 content area; the side menu (112), the second volume strip (76) or the
     * bottom dock (92) are added to the design size, so nothing ever runs off the screen.
     */
    private var winW = 0; private var winH = 0
    fun designSize(): Pair<Int, Int> {
        val side = Prefs.railSide
        return if (side == "bottom") 1168 + 112 to 720 + DOCK_H
        else 1168 + RAIL_W + (if (volStrip() != null) STRIP_W else 0) to 720
    }
    private fun computeScale() {
        val dm = resources.displayMetrics
        val w = if (winW > 0) winW else maxOf(dm.widthPixels, dm.heightPixels)
        val h = if (winH > 0) winH else minOf(dm.widthPixels, dm.heightPixels)
        val (dw, dh) = designSize()
        Ui.s = minOf(w / dw.toFloat(), h / dh.toFloat())
    }
    /** Side of the extra volume strip opposite the menu, or null. */
    fun volStrip(): String? {
        val side = Prefs.railSide; if (side == "bottom") return null
        val other = if (side == "left") "right" else "left"
        return if (Prefs.volSide == "both" || Prefs.volSide == other) other else null
    }
    private fun volOnRail(): Boolean = Prefs.railSide == "bottom" || Prefs.volSide == "both" || Prefs.volSide == Prefs.railSide

    fun requestAudioPerm() { if (!has(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }

    fun requestPerms() {
        val need = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 2) glow?.settingsChanged()
        if (resumed) { screens[current]?.onHide(); screens[current]?.onShow() }
    }

    private fun build() {
        rail.clear(); railLabels.clear(); railIdx.clear()
        val side = Prefs.railSide
        val bottom = side == "bottom"
        val root = (if (bottom) Ui.vbox(this) else Ui.hbox(this)).apply { setBackgroundColor(Ui.BG); if (!bottom) gravity = Gravity.TOP }
        val bar = if (bottom) buildDock() else buildRail()
        // Content area: wallpaper → dim → current screen. The menu stays solid.
        val area = FrameLayout(this)
        wallIv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(Wallpaper.full) }
        area.addView(wallIv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        dimView = View(this).apply { setBackgroundColor(Themes.current.wallDim) }
        area.addView(dimView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        content = FrameLayout(this)
        area.addView(content, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        // soft music glow: two thin strips at the top / bottom edge, above the content, never touchable
        val gb = GlowEdge(this, true); val gt = GlowEdge(this, false)
        area.addView(gb, FrameLayout.LayoutParams(Ui.MATCH, u(56), Gravity.BOTTOM))
        area.addView(gt, FrameLayout.LayoutParams(Ui.MATCH, u(28), Gravity.TOP))
        glow?.setActive(false)
        glow = AmbientGlow(this, listOf(gb, gt))
        // v1.7.0: «мало места» badge on the home screen (any volume < 10 % free — the DVR / Cam REC would stop recording)
        lowBadge = Ui.text(this, 15, Color.WHITE, Ui.MEDIUM, 1).apply {
            setPadding(u(14), u(6), u(14), u(6)); background = Ui.round(0xE6C62828.toInt(), u(16)); visibility = View.GONE
            isClickable = true; setOnClickListener { show(DVR) }
        }
        area.addView(lowBadge, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = u(6) })
        Glass.root = area
        area.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) Wallpaper.reload(this, r - l, b - t)
        }
        if (bottom) {
            root.addView(area, Ui.lin(Ui.MATCH, 0, 1f))
            root.addView(bar, Ui.lin(Ui.MATCH, u(DOCK_H)))
        } else {
            val strip = volStrip()?.let { buildVolStrip() }
            if (side == "left") root.addView(bar, Ui.lin(u(RAIL_W), Ui.MATCH)) else strip?.let { root.addView(it, Ui.lin(u(STRIP_W), Ui.MATCH)) }
            root.addView(area, Ui.lin(0, Ui.MATCH, 1f))
            if (side == "left") strip?.let { root.addView(it, Ui.lin(u(STRIP_W), Ui.MATCH)) } else root.addView(bar, Ui.lin(u(RAIL_W), Ui.MATCH))
        }
        // real window size known after the first layout → rescale once if the status bar / navigation bar took space
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (w > 0 && h > 0 && (w != winW || h != winH)) {
                winW = w; winH = h
                val old = Ui.s; computeScale()
                if (kotlin.math.abs(old - Ui.s) > 0.004f) root.post { rebuild() } else Ui.s = old
            }
        }
        setContentView(root)
    }

    private fun railItemView(id: String, horizontal: Boolean): View {
        if (id == "layout") return layoutCtl(horizontal)
        val b = Rail.builtin(id)
        val screen = when (id) { "home" -> HOME; "apps" -> APPS; "media" -> MEDIA; "dvr" -> DVR; "settings" -> SETTINGS; else -> -1 }
        val label = if (b != null) b.label else Rail.label(this, id)
        val col = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; contentDescription = label.replace('\n', ' '); setOnClickListener { Rail.run(this@MainActivity, id) } }
        val box = FrameLayout(this)
        val iv: ImageView
        if (b != null) {
            iv = Ui.icon(this, b.icon, if (id == "phone") Ui.accent else Ui.TEXT2)
            box.addView(iv, Ui.frame(u(32), u(32)))
        } else {
            iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            AppRepo.bindIcon(this, id.removePrefix(Rail.APP), null, iv)
            box.addView(iv, Ui.frame(u(38), u(38)))
        }
        col.addView(box, Ui.lin(u(76), u(48)))
        val tv = Ui.text(this, 13, Ui.TEXT3, Ui.MEDIUM, 2).apply { text = label; gravity = Gravity.CENTER; setLineSpacing(0f, 0.9f) }
        col.addView(tv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        if (screen >= 0) { rail.add(box to iv); railLabels.add(tv); railIdx.add(screen) }
        return col
    }

    /** ‹ ☀ › — previous / next layout + day / night (home screen only). */
    private fun layoutCtl(horizontal: Boolean): View {
        val ctl = Ui.hbox(this).apply { gravity = Gravity.CENTER }
        fun small(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
            background = Ui.pressableOval(Ui.SURFACE, Ui.PRESSED); isClickable = true; contentDescription = desc
            setOnClickListener { onClick() }
            addView(Ui.icon(this@MainActivity, res, Ui.TEXT2), Ui.frame(u(22), u(22)))
        }
        ctl.addView(small(R.drawable.ic_prev_layout, "Предыдущая раскладка") { stepLayout(-1) }, Ui.lin(u(34), u(34)))
        ctl.addView(small(if (dayNow()) R.drawable.ic_moon else R.drawable.ic_sun, "День / ночь") {
            Prefs.dayMode = if (dayNow()) "night" else "day"; rebuild()
        }, Ui.lin(u(34), u(34)).apply { leftMargin = u(3); rightMargin = u(3) })
        ctl.addView(small(R.drawable.ic_next_layout, "Следующая раскладка") { stepLayout(1) }, Ui.lin(u(34), u(34)))
        homeCtl = ctl
        return ctl
    }

    /** Volume button: tap = one step, hold = repeat. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun volButton(up: Boolean, size: Int): View {
        val b = Ui.iconButton(this, if (up) R.drawable.ic_vol_up else R.drawable.ic_vol_down, size, (size * 0.46f).toInt(), Ui.SURFACE2, Ui.TEXT) { Volume.step(this, up) }
        b.contentDescription = if (up) "Громче" else "Тише"
        val h = android.os.Handler(mainLooper)
        val rep = object : Runnable { override fun run() { Volume.step(this@MainActivity, up); h.postDelayed(this, 140) } }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.isPressed = true; Volume.step(this, up); h.postDelayed(rep, 450) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(rep) }
            }
            true
        }
        return b
    }

    private fun volBlock(horizontal: Boolean, size: Int): LinearLayout {
        val blk = (if (horizontal) Ui.hbox(this) else Ui.vbox(this)).apply { gravity = Gravity.CENTER }
        val first = volButton(!horizontal, size); val second = volButton(horizontal, size)   // vertical: + on top; horizontal: − then +
        blk.addView(first, Ui.lin(u(size), u(size)))
        blk.addView(second, Ui.lin(u(size), u(size)).apply { if (horizontal) leftMargin = u(10) else topMargin = u(10) })
        return blk
    }

    private fun buildRail(): View {
        val bar = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xFF0A0A0C.toInt()) }
        val sc = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val list = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, u(12), 0, u(8)) }
        for (id in Rail.items()) list.addView(railItemView(id, false), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(if (id == "layout") 12 else 8) })
        sc.addView(list, android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        bar.addView(sc, Ui.lin(Ui.MATCH, 0, 1f))
        if (volOnRail()) bar.addView(volBlock(false, 64), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8); bottomMargin = u(16) })
        return bar
    }

    /** The second, passenger-side volume pair (when the menu is on the other side). */
    private fun buildVolStrip(): View {
        val st = Ui.vbox(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xFF0A0A0C.toInt()) }
        st.addView(volBlock(false, 60), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(16) })
        return st
    }

    /** v1.7.0 bottom dock (macOS-like): items scroll sideways, volume pinned at the ends. */
    private fun buildDock(): View {
        val dock = Ui.hbox(this).apply { setBackgroundColor(0xFF0A0A0C.toInt()); setPadding(u(12), u(6), u(12), u(6)) }
        val vs = Prefs.volSide
        if (vs == "both" || vs == "left") dock.addView(volBlock(true, 62), Ui.lin(Ui.WRAP, Ui.MATCH).apply { rightMargin = u(12) })
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val row = Ui.hbox(this).apply { gravity = Gravity.CENTER }
        for (id in Rail.items()) row.addView(railItemView(id, true), Ui.lin(if (id == "layout") Ui.WRAP else u(100), Ui.WRAP).apply { leftMargin = u(4); rightMargin = u(4) })
        hs.addView(row, android.widget.FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        dock.addView(hs, Ui.lin(0, Ui.MATCH, 1f))
        if (vs == "both" || vs == "right") dock.addView(volBlock(true, 62), Ui.lin(Ui.WRAP, Ui.MATCH).apply { leftMargin = u(12) })
        return dock
    }

    /** Settings opened from the menu start at the top; rebuilds after a change keep the scroll position. */
    fun openSettings() {
        if (current != SETTINGS) SettingsScreen.savedScroll = 0
        show(SETTINGS)
    }

    private var homeCtl: LinearLayout? = null
    private var lowBadge: TextView? = null
    private var lowText: String? = null
    fun checkStorage() {
        val app = applicationContext
        AppRepo.exec.execute {
            val w = Storage.lowWarning(app)
            AppRepo.main.post { lowText = w; updateBadge() }
        }
    }
    private fun updateBadge() {
        val b = lowBadge ?: return
        b.text = "⚠ Мало места: " + (lowText ?: "")
        b.visibility = if (current == HOME && lowText != null) View.VISIBLE else View.GONE
    }
    private fun dayNow() = Dash.light(this)

    /** Prev / next layout from the home screen (same list and order as Settings → Раскладка). */
    fun stepLayout(d: Int) {
        val all = Layouts.ALL
        val k = all.indexOfFirst { it.id == Layouts.byId(Prefs.layout).id }
        val n = all[((k + d) % all.size + all.size) % all.size]
        Prefs.layout = n.id
        rebuild()
        android.widget.Toast.makeText(this, n.name, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun show(i: Int) {
        if (screens[current] != null && content.childCount > 0) screens[current]?.onHide()
        current = i
        val s = screens[i] ?: when (i) {
            HOME -> HomeScreen(this); APPS -> AppsScreen(this); DVR -> DvrScreen(this); MEDIA -> MediaScreen(this); else -> SettingsScreen(this)
        }.also { screens[i] = it }
        content.removeAllViews()
        (s.view.parent as? FrameLayout)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        rail.forEachIndexed { k, (box, iv) ->
            val sel = railIdx[k] == i
            box.background = if (sel) Ui.round(0xFF232326.toInt(), u(18)) else null
            iv.imageTintList = ColorStateList.valueOf(if (sel) Ui.accent else Ui.TEXT2)
            railLabels[k].setTextColor(if (sel) Ui.TEXT else Ui.TEXT3)
        }
        homeCtl?.visibility = if (i == HOME) View.VISIBLE else View.INVISIBLE
        updateBadge()
        dimView.setBackgroundColor(if (i == HOME && (s as? HomeScreen)?.light == true) Themes.LIGHT_VEIL else Themes.current.wallDim)
        if (resumed) s.onShow()
    }

    /** Rebuild all screens, e.g. after an accent colour change. */
    fun rebuild() {
        screens[current]?.onHide()
        for (k in screens.indices) screens[k] = null
        applyTheme()
        computeScale()
        build()
        show(current)
        Wallpaper.reload(this, Glass.root?.width ?: 0, Glass.root?.height ?: 0)
    }

    /** Automatic wallpaper rotation (cheap: a date comparison on launch / resume / once a minute on the home screen). */
    fun checkRotation(launch: Boolean) {
        if (Gallery.maybeRotate(this, launch)) Wallpaper.reload(this, Glass.root?.width ?: 0, Glass.root?.height ?: 0, force = true)
    }

    /** Re-decodes the wallpaper after the gallery / choice changed. */
    fun wallpaperChanged() {
        Wallpaper.reload(this, Glass.root?.width ?: 0, Glass.root?.height ?: 0, force = true)
    }

    // ---- local picture picker (USB / SD / files) → gallery ----
    fun pickWallpaper() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(open, REQ_BG); return } catch (_: Exception) {}
        val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(Intent.createChooser(get, "Выберите картинку"), REQ_BG) } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Нет приложения для выбора картинок", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Media.REQ_PLAY) {
            // VLC / MX return the stop position (also on RESULT_CANCELED for some builds) → per-file resume
            Media.onResult(this, data)
            (screens[MEDIA] as? MediaScreen)?.refresh()
            return
        }
        if (requestCode != REQ_BG || resultCode != RESULT_OK) return
        val uris = ArrayList<android.net.Uri>()
        data?.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val app = applicationContext
        AppRepo.exec.execute {
            // copied into app storage right away (≤1280×720 JPEG) — the USB stick can be removed afterwards
            var last: String? = null; var n = 0
            for (u in uris) { val id = try { Gallery.addUri(app, u) } catch (e: Throwable) { null }; if (id != null) { last = id; n++ } }
            AppRepo.main.post {
                if (last != null) { Prefs.wallpaper = last; wallpaperChanged(); rebuild() }
                android.widget.Toast.makeText(this, if (n > 0) "Добавлено в галерею: $n" else "Не удалось открыть картинку", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume(); resumed = true; Updater.host = java.lang.ref.WeakReference(this); MiniPlayer.setLauncherInFront(true); screens[current]?.onShow()
        musicHint()
        checkStorage()
    }

    private var hintShown = false
    /** v1.7.0: without «Доступ к уведомлениям» the launcher can't see Яндекс Музыку / BT / радио → explain once per start (max 3 times). */
    private fun musicHint() {
        if (hintShown || Prefs.notifHints >= 3 || MediaHub.listenerEnabled(this)) return
        hintShown = true; Prefs.notifHints = Prefs.notifHints + 1
        val d = android.app.AlertDialog.Builder(this, R.style.Dialog).setTitle("Доступ к музыке")
            .setMessage("Чтобы на главном экране показывалась и переключалась любая музыка — Яндекс Музыка, Apple Music, радио, музыка с телефона по Bluetooth, — " +
                "включите для Car Launcher «Доступ к уведомлениям».\n\nЛаунчер не читает и не показывает ваши уведомления — Android просто так называет доступ к плеерам.")
            .setPositiveButton("Открыть настройку") { _, _ ->
                AppRepo.startSafe(this, Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(android.provider.Settings.ACTION_SETTINGS))
            }.setNegativeButton("Позже", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }
    override fun onPause() { super.onPause(); resumed = false; MiniPlayer.setLauncherInFront(false); screens[current]?.onHide() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing HOME while already on top goes back to the home screen.
        if (Intent.ACTION_MAIN == intent.action && current != 0) show(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (current != 0) show(0) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = Ui.s
        computeScale()
        if (old != Ui.s) rebuild()
    }

    override fun onStart() {
        super.onStart()
        Wallpaper.addListener(wallListener)
        // unit woke up after a long sleep (FYT units suspend on ACC off) → counts as a new "launch" for rotation
        val off = Prefs.stoppedAt
        checkRotation(off > 0 && System.currentTimeMillis() - off > Gallery.WAKE_GAP_MS)
        // bitmaps may have been released by onTrimMemory while another app was in front
        if (Wallpaper.full == null) Glass.root?.let { Wallpaper.reload(this, it.width, it.height, force = true) }
    }

    /**
     * Low memory: drop everything that can be rebuilt. UI hidden → map tiles + icon cache;
     * system under pressure → also wallpaper/blur bitmaps (reloaded in onStart, ≈50 ms on a worker thread).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_RUNNING_LOW) {
            MapTileView.trim()
            AppRepo.trimIcons()
        }
        if (level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            Wallpaper.release(); RoadArt.release()
            if (::wallIv.isInitialized) wallIv.setImageDrawable(null)
        }
    }
    override fun onStop() { Wallpaper.removeListener(wallListener); Prefs.stoppedAt = System.currentTimeMillis(); super.onStop() }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
