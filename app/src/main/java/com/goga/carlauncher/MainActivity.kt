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
        const val HOME = 0; const val APPS = 1; const val DVR = 2; const val SETTINGS = 3; const val MEDIA = 4; const val CLEAN = 5; const val VOLTEST = 6
        /** menu width / dock height at «Обычный» size; v1.7.1 multiplied by [Prefs.railScale] (default «Крупный» +25 %). */
        const val RAIL_W = 112; const val DOCK_H = 92
    }
    private lateinit var content: FrameLayout
    private val screens = arrayOfNulls<Screen>(7)
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
        migrate()
        Syu.bind(this)   // v1.7.1 TEYES/FYT volume service, bound early so the first press already works
        Wallpaper.onCoverColor = { coverColorChanged() }
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

    /** v1.7.1 one-time: «Назад» / «Недавние» added to an existing menu (right after «Главная»). */
    private fun migrate() {
        if (Prefs.migrated and 1 == 0) {
            val l = Rail.items().toMutableList()
            val at = (l.indexOf("home") + 1).coerceAtLeast(0)
            if ("recents" !in l) l.add(at, "recents")
            if ("back" !in l) l.add(at, "back")
            Prefs.railItems = Rail.join(l)
            Prefs.migrated = Prefs.migrated or 1
        }
    }

    /** v1.7.1 «Цвет из обложки»: the new cover colour becomes the accent (one rebuild per track, only when it differs). */
    private val coverRebuild = Runnable { if (Themes.active().accent != Ui.accent) rebuild() }
    private fun coverColorChanged() {
        if (!Prefs.coverAccent) return
        AppRepo.main.removeCallbacks(coverRebuild); AppRepo.main.postDelayed(coverRebuild, 400)
    }

    /** Menu size multiplier → design px. */
    private fun r(v: Number): Int = u(v.toFloat() * Prefs.railScale)
    private val railW get() = Math.round(RAIL_W * Prefs.railScale)
    private val dockH get() = Math.round(DOCK_H * Prefs.railScale)

    /**
     * v1.7.0: scale from the REAL window (TEYES/FYT status bar takes part of the 720 px) and the menu arrangement:
     * the home layouts are designed for a 1168×720 content area; the side menu (112), the second volume strip (76) or the
     * bottom dock (92) are added to the design size, so nothing ever runs off the screen.
     */
    private var winW = 0; private var winH = 0
    fun designSize(): Pair<Int, Int> {
        val side = Prefs.railSide
        // v1.7.1: the volume buttons float over the content, they no longer take a strip of their own
        return if (side == "bottom") 1168 + 112 to 720 + dockH
        else 1168 + railW to 720
    }
    private fun computeScale() {
        val dm = resources.displayMetrics
        val w = if (winW > 0) winW else maxOf(dm.widthPixels, dm.heightPixels)
        val h = if (winH > 0) winH else minOf(dm.widthPixels, dm.heightPixels)
        val (dw, dh) = designSize()
        Ui.s = minOf(w / dw.toFloat(), h / dh.toFloat())
    }

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
        // v1.7.1: the badge lives in the menu / dock (it used to cover the clock at the top of the screen)
        // v1.7.1: optional spectrum bars along the left / right edges (never over the cover), under the volume buttons
        if (Prefs.musicFx == "bars") {
            area.addView(SideBars(this, true), FrameLayout.LayoutParams(u(64), Ui.MATCH, Gravity.START))
            area.addView(SideBars(this, false), FrameLayout.LayoutParams(u(64), Ui.MATCH, Gravity.END))
        }
        // v1.7.1: floating, semi-transparent volume buttons over the content (driver + passenger), no reserved strip
        addFloatingVolume(area)
        Glass.root = area
        area.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) Wallpaper.reload(this, r - l, b - t)
        }
        if (bottom) {
            root.addView(area, Ui.lin(Ui.MATCH, 0, 1f))
            root.addView(bar, Ui.lin(Ui.MATCH, u(dockH)))
        } else {
            if (side == "left") root.addView(bar, Ui.lin(u(railW), Ui.MATCH))
            root.addView(area, Ui.lin(0, Ui.MATCH, 1f))
            if (side != "left") root.addView(bar, Ui.lin(u(railW), Ui.MATCH))
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
            box.addView(iv, Ui.frame(r(32), r(32)))
        } else {
            iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            AppRepo.bindIcon(this, id.removePrefix(Rail.APP), null, iv)
            box.addView(iv, Ui.frame(r(38), r(38)))
        }
        col.addView(box, Ui.lin(r(76), r(48)))
        val tv = Ui.text(this, Math.round(13 * Prefs.railScale), Ui.TEXT3, Ui.MEDIUM, 2).apply { text = label; gravity = Gravity.CENTER; setLineSpacing(0f, 0.9f) }
        col.addView(tv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        if (screen >= 0) { rail.add(box to iv); railLabels.add(tv); railIdx.add(screen) }
        return col
    }

    /**
     * v1.7.1 ‹ ☀ › 🎨 — big round buttons (≥ 64 design px, «Крупный» 80): previous / next layout, day / night and the quick
     * panel «Вид» (background while music plays, colour from the cover). Column in the side menu, row in the dock.
     */
    private fun layoutCtl(horizontal: Boolean): View {
        val ctl = (if (horizontal) Ui.hbox(this) else Ui.vbox(this)).apply { gravity = Gravity.CENTER }
        val d = maxOf(64, Math.round(64 * Prefs.railScale))
        fun big(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
            background = Ui.pressableOval(Ui.SURFACE2, Ui.PRESSED); isClickable = true; contentDescription = desc
            setOnClickListener { onClick() }
            addView(Ui.icon(this@MainActivity, res, Ui.TEXT), Ui.frame(u(d * 0.46f), u(d * 0.46f)))
        }
        val gap = u(10)
        fun lp(first: Boolean) = Ui.lin(u(d), u(d)).apply { if (!first) { if (horizontal) leftMargin = gap else topMargin = gap } }
        ctl.addView(big(R.drawable.ic_prev_layout, "Предыдущая раскладка") { stepLayout(-1) }, lp(true))
        ctl.addView(big(if (dayNow()) R.drawable.ic_moon else R.drawable.ic_sun, "День / ночь") {
            Prefs.dayMode = if (dayNow()) "night" else "day"; rebuild()
        }, lp(false))
        ctl.addView(big(R.drawable.ic_next_layout, "Следующая раскладка") { stepLayout(1) }, lp(false))
        ctl.addView(big(R.drawable.ic_palette, "Вид: фон и цвет") { QuickPanel.show(this) }, lp(false))
        homeCtl = ctl
        return ctl
    }

    /** Volume button: tap = one step, hold = repeat. v1.7.1: semi-transparent, floats over the content. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun volButton(up: Boolean, size: Int): View {
        val b = Ui.iconButton(this, if (up) R.drawable.ic_vol_up else R.drawable.ic_vol_down, size, (size * 0.44f).toInt(), 0x8C000000.toInt(), Ui.TEXT, Ui.alpha(Ui.accent, 0.75f)) { Volume.step(this, up) }
        b.foreground = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(u(2), 0x40FFFFFF) }
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

    private fun volBlock(size: Int): LinearLayout {
        val blk = Ui.vbox(this).apply { gravity = Gravity.CENTER }
        blk.addView(volButton(true, size), Ui.lin(u(size), u(size)))
        blk.addView(volButton(false, size), Ui.lin(u(size), u(size)).apply { topMargin = u(14) })
        return blk
    }

    /** v1.7.1: «+ / −» pairs pinned to the content edges (driver left, passenger right), over everything, no layout space. */
    private fun addFloatingVolume(area: FrameLayout) {
        val vs = Prefs.volSide; val size = Prefs.volSize
        val v = when (Prefs.volPos) { "top" -> Gravity.TOP; "center" -> Gravity.CENTER_VERTICAL; else -> Gravity.BOTTOM }
        fun add(g: Int) = area.addView(volBlock(size), FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, g or v).apply {
            leftMargin = u(12); rightMargin = u(12); bottomMargin = u(if (v == Gravity.BOTTOM) 18 else 0); topMargin = u(if (v == Gravity.TOP) 70 else 0)
        })
        if (vs == "both" || vs == "left") add(Gravity.START)
        if (vs == "both" || vs == "right") add(Gravity.END)
    }

    /** «⚠ Мало места: 7 %» — a red button pinned in the menu; opens «Очистка памяти». */
    private fun makeBadge(): TextView = Ui.text(this, Math.round(14 * Prefs.railScale), Color.WHITE, Ui.BOLD, 3).apply {
        gravity = Gravity.CENTER; setPadding(r(6), r(8), r(6), r(8)); background = Ui.pressable(0xE6C62828.toInt(), r(16), 0xFFE53935.toInt()); visibility = View.GONE
        isClickable = true; contentDescription = "Мало места"; setOnClickListener { show(CLEAN) }
        lowBadge = this
    }

    private fun buildRail(): View {
        val bar = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xFF0A0A0C.toInt()) }
        bar.addView(makeBadge(), Ui.lin(Ui.MATCH, Ui.WRAP).apply { setMargins(r(8), r(8), r(8), 0) })
        val sc = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val list = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, u(12), 0, u(8)) }
        for (id in Rail.items()) list.addView(railItemView(id, false), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = if (id == "layout") r(6) else 0; bottomMargin = r(if (id == "layout") 16 else 10) })
        sc.addView(list, android.widget.FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        bar.addView(sc, Ui.lin(Ui.MATCH, 0, 1f))
        return bar
    }

    /** v1.7.0 bottom dock (macOS-like): items scroll sideways, volume pinned at the ends. */
    private fun buildDock(): View {
        val dock = Ui.hbox(this).apply { setBackgroundColor(0xFF0A0A0C.toInt()); setPadding(u(12), u(6), u(12), u(6)) }
        dock.addView(makeBadge(), Ui.lin(r(120), Ui.MATCH).apply { rightMargin = r(8) })
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val row = Ui.hbox(this).apply { gravity = Gravity.CENTER }
        for (id in Rail.items()) row.addView(railItemView(id, true), Ui.lin(if (id == "layout") Ui.WRAP else r(100), Ui.WRAP).apply { leftMargin = r(4); rightMargin = r(4) })
        hs.addView(row, android.widget.FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        dock.addView(hs, Ui.lin(0, Ui.MATCH, 1f))
        return dock
    }

    /** Settings opened from the menu start at the top; rebuilds after a change keep the scroll position. */
    fun openSettings() {
        if (current != SETTINGS) SettingsScreen.savedScroll = 0
        show(SETTINGS)
    }
    /** Back to Settings at the place it was left (from «Тест громкости» / «Очистка памяти»). */
    fun openSettingsKeep() = show(SETTINGS)

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
        // short: only the percentages ("Внутренний общий накопитель 7 %" → "7 %"), the full text is in «Очистка памяти»
        val pc = Regex("(\\d+) %").findAll(lowText ?: "").map { it.value }.toList()
        b.text = "⚠ Мало места: " + (if (pc.isNotEmpty()) pc.joinToString(", ") else (lowText ?: ""))
        b.visibility = if (current == HOME && lowText != null) View.VISIBLE else View.GONE
    }
    private fun dayNow() = Dash.light(this)

    /** Prev / next layout from the home screen (same list and order as Settings → Раскладка). */
    fun stepLayout(d: Int) {
        (screens[HOME] as? HomeScreen)?.takeIf { it.layoutId == Layouts.SCREENS.id }?.let { if (current != HOME) show(HOME); it.goPage(it.currentPage + d); return }
        val all = Layouts.ALL
        val k = all.indexOfFirst { it.id == Layouts.byId(Prefs.layout).id }
        val n = all[((k + d) % all.size + all.size) % all.size]
        Prefs.layout = n.id
        rebuild()
        android.widget.Toast.makeText(this, n.name, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun homeScreen(): HomeScreen? = screens[HOME] as? HomeScreen
    fun show(i: Int) {
        if (screens[current] != null && content.childCount > 0) screens[current]?.onHide()
        current = i
        val s = screens[i] ?: when (i) {
            HOME -> HomeScreen(this); APPS -> AppsScreen(this); DVR -> DvrScreen(this); MEDIA -> MediaScreen(this)
            CLEAN -> CleanScreen(this); VOLTEST -> VolTestScreen(this); else -> SettingsScreen(this)
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
        // v1.7.1: the floating volume sits over the home picture; list screens (settings, apps, …) step aside for it
        val vs = Prefs.volSide; val pad = u(Prefs.volSize + 20)
        // (the new screens keep the strips free too: nothing — cover, buttons — ever sits under the volume / spectrum bars)
        if (i == HOME && Prefs.layout != Layouts.SCREENS.id) content.setPadding(0, 0, 0, 0)
        else content.setPadding(if (vs == "both" || vs == "left") pad else 0, 0, if (vs == "both" || vs == "right") pad else 0, 0)
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
        super.onResume(); resumed = true; Updater.host = java.lang.ref.WeakReference(this); MiniPlayer.setLauncherInFront(true); FloatNav.setLauncherInFront(true); screens[current]?.onShow()
        FloatNav.sync(this)
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
    override fun onPause() { super.onPause(); resumed = false; MiniPlayer.setLauncherInFront(false); FloatNav.setLauncherInFront(false); screens[current]?.onHide() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing HOME while already on top goes back to the home screen.
        if (Intent.ACTION_MAIN == intent.action && current != 0) show(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (current == VOLTEST) openSettingsKeep() else if (current != 0) show(0) }

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
