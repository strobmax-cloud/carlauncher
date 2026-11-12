package com.goga.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ui.u
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen. Data (clock, GPS, trip, media session) is shared; the arrangement comes from Prefs.layout:
 * cockpit / map / media / arc / widgets / tiles. Each builder creates its own views and registers them as sinks.
 */
class HomeScreen(a: MainActivity) : Screen(a) {
    companion object { const val TILE_ROW = 112; const val TILE_H = TILE_ROW * 2 + 16 }
    private val h = Handler(Looper.getMainLooper())
    private val ru = Locale("ru")
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", ru)
    private val T get() = if (light) lightTheme else Themes.current
    private val lightTheme by lazy { Themes.lightOf(Themes.active()) }
    val layoutId: String = Layouts.byId(Prefs.layout).id

    // ---- sinks (filled by the layout builder) ----
    private val timeViews = ArrayList<TextView>()
    private val dateViews = ArrayList<TextView>()
    private val analogClocks = ArrayList<AnalogClockView>()
    private val speedSinks = ArrayList<SpeedSink>()
    private val compasses = ArrayList<CompassView>()
    private val maps = ArrayList<MapTileView>()
    private val tickUpdaters = ArrayList<() -> Unit>()
    private var weekRow: LinearLayout? = null
    private var weekDay = -1
    private var dayBar: BarView? = null
    private var dayTv: TextView? = null
    private var tilesBox: LinearLayout? = null

    // ---- media views (every layout creates all of them; unused ones simply stay detached) ----
    private lateinit var mediaCard: View
    private lateinit var artIv: ImageView
    private lateinit var srcTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var playIv: ImageView
    private lateinit var progBar: BarView
    private lateinit var posTv: TextView
    private lateinit var durTv: TextView
    private lateinit var progBox: View
    private var artPad = 0.28f    // placeholder padding as a fraction of the art size
    private var idle = true

    override val view: View by lazy { build() }

    /** v1.6.1: day look for the classic layouts too (Prefs.dayMode: auto / day / night, toggled from the rail). */
    val light: Boolean = LightPass.applies(layoutId) && Dash.light(a)

    private fun build(): View {
        val saved = Themes.current
        if (light) Themes.current = Themes.lightOf(saved)
        try {
            val v = buildLayout()
            if (light) LightPass.apply(v)
            if (LightPass.applies(layoutId) && Prefs.dayMode == "auto") tickUpdaters.add { if (Dash.light(a) != light) h.post { a.rebuild() } }
            return v
        } finally { Themes.current = saved }
    }

    private fun buildLayout(): View = when (layoutId) {
        "screens" -> buildScreens()
        "cockpit" -> buildCockpit()
        "map" -> buildMap()
        "media" -> buildMediaLayout()
        "arc" -> buildArc()
        "widgets" -> buildWidgets()
        "minimal" -> buildMinimal()
        "smart" -> buildSmart()
        "premium" -> buildPremium()
        "grand" -> buildGrand()
        "sport" -> buildSport()
        "cluster" -> buildCluster()
        "chrono" -> buildChrono()
        "radial" -> buildRadial()
        "showcase" -> buildShowcase()
        else -> buildTilesLayout()
    }

    // ====================================================================================
    // shared pieces
    // ====================================================================================
    /** Small caps labels on dark cards (Bento's dateFg is black, meant for its orange clock block). */
    private val labelFg get() = if (T.clockBg != 0) Ui.alpha(Color.WHITE, 0.7f) else Ui.alpha(T.dateFg, 0.9f)
    private val playFg get() = if (T.id == "bento") Color.BLACK else Color.WHITE

    private fun clockText(size: Int, color: Int = T.clockFg, autosizeMin: Int = 0): TextView =
        Ui.text(a, size, color, T.clockTypeface).apply {
            letterSpacing = -0.02f; ellipsize = null
            if (autosizeMin > 0) setAutoSizeTextTypeUniformWithConfiguration(u(autosizeMin), u(size), 2, android.util.TypedValue.COMPLEX_UNIT_PX)
            timeViews.add(this)
        }

    private fun dateText(size: Int, color: Int = T.dateFg): TextView = Ui.text(a, size, color, Ui.MEDIUM).also { dateViews.add(it) }

    /** Creates the media text views with the given sizes. */
    private fun mediaTexts(src: Int, title: Int, artist: Int, titleLines: Int = 2, fg: Int = Color.WHITE, titleFace: android.graphics.Typeface = Ui.BOLD) {
        srcTv = Ui.text(a, src, Ui.alpha(fg, 0.6f), Ui.MEDIUM).apply { letterSpacing = 0.1f }
        titleTv = Ui.text(a, title, fg, titleFace, titleLines).apply { setLineSpacing(0f, 1.05f) }
        artistTv = Ui.text(a, artist, Ui.alpha(fg, 0.7f), Ui.SANS, if (titleLines > 1) 2 else 1)
    }

    /** v1.6.3: road scene with its own time of day; checked every tick, repainted only when day ↔ night flips. */
    private fun road(accent: Int, light: Boolean, carH: Float, maxCarW: Float, horizon: Float, ground: Float): RoadView =
        RoadArt.view(a, accent, light, carH, maxCarW, horizon, ground).also { v -> tickUpdaters.add { v.check() } }

    /** v1.6.3: right / main media card shows YouTube (its session, «Рядом» / «На весь экран») instead of music. */
    private val yt get() = Prefs.rightCard == "youtube"

    private fun mediaArt(square: Boolean, radius: Int): ImageView {
        // v1.6.2: the cover view rounds / circles and shadows the art itself (no hard square cut-outs, min 12 dp radius)
        artIv = CoverView(a, square).apply { radiusPx = u(maxOf(radius, 12)).toFloat() }
        artIv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (idle && (r - l != or - ol || b - t != ob - ot)) showArtPlaceholder() }
        showArtPlaceholder()
        return artIv
    }

    private fun progress(times: Boolean, height: Int = 5, fg: Int = Color.WHITE, fill: Int = fg): View {
        val pb = Ui.vbox(a)
        progBar = BarView(a, fill, Ui.alpha(fg, 0.2f))
        pb.addView(progBar, Ui.lin(Ui.MATCH, u(height)))
        posTv = Ui.text(a, 15, Ui.alpha(fg, 0.6f)); durTv = Ui.text(a, 15, Ui.alpha(fg, 0.6f)).apply { gravity = Gravity.END }
        if (times) {
            val row = Ui.hbox(a)
            row.addView(posTv, Ui.lin(0, Ui.WRAP, 1f)); row.addView(durTv, Ui.lin(0, Ui.WRAP, 1f))
            pb.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        }
        progBox = pb; pb.visibility = View.INVISIBLE
        return pb
    }

    private fun controls(side: Int, play: Int, gap: Int, sideIcon: Int = 36, playIcon: Int = 44,
                         fg: Int = Color.WHITE, playBg: Int = T.accent, playIconFg: Int = playFg): LinearLayout {
        val ctr = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val btnBg = Ui.alpha(fg, if (fg == Color.WHITE) 0.14f else 0.07f); val btnPr = Ui.alpha(fg, 0.28f)
        if (yt) {   // YouTube card: «Рядом» ⏯ «На весь экран»
            fun ytBtn(icon: Int, label: String, adjacent: Boolean): View = Ui.iconButton(a, icon, side, (sideIcon * 0.8f).toInt(), btnBg, fg, btnPr) { YouTube.open(a, adjacent) }
                .apply { contentDescription = label }
            fun labeled(icon: Int, label: String, adjacent: Boolean): View {
                if (side < 54) return ytBtn(icon, label, adjacent)
                val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
                col.addView(ytBtn(icon, label, adjacent))
                col.addView(Ui.text(a, 12, Ui.alpha(fg, 0.75f), Ui.MEDIUM).apply { text = label; gravity = Gravity.CENTER; maxLines = 1 }, Ui.lin(u(side + 34), Ui.WRAP).apply { topMargin = u(3) })
                return col
            }
            ctr.addView(labeled(R.drawable.ic_split, "Рядом", true))
            val p = Ui.iconButton(a, R.drawable.ic_play, play, playIcon, playBg, playIconFg, Ui.blend(playBg, 0x40000000)) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            playIv = p.getChildAt(0) as ImageView
            ctr.addView(p, LinearLayout.LayoutParams(u(play), u(play)).apply { leftMargin = u(gap); rightMargin = u(gap) })
            ctr.addView(labeled(R.drawable.ic_fullscreen, "На весь экран", false))
            return ctr
        }
        ctr.addView(Ui.iconButton(a, R.drawable.ic_prev, side, sideIcon, btnBg, fg, btnPr) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        val p = Ui.iconButton(a, R.drawable.ic_play, play, playIcon, playBg, playIconFg, Ui.blend(playBg, 0x40000000)) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        playIv = p.getChildAt(0) as ImageView
        ctr.addView(p, (p.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = u(gap); rightMargin = u(gap) })
        ctr.addView(Ui.iconButton(a, R.drawable.ic_next, side, sideIcon, btnBg, fg, btnPr) { media(KeyEvent.KEYCODE_MEDIA_NEXT) })
        return ctr
    }

    private fun clickableMedia(v: View) { mediaCard = v; v.isClickable = true }

    /** Text speed readout registered as a sink. */
    private inner class TextSpeed(val num: TextView, val status: TextView?, val fg: Int, val bar: BarView? = null) : SpeedSink {
        override fun setSpeed(kmh: Int?, status: String) {
            num.text = kmh?.toString() ?: "—"
            num.setTextColor(if (kmh == null) Ui.alpha(fg, 0.35f) else fg)
            this.status?.text = status
            bar?.fraction = (kmh ?: 0) / 200f
        }
    }

    private fun gpsClick(v: View) = v.setOnClickListener { if (!a.has(Manifest.permission.ACCESS_FINE_LOCATION)) a.requestPerms() }

    // ---- app entries ----
    /** Most layouts have room for 8 tiles; the roomy ones pass 9 (the optional «Флешка» tile). */
    private fun visibleTiles(max: Int = 8) = Tiles.ALL.indices.filter { Prefs.tileVisible(it) }.take(max)

    private fun tileIcon(i: Int): ImageView {
        val r = Tiles.resolve(a, i)
        val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (Tiles.useRealIcon(r.pkg)) AppRepo.bindIcon(a, r.pkg!!, null, iv, Tiles.fallbackArt(i, r.pkg))
        else iv.setImageResource(Tiles.fallbackArt(i, r.pkg))
        if (r.pkg == null) iv.alpha = 0.45f
        return iv
    }

    private fun tileTitle(i: Int): String {
        val r = Tiles.resolve(a, i)
        return if (r.custom && r.pkg != null) Tiles.label(a, r.pkg) else Tiles.ALL[i].title
    }

    private fun bindTile(v: View, i: Int) {
        v.isClickable = true; v.isLongClickable = true
        v.setOnClickListener {
            val p = Tiles.resolve(a, i).pkg
            if (p == null) Pickers.pickApp(a, i) { rebuildApps() }
            else if (p == Tiles.INTERNAL_MEDIA) a.show(MainActivity.MEDIA)
            else if (i == 0 || Nav.isNav(p)) Nav.open(a, p)       // navigator → mini player over it (if enabled)
            else AppRepo.launch(a, p)
        }
        v.setOnLongClickListener { Pickers.pickApp(a, i) { rebuildApps() }; true }
    }

    /** Icon (+ optional label). round = icon clipped to a circle. */
    private fun appIcon(i: Int, icon: Int, label: Int, round: Boolean = false, labelColor: Int = Ui.TEXT): LinearLayout {
        val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val iv = tileIcon(i)
        if (round) { iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; iv.background = Ui.oval(0x33FFFFFF); iv.clipToOutline = true; iv.scaleType = ImageView.ScaleType.CENTER_CROP }
        col.addView(iv, Ui.lin(u(icon), u(icon)))
        if (label > 0) col.addView(Ui.text(a, label, labelColor, Ui.MEDIUM).apply {
                text = tileTitle(i); gravity = Gravity.CENTER
                if (round) setShadowLayer(u(6).toFloat(), 0f, u(1).toFloat(), 0x99000000.toInt())   // legible over any wallpaper
            },
            Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        bindTile(col, i)
        return col
    }

    private fun allAppsIcon(icon: Int, label: Int, round: Boolean, bg: Int = 0x40FFFFFF): LinearLayout {
        val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener { a.show(1) } }
        val f = FrameLayout(a).apply { background = if (round) Ui.oval(bg) else Ui.round(bg, u(icon * 0.23f)) }
        f.addView(Ui.icon(a, R.drawable.ic_apps, Color.WHITE), Ui.frame(u(icon * 0.45f), u(icon * 0.45f)))
        col.addView(f, Ui.lin(u(icon), u(icon)))
        if (label > 0) col.addView(Ui.text(a, label, Ui.TEXT, Ui.MEDIUM).apply { text = "Все"; gravity = Gravity.CENTER; setShadowLayer(u(6).toFloat(), 0f, u(1).toFloat(), 0x99000000.toInt()) }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        return col
    }

    private var appsRebuild: (() -> Unit)? = null
    private fun rebuildApps() { if (light) a.rebuild() else appsRebuild?.invoke() ?: a.rebuild() }


    // ====================================================================================
    // v1.5 — navigator card (replaces the OSM map) and quick navigator buttons
    // ====================================================================================
    private class NavStyle(val fg: Int, val fg2: Int, val btn: Int, val btnPr: Int, val accent: Int, val radius: Int,
                           val face: Typeface = Ui.MEDIUM, val upper: Boolean = false, val glass: Boolean = false, val chamfer: Boolean = false)
    private class NavEntry(val pkg: String?, val label: String, val split: Boolean)

    private fun playingPkg(): String? = controller?.packageName

    /**
     * v1.7.0: exactly ONE entry — «Навигатор» = the default navigator, the music is built in (Nav.open: mini player /
     * split / resume). Installed navigators are no longer added as separate buttons; «Навигатор + музыка» is gone.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun navEntries(withSplit: Boolean = true, max: Int = 3): List<NavEntry> {
        val d = Nav.defaultNav(a)
        return listOf(NavEntry(d, if (d != null) "Навигатор · " + Nav.short(a, d) else "Выбрать навигатор", false))
    }

    private fun navIcon(e: NavEntry, st: NavStyle): View =
        if (e.split || e.pkg == null) Ui.icon(a, if (e.split) R.drawable.ic_split else R.drawable.ic_nav, st.accent)
        else ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; AppRepo.bindIcon(a, e.pkg, null, this, Tiles.brand(e.pkg).takeIf { it != 0 } ?: R.drawable.tile_nav) }

    private fun bindNav(v: View, e: NavEntry) {
        v.isClickable = true
        v.setOnClickListener {
            when { e.split -> Nav.split(a, playingPkg()); e.pkg != null -> Nav.open(a, e.pkg); else -> Pickers.pickApp(a, 0) { a.rebuild() } }
        }
        if (e.pkg != null && !e.split) {
            v.isLongClickable = true
            v.setOnLongClickListener {
                Prefs.navDefault = e.pkg
                android.widget.Toast.makeText(a, "Навигатор по умолчанию: " + Nav.name(a, e.pkg), android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun navBg(v: View, st: NavStyle) = when {
        st.chamfer -> v.background = ChamferDrawable(st.btn, 0x33FFFFFF, 0, u(14).toFloat(), st.btnPr)
        st.glass -> Ui.card(v, press = true, radius = st.radius)
        else -> v.background = Ui.pressable(st.btn, u(st.radius), st.btnPr)
    }

    private fun navLabel(e: NavEntry, st: NavStyle) = if (st.upper) e.label.uppercase(ru) else e.label

    /** Pill: icon + label. */
    private fun navPill(e: NavEntry, st: NavStyle, height: Int, icon: Int, text: Int): LinearLayout {
        val p = Ui.hbox(a).apply { setPadding(u(14), 0, u(18), 0); minimumHeight = u(height) }
        navBg(p, st)
        p.addView(navIcon(e, st), Ui.lin(u(icon), u(icon)))
        p.addView(Ui.text(a, text, st.fg, st.face).apply { this.text = navLabel(e, st) }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(12) })
        bindNav(p, e)
        return p
    }

    /** Tile: icon above the label (takes the place of the trip cards). */
    private fun navTile(e: NavEntry, st: NavStyle, icon: Int, text: Int): LinearLayout {
        val b = Ui.vbox(a).apply { gravity = Gravity.CENTER; setPadding(u(8), u(10), u(8), u(10)) }
        navBg(b, st)
        b.addView(navIcon(e, st), Ui.lin(u(icon), u(icon)))
        b.addView(Ui.text(a, text, st.fg, st.face, 2).apply { this.text = navLabel(e, st); gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        bindNav(b, e)
        return b
    }

    private fun navTileRow(st: NavStyle, gap: Int, icon: Int, text: Int): LinearLayout {
        val row = Ui.hbox(a)
        navEntries().forEachIndexed { n, e -> row.addView(navTile(e, st, icon, text), Ui.lin(0, Ui.MATCH, 1f).apply { if (n > 0) leftMargin = u(gap) }) }
        return row
    }

    private fun navPillColumn(st: NavStyle, height: Int = 64, icon: Int = 34, text: Int = 19, gap: Int = 10): LinearLayout {
        val col = Ui.vbox(a)
        navEntries().forEachIndexed { n, e -> col.addView(navPill(e, st, height, icon, text), Ui.lin(Ui.MATCH, u(height)).apply { if (n > 0) topMargin = u(gap) }) }
        return col
    }

    private fun navPillRow(st: NavStyle, height: Int, icon: Int, text: Int, gap: Int = 10): LinearLayout {
        val row = Ui.hbox(a)
        navEntries().forEachIndexed { n, e -> row.addView(navPill(e, st, height, icon, text), Ui.lin(Ui.WRAP, u(height)).apply { if (n > 0) leftMargin = u(gap) }) }
        return row
    }

    /** «Навигатор» header: accent circle + title + default app; tap opens the default navigator. */
    private fun navHeader(st: NavStyle, title: Int = 30): LinearLayout {
        val hd = Ui.hbox(a)
        val c = FrameLayout(a).apply { background = Ui.oval(st.accent) }
        c.addView(Ui.icon(a, R.drawable.ic_nav, Color.WHITE), Ui.frame(u(30), u(30)))
        hd.addView(c, Ui.lin(u(60), u(60)))
        val col = Ui.vbox(a).apply { setPadding(u(16), 0, 0, 0) }
        col.addView(Ui.text(a, title, st.fg, Ui.BOLD).apply { text = if (st.upper) "НАВИГАТОР" else "Навигатор" })
        col.addView(Ui.text(a, 16, st.fg2, Ui.MEDIUM).apply { text = "Куда едем? · " + Nav.name(a, Nav.defaultNav(a)) }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4) })
        hd.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        navClick(hd)
        return hd
    }

    private fun glassNav() = NavStyle(Ui.TEXT, Ui.TEXT2, 0, 0, T.accent, 22, glass = true)

    // ====================================================================================
    // A. КОКПИТ — analog gauge left, clock + media + trip right, dock at the bottom (CWG "Dashboard")
    // ====================================================================================
    private fun buildCockpit(): View {
        val t = T
        val root = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(20)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.TOP }
        val gauge = SpeedGauge(a, t.accent)
        gpsClick(gauge); speedSinks.add(gauge)
        top.addView(gauge, Ui.lin(u(544), Ui.MATCH))

        val right = Ui.vbox(a)
        val ch = Ui.hbox(a).apply { gravity = Gravity.BOTTOM }
        ch.addView(clockText(104, Ui.TEXT, 64), Ui.lin(Ui.WRAP, u(108)))
        val dc = Ui.vbox(a).apply { setPadding(u(20), 0, 0, u(14)) }
        dc.addView(dateText(22))
        val tripLine = Ui.text(a, 17, Ui.alpha(Color.WHITE, 0.6f), Ui.MEDIUM)
        dc.addView(tripLine, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        tickUpdaters.add { tripLine.text = "НЕДЕЛЯ ${java.util.Calendar.getInstance().get(java.util.Calendar.WEEK_OF_YEAR)}" }
        ch.addView(dc, Ui.lin(0, Ui.WRAP, 1f))
        right.addView(ch, Ui.lin(Ui.MATCH, Ui.WRAP))

        // compact media card
        val mc = Ui.hbox(a).apply { setPadding(u(16), u(16), u(20), u(16)) }
        Ui.card(mc); clickableMedia(mc)
        mediaTexts(13, 26, 18, 1)
        artPad = 0.22f
        mc.addView(mediaArt(true, 14), Ui.lin(Ui.WRAP, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(20), u(4), 0, 0) }
        col.addView(srcTv); col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        col.addView(View(a), Ui.lin(1, 0, 1f))
        col.addView(progress(false, 4), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(14) })
        col.addView(controls(62, 76, 14, 30, 38), Ui.lin(Ui.WRAP, Ui.WRAP))
        mc.addView(col, Ui.lin(0, Ui.MATCH, 1f))
        right.addView(mc, Ui.lin(Ui.MATCH, u(220)).apply { topMargin = u(18) })

        // trip cards off → the same space holds quick navigator buttons (no empty hole)
        right.addView(navTileRow(glassNav(), 16, 56, 18), Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(18) })
    
        top.addView(right, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(24) })
        root.addView(top, Ui.lin(Ui.MATCH, 0, 1f))

        // dock
        val dock = Ui.hbox(a).apply { setPadding(u(12), u(10), u(12), u(8)) }
        Ui.card(dock, radius = 30)
        fun fill() {
            dock.removeAllViews()
            for (i in visibleTiles(9)) dock.addView(appIcon(i, 60, 15), Ui.lin(0, Ui.WRAP, 1f))
            dock.visibility = if (dock.childCount == 0) View.GONE else View.VISIBLE
        }
        fill(); appsRebuild = { fill() }
        root.addView(dock, Ui.lin(Ui.MATCH, u(120)).apply { topMargin = u(16) })
        return root
    }

    // ====================================================================================
    // B. КАРТА — full-bleed live map, floating clock / speed ring / media capsule, app strip on the right
    // ====================================================================================
    private fun buildMap(): View {
        val t = T
        val root = FrameLayout(a).apply { setPadding(u(16), u(16), u(16), u(16)) }
        val osm = Prefs.showOsmMap
        val map: View = if (osm) newMap(t.radius) else NavCardView(a, t.accent, light).also { Ui.clipRound(it, u(t.radius)); navClick(it) }
        root.addView(map, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val left = Ui.vbox(a)
        val cc = Ui.vbox(a).apply { setPadding(u(22), u(14), u(26), u(16)) }
        Ui.card(cc)
        cc.addView(clockText(64, Ui.TEXT))
        cc.addView(dateText(18), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4) })
        left.addView(cc, Ui.lin(Ui.WRAP, Ui.WRAP))
        val ring = RingGauge(a, t.accent, numberSize = 50, showCaption = false)
        gpsClick(ring); speedSinks.add(ring)
        left.addView(ring, Ui.lin(u(150), u(150)).apply { topMargin = u(14) })
        root.addView(left, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(u(16), u(16), 0, 0) })

        if (osm) {
            // "open navigator" chip
            val chip = Ui.hbox(a).apply { setPadding(u(18), 0, u(22), 0) }
            Ui.card(chip, radius = 28, press = true); navClick(chip)
            chip.addView(Ui.icon(a, R.drawable.ic_nav, t.accent), Ui.lin(u(26), u(26)))
            chip.addView(Ui.text(a, 19, Ui.TEXT, Ui.MEDIUM).apply { text = "Навигатор"; setPadding(u(10), 0, 0, 0) })
            root.addView(chip, FrameLayout.LayoutParams(Ui.WRAP, u(56), Gravity.TOP or Gravity.END).apply { setMargins(0, u(16), u(16 + 96 + 14), 0) })
        } else {
            // v1.7.0: the whole background is the navigator card — no extra button panel
        }

        // media capsule
        val mp = Ui.hbox(a).apply { setPadding(u(12), u(12), u(16), u(12)) }
        Ui.card(mp, radius = 52); clickableMedia(mp)
        mediaTexts(12, 22, 16, 1)
        artPad = 0.2f
        mp.addView(mediaArt(false, 40), Ui.lin(u(80), u(80)))
        val col = Ui.vbox(a).apply { setPadding(u(16), 0, u(10), 0) }
        col.addView(titleTv); col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        col.addView(progress(false, 3), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        mp.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        mp.addView(controls(56, 68, 8, 28, 34))
        root.addView(mp, FrameLayout.LayoutParams(u(640), u(104), Gravity.BOTTOM or Gravity.START).apply { setMargins(u(16), 0, 0, u(16)) })

        // app strip
        val strip = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, u(14), 0, u(14)) }
        Ui.card(strip, radius = 30)
        fun fill() {
            strip.removeAllViews()
            visibleTiles().forEachIndexed { n, i -> strip.addView(appIcon(i, 56, 0), Ui.lin(Ui.WRAP, Ui.WRAP).apply { if (n > 0) topMargin = u(16) }) }
            strip.visibility = if (strip.childCount == 0) View.GONE else View.VISIBLE
        }
        fill(); appsRebuild = { fill() }
        root.addView(strip, FrameLayout.LayoutParams(u(96), Ui.WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = u(16) })
        return root
    }

    // ====================================================================================
    // C. МЕДИА — giant cover, big controls, round icon carousel (CWG "Templa"-like centre widget)
    // ====================================================================================
    private fun buildMediaLayout(): View {
        val t = T
        val root = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(12)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.TOP }
        clickableMedia(top)
        mediaTexts(16, 46, 26, 2)
        artPad = 0.26f
        val artWrap = FrameLayout(a)
        Ui.card(artWrap, radius = (t.radius * 1.2f).toInt())
        artWrap.addView(mediaArt(true, (t.radius * 1.2f).toInt()), FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        top.addView(artWrap, Ui.lin(Ui.WRAP, Ui.MATCH))

        val col = Ui.vbox(a).apply { setPadding(u(36), u(4), 0, u(4)) }
        val head = Ui.hbox(a)
        head.addView(clockText(40, Ui.TEXT))
        head.addView(dateText(20).apply { setPadding(u(16), u(8), 0, 0) }, Ui.lin(0, Ui.WRAP, 1f))
        val sp = Ui.hbox(a).apply { setPadding(u(18), 0, u(20), 0) }
        Ui.card(sp, radius = 26); gpsClick(sp)
        val num = Ui.text(a, 26, Ui.TEXT, Ui.BOLD).apply { text = "—" }
        sp.addView(Ui.icon(a, R.drawable.ic_speed, t.accent), Ui.lin(u(24), u(24)))
        sp.addView(num, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(10) })
        sp.addView(Ui.text(a, 17, Ui.TEXT2, Ui.MEDIUM).apply { text = " км/ч" })
        speedSinks.add(TextSpeed(num, null, Ui.TEXT))
        head.addView(sp, Ui.lin(Ui.WRAP, u(52)))
        col.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP))
        col.addView(View(a), Ui.lin(1, 0, 1f))
        srcTv.setTextColor(t.accent)
        col.addView(srcTv)
        col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        col.addView(View(a), Ui.lin(1, 0, 1f))
        col.addView(progress(true, 6), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(22) })
        col.addView(controls(96, 124, 26, 42, 56), Ui.lin(Ui.WRAP, Ui.WRAP))
        top.addView(col, Ui.lin(0, Ui.MATCH, 1f))
        root.addView(top, Ui.lin(Ui.MATCH, 0, 1f))

        // carousel: icons grow towards the centre while scrolling (cheap scale on scroll, no animation)
        val sc = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = Ui.hbox(a).apply { setPadding(u(8), 0, u(8), 0) }
        sc.addView(row)
        fun scale() {
            val c = sc.scrollX + sc.width / 2f
            if (sc.width == 0) return
            for (k in 0 until row.childCount) {
                val v = row.getChildAt(k)
                val d = kotlin.math.abs(v.left + v.width / 2f + row.paddingLeft * 0 - c) / (sc.width / 2f)
                val s = 1f - 0.16f * d.coerceAtMost(1f)
                v.scaleX = s; v.scaleY = s; v.alpha = 1f - 0.25f * d.coerceAtMost(1f)
            }
        }
        sc.setOnScrollChangeListener { _, _, _, _, _ -> scale() }
        sc.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scale() }
        fun fill() {
            row.removeAllViews()
            for (i in visibleTiles()) row.addView(appIcon(i, 92, 16, round = true), Ui.lin(u(140), Ui.WRAP))
            row.addView(allAppsIcon(92, 16, true), Ui.lin(u(140), Ui.WRAP))
        }
        fill(); appsRebuild = { fill(); sc.post { scale() } }
        root.addView(sc, Ui.lin(Ui.MATCH, u(150)).apply { topMargin = u(18) })
        return root
    }

    // ====================================================================================
    // D. ДУГА — minimal clock, slim digital speed, big round icons on an arc, media bar (Car Launcher Pro curvature)
    // ====================================================================================
    private fun buildArc(): View {
        val t = T
        val root = FrameLayout(a).apply { setPadding(u(28), u(16), u(28), u(24)) }
        val cl = Ui.vbox(a)
        cl.addView(clockText(150, t.clockFg.takeIf { t.clockBg == 0 } ?: Ui.TEXT, 90), Ui.lin(Ui.WRAP, u(160)))
        cl.addView(dateText(24), Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(6) })
        root.addView(cl, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START))

        val sp = Ui.vbox(a).apply { gravity = Gravity.END; setPadding(0, u(30), 0, 0) }
        gpsClick(sp)
        val r = Ui.hbox(a).apply { gravity = Gravity.BOTTOM }
        val num = Ui.text(a, 92, Ui.TEXT, Ui.BOLD).apply { text = "—"; letterSpacing = -0.02f }
        r.addView(num); r.addView(Ui.text(a, 22, Ui.TEXT2, Ui.MEDIUM).apply { text = "км/ч"; setPadding(u(10), 0, 0, u(14)) })
        sp.addView(r)
        val bar = BarView(a, t.accent, 0x33FFFFFF)
        sp.addView(bar, Ui.lin(u(260), u(6)).apply { topMargin = u(10) })
        val st = Ui.text(a, 16, Ui.TEXT2, Ui.MEDIUM)
        sp.addView(st, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(10) })
        speedSinks.add(TextSpeed(num, st, Ui.TEXT, bar))
        root.addView(sp, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END))

        val arc = ArcLayout(a, -u(96))
        fun fill() {
            arc.removeAllViews()
            for (i in visibleTiles()) arc.addView(appIcon(i, 100, 17, round = true).apply { layoutParams = ViewGroup.LayoutParams(u(124), u(140)) })
            arc.addView(allAppsIcon(100, 17, true).apply { layoutParams = ViewGroup.LayoutParams(u(124), u(140)) })
        }
        fill(); appsRebuild = { fill() }
        root.addView(arc, FrameLayout.LayoutParams(Ui.MATCH, u(96 + 140), Gravity.TOP).apply { topMargin = u(252); leftMargin = u(8); rightMargin = u(8) })

        val mb = Ui.hbox(a).apply { setPadding(u(12), u(12), u(16), u(12)) }
        Ui.card(mb, radius = 56); clickableMedia(mb)
        mediaTexts(12, 24, 18, 1)
        artPad = 0.2f
        mb.addView(mediaArt(false, 44), Ui.lin(u(88), u(88)))
        val col = Ui.vbox(a).apply { setPadding(u(20), 0, u(24), 0) }
        col.addView(titleTv); col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        col.addView(progress(false, 4), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        mb.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        mb.addView(controls(64, 80, 12, 30, 38))
        root.addView(mb, FrameLayout.LayoutParams(Ui.MATCH, u(112), Gravity.BOTTOM))
        return root
    }

    // ====================================================================================
    // E. ВИДЖЕТЫ — iOS-style widget board with mixed sizes
    // ====================================================================================
    private fun buildWidgets(): View {
        val t = T
        val board = BoardLayout(a, 4, 3, u(16)).apply { setPadding(u(20), u(20), u(20), u(20)) }
        val wrap = FrameLayout(a).apply { setPadding(u(20), u(20), u(20), u(20)) }
        board.setPadding(0, 0, 0, 0)
        wrap.addView(board, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        // speed ring 2×2
        val sw = FrameLayout(a)
        Ui.card(sw, solid = t.speedBg)
        val fg = if (t.speedBg != 0) t.speedFg else Color.WHITE
        val ring = RingGauge(a, if (t.speedBg != 0) t.speedFg else t.accent, fg, 0, 104, true)
        gpsClick(sw); speedSinks.add(ring)
        sw.addView(ring, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH).apply { setMargins(u(30), u(30), u(30), u(24)) })
        sw.addView(Ui.text(a, 15, Ui.alpha(fg, 0.75f), Ui.BOLD).apply { text = "СКОРОСТЬ"; letterSpacing = 0.1f },
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(u(22), u(18), 0, 0) })
        board.addView(sw, BoardLayout.Cell(0, 0, 2, 2))

        // music 2×1
        val mw = Ui.hbox(a).apply { setPadding(u(16), u(16), u(20), u(16)) }
        Ui.card(mw); clickableMedia(mw)
        mediaTexts(12, 24, 17, 1)
        artPad = 0.22f
        mw.addView(mediaArt(true, 14), Ui.lin(Ui.WRAP, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(18), u(2), 0, 0) }
        col.addView(srcTv); col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        col.addView(View(a), Ui.lin(1, 0, 1f))
        col.addView(progress(false, 4), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(12) })
        col.addView(controls(54, 64, 10, 26, 32))
        mw.addView(col, Ui.lin(0, Ui.MATCH, 1f))
        board.addView(mw, BoardLayout.Cell(2, 0, 2, 1))

        // date 1×1 (calendar page)
        val dw = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(u(12), u(20), u(12), u(16)) }
        Ui.card(dw, solid = t.clockBg)
        val dfg = if (t.clockBg != 0) t.clockFg else Color.WHITE
        val wd = Ui.text(a, 18, if (t.clockBg != 0) t.clockFg else t.accent, Ui.BOLD).apply { letterSpacing = 0.08f; gravity = Gravity.CENTER }
        val dn = Ui.text(a, 96, dfg, t.clockTypeface).apply { gravity = Gravity.CENTER }
        val mo = Ui.text(a, 19, Ui.alpha(dfg, 0.7f), Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        dw.addView(wd, Ui.lin(Ui.MATCH, Ui.WRAP)); dw.addView(dn, Ui.lin(Ui.MATCH, 0, 1f)); dw.addView(mo, Ui.lin(Ui.MATCH, Ui.WRAP))
        dn.gravity = Gravity.CENTER
        tickUpdaters.add {
            val now = Date()
            wd.text = SimpleDateFormat("EEEE", ru).format(now).uppercase(ru)
            dn.text = SimpleDateFormat("d", ru).format(now)
            mo.text = SimpleDateFormat("LLLL yyyy", ru).format(now).replaceFirstChar { it.titlecase(ru) }
        }
        board.addView(dw, BoardLayout.Cell(2, 1, 1, 1))

        // compass 1×1
        val cw = FrameLayout(a)
        Ui.card(cw)
        val comp = CompassView(a, t.accent); compasses.add(comp)
        cw.addView(comp, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH).apply { setMargins(u(20), u(20), u(20), u(20)) })
        board.addView(cw, BoardLayout.Cell(3, 1, 1, 1))

        // navigator 1×1: app icons + «Навигатор + музыка»
        val st = NavStyle(Ui.TEXT, Ui.TEXT2, 0x24FFFFFF, 0x45FFFFFF, t.accent, 18)
        val nw = Ui.vbox(a).apply { setPadding(u(18), u(16), u(18), u(16)) }
        Ui.card(nw)
        val hd = Ui.hbox(a)
        hd.addView(Ui.icon(a, R.drawable.ic_nav, t.accent), Ui.lin(u(20), u(20)))
        hd.addView(Ui.text(a, 15, labelFg, Ui.BOLD).apply { text = "НАВИГАТОР"; letterSpacing = 0.1f }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(8) })
        nw.addView(hd)
        val icons = Ui.hbox(a)
        navEntries(false, 3).forEachIndexed { n, e ->
            val f = FrameLayout(a).apply { background = Ui.pressable(0x1AFFFFFF, u(18), 0x40FFFFFF); setPadding(u(12), u(12), u(12), u(12)) }
            f.addView(navIcon(e, st), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)); bindNav(f, e)
            icons.addView(f, Ui.lin(0, u(76), 1f).apply { if (n > 0) leftMargin = u(10) })
        }
        nw.addView(icons, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(10) })
        board.addView(nw, BoardLayout.Cell(0, 2, 1, 1))
    

        // analog clock 1×1 + digital time
        val aw = FrameLayout(a)
        Ui.card(aw)
        val ac = AnalogClockView(a, t.accent); analogClocks.add(ac)
        aw.addView(ac, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH).apply { setMargins(u(18), u(18), u(18), u(18)) })
        board.addView(aw, BoardLayout.Cell(1, 2, 1, 1))

        // apps 2×1 (4×2 icons)
        val ap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(u(10), u(12), u(10), u(10)) }
        Ui.card(ap)
        fun fill() {
            ap.removeAllViews()
            val vis = visibleTiles()
            var row: LinearLayout? = null
            vis.forEachIndexed { n, i ->
                if (n % 4 == 0) { row = Ui.hbox(a); ap.addView(row, Ui.lin(Ui.MATCH, 0, 1f)) }
                row!!.addView(appIcon(i, 58, 14), Ui.lin(0, Ui.WRAP, 1f))
            }
            val rem = vis.size % 4
            if (rem != 0) repeat(4 - rem) { row!!.addView(View(a), Ui.lin(0, 1, 1f)) }
        }
        fill(); appsRebuild = { fill() }
        board.addView(ap, BoardLayout.Cell(2, 2, 2, 1))
        return wrap
    }

    // ====================================================================================
    // F. ПЛИТКИ — the v1.2 layout (clock card, speed card, media card, tile grid)
    // ====================================================================================
    private fun buildTilesLayout(): View {
        val t = T
        val root = Ui.hbox(a).apply { gravity = Gravity.TOP; setPadding(u(20), u(20), u(20), u(20)) }
        val left = Ui.vbox(a)
        val clock = Ui.vbox(a).apply { setPadding(u(28), u(26), u(24), u(24)) }
        Ui.card(clock, solid = t.clockBg)
        clock.addView(clockText(t.clockSize, t.clockFg, 56), Ui.lin(Ui.MATCH, u(t.clockSize + 8)))
        clock.addView(dateText(24), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(8) })
        val wr = Ui.hbox(a); weekRow = wr
        clock.addView(wr, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(22) })
        clock.addView(View(a), Ui.lin(1, 0, 1f))
        val db = BarView(a, if (t.clockBg != 0) t.clockFg else t.accent, Ui.alpha(if (t.clockBg != 0) t.clockFg else Color.WHITE, 0.18f))
        dayBar = db
        clock.addView(db, Ui.lin(Ui.MATCH, u(4)))
        val dt = Ui.text(a, 16, Ui.alpha(t.dateFg, 0.85f), Ui.MEDIUM).apply { letterSpacing = 0.04f }
        dayTv = dt
        clock.addView(dt, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        left.addView(clock, Ui.lin(Ui.MATCH, 0, 1f))

        if (Prefs.showSpeed) {
            val sp = Ui.vbox(a).apply { setPadding(u(28), u(18), u(20), u(18)); gravity = Gravity.CENTER_VERTICAL }
            gpsClick(sp)
            Ui.card(sp, solid = t.speedBg)
            val row = Ui.hbox(a).apply { gravity = Gravity.BOTTOM }
            val speedTv = Ui.text(a, 88, t.speedFg, Ui.BOLD).apply { text = "—"; letterSpacing = -0.02f }
            row.addView(speedTv)
            row.addView(Ui.text(a, 24, Ui.alpha(t.speedFg, 0.7f), Ui.MEDIUM).apply { text = "км/ч"; setPadding(u(12), 0, 0, u(12)) })
            sp.addView(row)
            val st = Ui.hbox(a)
            st.addView(Ui.icon(a, R.drawable.ic_speed, if (t.speedBg != 0) t.speedFg else t.accent), Ui.lin(u(22), u(22)))
            val gpsTv = Ui.text(a, 18, Ui.alpha(t.speedFg, 0.7f)).apply { setPadding(u(8), 0, 0, 0) }
            st.addView(gpsTv)
            sp.addView(st, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(8) })
            left.addView(sp, Ui.lin(Ui.MATCH, u(200)).apply { topMargin = u(16) })
            speedSinks.add(TextSpeed(speedTv, gpsTv, t.speedFg))
        }
        root.addView(left, Ui.lin(u(320), Ui.MATCH))

        val right = Ui.vbox(a)
        val mc = Ui.hbox(a).apply { gravity = Gravity.TOP }
        Ui.card(mc, solid = if (t.glass) 0 else 0xFF141416.toInt())
        Ui.clipRound(mc, u(t.radius)); clickableMedia(mc)
        mediaTexts(15, 32, 21, 2)
        mc.addView(mediaArt(true, 0), Ui.lin(Ui.WRAP, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(26), u(24), u(24), u(22)) }
        col.addView(srcTv)
        col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        col.addView(View(a), Ui.lin(1, 0, 1f))
        col.addView(progress(true), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(14) })
        col.addView(controls(76, 92, 18).apply { gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP))
        mc.addView(col, Ui.lin(0, Ui.MATCH, 1f))
        right.addView(mc, Ui.lin(Ui.MATCH, 0, 1f))
        val tb = Ui.vbox(a); tilesBox = tb
        right.addView(tb, Ui.lin(Ui.MATCH, u(TILE_H)).apply { topMargin = u(16) })
        root.addView(right, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(16) })
        buildTiles(); appsRebuild = { buildTiles() }
        return root
    }

    private fun buildTiles() {
        val box = tilesBox ?: return
        box.removeAllViews()
        val vis = visibleTiles(9)
        val cols = 4
        var row: LinearLayout? = null
        vis.forEachIndexed { n, i ->
            if (n % cols == 0) {
                row = Ui.hbox(a)
                box.addView(row, Ui.lin(Ui.MATCH, 0, 1f).apply { if (n > 0) topMargin = u(16) })
            }
            val wide = T.bento && n == 0
            row!!.addView(tile(i, wide), Ui.lin(0, Ui.MATCH, if (wide) 2f else 1f).apply { if (n % cols > 0) leftMargin = u(16) })
        }
        val rem = vis.size % cols
        if (rem != 0) repeat(cols - rem) { row!!.addView(View(a), Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(16) }) }
        val rows = (vis.size + cols - 1) / cols
        box.visibility = if (rows == 0) View.GONE else View.VISIBLE
        box.layoutParams?.let { it.height = if (rows == 0) 0 else rows * u(TILE_ROW) + (rows - 1) * u(16); box.layoutParams = it }
    }

    private fun tile(i: Int, wide: Boolean): View {
        val t = T
        val def = Tiles.ALL[i]
        val r = Tiles.resolve(a, i)
        val title = tileTitle(i)
        val iv = tileIcon(i)
        val real = Tiles.useRealIcon(r.pkg)
        val box: LinearLayout
        if (t.tileBlocks) {
            box = Ui.vbox(a).apply { setPadding(u(16), u(14), u(14), u(12)); gravity = Gravity.START }
            Ui.card(box, solid = if (real) 0xFF1C1C1E.toInt() else Tiles.blockColor(def), press = true)
            box.addView(iv, Ui.lin(u(if (wide) 52 else 46), u(if (wide) 52 else 46)))
            box.addView(View(a), Ui.lin(1, 0, 1f))
            box.addView(Ui.text(a, if (wide) 24 else 18, Color.WHITE, Ui.BOLD).apply { text = title }, Ui.lin(Ui.MATCH, Ui.WRAP))
        } else {
            box = Ui.vbox(a).apply { gravity = Gravity.CENTER }
            Ui.card(box, press = true)
            box.addView(iv, Ui.lin(u(60), u(60)))
            box.addView(Ui.text(a, 17, Ui.TEXT, Ui.MEDIUM).apply { text = title; gravity = Gravity.CENTER },
                Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8); leftMargin = u(6); rightMargin = u(6) })
        }
        if (r.pkg == null) box.addView(Ui.text(a, 13, Ui.alpha(Color.WHITE, 0.55f)).apply { text = "не установлено"; gravity = if (t.tileBlocks) Gravity.START else Gravity.CENTER },
            Ui.lin(Ui.MATCH, Ui.WRAP))
        bindTile(box, i)
        return box
    }

    /** Mini week strip (tiles layout): ПН..ВС with today's date highlighted. */
    private fun buildWeek() {
        val wr = weekRow ?: return
        val t = T
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (today == weekDay) return
        weekDay = today
        wr.removeAllViews()
        val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(java.util.Calendar.DAY_OF_YEAR, -dow)
        val names = arrayOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        for (i in 0..6) {
            val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(Ui.text(a, 13, Ui.alpha(t.dateFg, if (i >= 5) 0.5f else 0.7f), Ui.MEDIUM).apply { text = names[i]; letterSpacing = 0.04f })
            val n = Ui.text(a, 19, t.clockFg, Ui.MEDIUM).apply { text = cal.get(java.util.Calendar.DAY_OF_MONTH).toString(); gravity = Gravity.CENTER }
            if (i == dow) {
                n.background = Ui.oval(if (t.clockBg != 0) t.clockFg else t.accent)
                n.setTextColor(if (t.clockBg != 0) t.clockBg else Color.WHITE)
                n.typeface = Ui.BOLD
            }
            col.addView(n, Ui.lin(u(36), u(36)).apply { topMargin = u(6) })
            wr.addView(col, Ui.lin(0, Ui.WRAP, 1f))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
    }

    // ====================================================================================
    // v1.4 — layouts inspired by real car head units (original interpretations, no brand marks)
    // ====================================================================================
    private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи"
    }

    // ---- v1.7.0 rear camera tile ----
    private val liveCams = ArrayList<LiveCamView>()
    private fun cameraTile(radius: Int): View {
        val f = FrameLayout(a)
        Ui.card(f, solid = 0xF0101114.toInt(), radius = radius, press = true)
        Ui.clipRound(f, u(radius))
        val pkg = CamApps.camera(a)
        if (Prefs.camLive) {
            val lv = LiveCamView(a); liveCams.add(lv)
            f.addView(lv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        val col = Ui.vbox(a).apply { gravity = Gravity.CENTER }
        val disc = FrameLayout(a).apply { background = Ui.oval(Ui.alpha(T.accent, 0.22f)) }
        disc.addView(Ui.icon(a, R.drawable.ic_videocam, T.accent), Ui.frame(u(34), u(34)))
        col.addView(disc, Ui.lin(u(66), u(66)))
        col.addView(Ui.text(a, 20, Color.WHITE, Ui.BOLD).apply { text = "Камера"; gravity = Gravity.CENTER }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(10) })
        col.addView(Ui.text(a, 14, Ui.TEXT2, Ui.MEDIUM).apply { text = pkg?.let { "Задний вид · " + Tiles.label(a, it) } ?: "Выберите приложение в Настройках"; gravity = Gravity.CENTER },
            Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4) })
        f.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        liveCams.lastOrNull()?.takeIf { Prefs.camLive }?.onState = { ok -> col.visibility = if (ok) View.GONE else View.VISIBLE }
        f.isClickable = true; f.contentDescription = "Камера"
        f.setOnClickListener { CamApps.openCamera(a) }
        return f
    }

    /** «Камера» pill in the same style as the navigator pill (car layouts). */
    private fun camPill(st: NavStyle, height: Int, icon: Int, text: Int): LinearLayout {
        val p = Ui.hbox(a).apply { setPadding(u(14), 0, u(18), 0); minimumHeight = u(height) }
        navBg(p, st)
        p.addView(Ui.icon(a, R.drawable.ic_videocam, st.accent), Ui.lin(u(icon), u(icon)))
        p.addView(Ui.text(a, text, st.fg, st.face).apply { this.text = if (st.upper) "КАМЕРА" else "Камера заднего вида" }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(12) })
        p.isClickable = true; p.contentDescription = "Камера"; p.setOnClickListener { CamApps.openCamera(a) }
        return p
    }

    private fun navClick(v: View) {
        v.isClickable = true
        v.setOnClickListener { if (Nav.defaultNav(a) != null) Nav.open(a, null) else Pickers.pickApp(a, 0) { a.rebuild() } }
    }

    private fun newMap(radius: Int, day: Boolean = false): MapTileView {
        val map = MapTileView(a, T.accent, day)
        if (!Prefs.lastLat.isNaN()) { MapTileView.lastLat = Prefs.lastLat.toDouble(); MapTileView.lastLon = Prefs.lastLon.toDouble() }
        if (radius > 0) Ui.clipRound(map, u(radius))
        navClick(map); maps.add(map)
        return map
    }


    // ---- 1. АМЕРИКАНСКИЙ МИНИМАЛ: car panel left (speed + top-view car + trip), map dominates, slim bottom dock ----
    private fun buildMinimal(): View {
        val t = T
        val root = Ui.vbox(a)
        val top = Ui.hbox(a).apply { gravity = Gravity.TOP }
        val panel = Ui.vbox(a).apply { setPadding(u(28), u(22), u(28), u(22)); gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xF5121316.toInt()) }
        val hdr = Ui.hbox(a)
        hdr.addView(clockText(22, Ui.TEXT).apply { typeface = Ui.MEDIUM }, Ui.lin(0, Ui.WRAP, 1f))
        val st = Ui.text(a, 15, Ui.TEXT2, Ui.MEDIUM).apply { gravity = Gravity.END }
        hdr.addView(st, Ui.lin(0, Ui.WRAP, 2f))
        panel.addView(hdr, Ui.lin(Ui.MATCH, Ui.WRAP))
        val num = Ui.text(a, 120, Ui.TEXT, Ui.LIGHT).apply { text = "—"; gravity = Gravity.CENTER; letterSpacing = -0.03f }
        gpsClick(num)
        panel.addView(num, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        panel.addView(Ui.text(a, 19, Ui.TEXT2, Ui.MEDIUM).apply { text = "км/ч"; gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        speedSinks.add(TextSpeed(num, st, Ui.TEXT))
        panel.addView(road(t.accent, light, carH = 0.84f, maxCarW = 0.74f, horizon = 0.06f, ground = 0.9f), Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(10); bottomMargin = u(6) })
        panel.addView(Ui.text(a, 13, Ui.TEXT3, Ui.MEDIUM).apply { text = "SUZUKI SX4 S-CROSS"; gravity = Gravity.CENTER; letterSpacing = 0.3f }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(4) })
        top.addView(panel, Ui.lin(u(390), Ui.MATCH))

        val mapBox = FrameLayout(a)
        mapBox.addView(if (Prefs.showOsmMap) newMap(0) else NavCardView(a, t.accent, light).also { navClick(it); if (Prefs.camTile) it.setPadding(0, 0, u(300), u(110)) else it.setPadding(0, 0, 0, u(110)) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        // v1.7.0: rear camera tile (opens Cam REC / camera app; optional live picture), top-right of the navigator area
        if (Prefs.camTile) mapBox.addView(cameraTile(14), FrameLayout.LayoutParams(u(300), u(190), Gravity.TOP or Gravity.END).apply { setMargins(0, u(20), u(20), 0) })
        val mc = Ui.hbox(a).apply { setPadding(u(12), u(12), u(14), u(12)) }
        Ui.card(mc, solid = 0xF0141518.toInt(), radius = 14); clickableMedia(mc)
        mediaTexts(12, 20, 16, 1); artPad = 0.2f
        mc.addView(mediaArt(false, 8), Ui.lin(u(80), u(80)))
        val col = Ui.vbox(a).apply { setPadding(u(14), 0, u(8), 0) }
        col.addView(titleTv); col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        col.addView(progress(false, 3), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        mc.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        mc.addView(controls(50, 58, 6, 26, 30, playBg = 0x00000000, playIconFg = Color.WHITE))
        mapBox.addView(mc, FrameLayout.LayoutParams(u(500), u(104), Gravity.BOTTOM or Gravity.START).apply { setMargins(u(20), 0, 0, u(20)) })
        top.addView(mapBox, Ui.lin(0, Ui.MATCH, 1f))
        root.addView(top, Ui.lin(Ui.MATCH, 0, 1f))

        val dock = Ui.hbox(a).apply { gravity = Gravity.CENTER; setBackgroundColor(0xFF08080A.toInt()) }
        fun fill() {
            dock.removeAllViews()
            for (i in visibleTiles(9)) dock.addView(appIcon(i, 50, 0), Ui.lin(u(50), u(50)).apply { leftMargin = u(22); rightMargin = u(22) })
            dock.addView(allAppsIcon(50, 0, false, 0x33FFFFFF), Ui.lin(u(50), u(50)).apply { leftMargin = u(22); rightMargin = u(22) })
        }
        fill(); appsRebuild = { fill() }
        root.addView(dock, Ui.lin(Ui.MATCH, u(80)))
        return root
    }

    /** Smart: glossy round backplate (frosted disc, hairline ring, highlight) behind a tile icon. */
    private fun smartPlate(icon: View): FrameLayout = FrameLayout(a).apply {
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x59FFFFFF, 0x14FFFFFF)).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(u(1.5f), 0x66FFFFFF) },
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x1AFFFFFF) }
        )).apply { setLayerInset(1, u(10), u(4), u(10), u(40)) }
        addView(icon, Ui.frame(u(48), u(48)))
    }

    /** Sport: chamfered metal plate with an accent notch behind a tile icon. */
    private fun sportPlate(icon: View, accent: Int): FrameLayout = FrameLayout(a).apply {
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            ChamferDrawable(0xFF2A2D34.toInt(), 0x40FFFFFF, 0, u(12).toFloat()),
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22FFFFFF, 0)),
            android.graphics.drawable.ColorDrawable(accent)
        )).apply { setLayerInset(1, u(12), u(2), u(2), u(34)); setLayerInset(2, u(14), u(62), u(14), u(0)) }
        addView(icon, Ui.frame(u(44), u(44)))
    }

    // ---- 2. КИТАЙСКИЙ СМАРТ: big gradient hero with a glossy car, vivid music card, colourful app grid ----
    private fun buildSmart(): View {
        val t = T
        val root = Ui.hbox(a).apply { gravity = Gravity.TOP; setPadding(u(20), u(20), u(20), u(20)) }
        val hero = FrameLayout(a)
        hero.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if (light) intArrayOf(Ui.blend(0xFFF7F9FC.toInt(), Ui.alpha(t.accent, 0.16f)), 0xFFF1F3F7.toInt(), 0xFFE6E9EF.toInt())
            else intArrayOf(Ui.blend(0xF0141A3A.toInt(), Ui.alpha(t.accent, 0.55f)), 0xF00E1228.toInt(), 0xF0060810.toInt())).apply { cornerRadius = u(32).toFloat() }
        val hc = Ui.vbox(a).apply { setPadding(u(32), u(26), 0, 0) }
        hc.addView(Ui.text(a, 22, Ui.alpha(Color.WHITE, 0.75f), Ui.MEDIUM).also { g -> tickUpdaters.add { g.text = greeting() } })
        hc.addView(clockText(84, Color.WHITE).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        hc.addView(dateText(18, Ui.alpha(Color.WHITE, 0.7f)), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(8) })
        hero.addView(hc, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START))
        val ring = RingGauge(a, t.accent, bg = 0x40000000, numberSize = 46, showCaption = false)
        gpsClick(ring); speedSinks.add(ring)
        hero.addView(ring, FrameLayout.LayoutParams(u(150), u(150), Gravity.TOP or Gravity.END).apply { setMargins(0, u(24), u(24), 0) })
        hero.addView(road(t.accent, light, carH = 0.86f, maxCarW = 0.46f, horizon = 0.05f, ground = 0.92f), FrameLayout.LayoutParams(Ui.MATCH, u(340), Gravity.BOTTOM).apply { setMargins(0, 0, 0, u(84)) })
        val chips = Ui.hbox(a)
        val chipSt = NavStyle(Color.WHITE, Ui.alpha(Color.WHITE, 0.7f), 0x2EFFFFFF, 0x55FFFFFF, Color.WHITE, 22, Ui.BOLD)
        navEntries().forEach { e -> chips.addView(navPill(e, chipSt, 48, 24, 16), Ui.lin(Ui.WRAP, u(48)).apply { rightMargin = u(10) }) }
        hero.addView(chips, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(u(28), 0, 0, u(26)) })
        root.addView(hero, Ui.lin(0, Ui.MATCH, 1f))

        val right = Ui.vbox(a)
        val mc = Ui.vbox(a).apply { setPadding(u(22), u(22), u(22), u(18)) }
        mc.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFF3D7F.toInt(), 0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt())).apply { cornerRadius = u(32).toFloat() }
        clickableMedia(mc)
        mediaTexts(12, 24, 17, 2); artPad = 0.22f
        val mt = Ui.hbox(a).apply { gravity = Gravity.TOP }
        mt.addView(mediaArt(true, 20), Ui.lin(u(116), u(116)))
        val col = Ui.vbox(a).apply { setPadding(u(18), u(4), 0, 0) }
        col.addView(srcTv); col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        mt.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        mc.addView(mt, Ui.lin(Ui.MATCH, Ui.WRAP))
        mc.addView(View(a), Ui.lin(1, 0, 1f))
        mc.addView(progress(false, 4), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(12) })
        mc.addView(controls(58, 70, 22, 28, 34, playBg = Color.WHITE, playIconFg = 0xFF5B12D8.toInt()).apply { gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP))
        right.addView(mc, Ui.lin(Ui.MATCH, u(296)))
        val grid = Ui.vbox(a)
        fun fill() {
            grid.removeAllViews()
            val vis = visibleTiles()
            for (r in 0..1) {
                val row = Ui.hbox(a)
                for (k in 0..3) {
                    val n = r * 4 + k
                    if (n < vis.size) {
                        val i = vis[n]
                        val base = if (Tiles.useRealIcon(Tiles.resolve(a, i).pkg)) 0xFF2C3446.toInt() else Tiles.blockColor(Tiles.ALL[i])
                        val tile = Ui.vbox(a).apply { gravity = Gravity.CENTER }
                        tile.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Ui.blend(base, 0x40FFFFFF), base)).apply { cornerRadius = u(24).toFloat() }
                        tile.addView(smartPlate(tileIcon(i)), Ui.lin(u(72), u(72)))
                        tile.addView(Ui.text(a, 14, Color.WHITE, Ui.BOLD).apply { text = tileTitle(i); gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8); leftMargin = u(4); rightMargin = u(4) })
                        bindTile(tile, i)
                        row.addView(tile, Ui.lin(0, Ui.MATCH, 1f).apply { if (k > 0) leftMargin = u(12) })
                    } else if (n == vis.size) {
                        val all = allAppsIcon(54, 14, false, 0x33FFFFFF).apply { gravity = Gravity.CENTER; background = Ui.round(0xCC1C1F2A.toInt(), u(24)) }
                        row.addView(all, Ui.lin(0, Ui.MATCH, 1f).apply { if (k > 0) leftMargin = u(12) })
                    } else row.addView(View(a), Ui.lin(0, Ui.MATCH, 1f).apply { if (k > 0) leftMargin = u(12) })
                }
                grid.addView(row, Ui.lin(Ui.MATCH, 0, 1f).apply { if (r > 0) topMargin = u(12) })
            }
        }
        fill(); appsRebuild = { fill() }
        right.addView(grid, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(16) })
        root.addView(right, Ui.lin(u(430), Ui.MATCH).apply { leftMargin = u(16) })
        return root
    }

    /** Premium typography: caps source with wide tracking, serif title, light tracked artist. */
    private fun premiumType(accent: Int, title: Int = 26) {
        mediaTexts(11, title, 16, 1, Color.WHITE, Typeface.create("serif", Typeface.NORMAL))
        srcTv.setTextColor(accent); srcTv.letterSpacing = 0.35f; srcTv.isAllCaps = true
        titleTv.letterSpacing = 0.01f; artistTv.typeface = Ui.LIGHT; artistTv.letterSpacing = 0.06f
        srcTv.gravity = Gravity.CENTER; titleTv.gravity = Gravity.CENTER; artistTv.gravity = Gravity.CENTER
    }
    /** Hairline pill (no heavy fill) — premium buttons. */
    private fun premiumPill(): android.graphics.drawable.StateListDrawable = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), Ui.round(0x33FFFFFF, u(30)))
        addState(intArrayOf(), Ui.round(0x0FFFFFFF, u(30)).apply { setStroke(u(1), 0x2EFFFFFF) })
    }

    // ---- 3b. ПРЕМИУМ ГРАНД (v1.6.1, second premium variant): thin big clock + navigator left, chrome gauge centre,
    //          round cover with serif type right, round icon dock on lacquer
    private fun buildGrand(): View {
        val t = T
        val root = FrameLayout(a)
        root.addView(View(a).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xEC111318.toInt(), 0xF4060608.toInt(), 0xF80B0C10.toInt())) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(AmbientLines(a, t.accent), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(32), u(24), u(32), u(18)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        val left = Ui.vbox(a)
        left.addView(clockText(124, Color.WHITE, 80).apply { typeface = thin; letterSpacing = 0.02f }, Ui.lin(Ui.MATCH, u(132)))
        left.addView(dateText(14, Ui.alpha(Color.WHITE, 0.6f)).apply { letterSpacing = 0.32f; isAllCaps = true }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2); leftMargin = u(6) })
        val st = NavStyle(Ui.alpha(Color.WHITE, 0.92f), Ui.alpha(Color.WHITE, 0.6f), 0x0FFFFFFF, 0x33FFFFFF, t.accent, 30, Ui.LIGHT, upper = true)
        left.addView(navPillColumn(st, 54, 28, 14, 10).apply { for (k in 0 until childCount) getChildAt(k).background = premiumPill() },
            Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(28) })
        top.addView(left, Ui.lin(u(330), Ui.WRAP))
        val gauge = LuxGauge(a, t.accent); gpsClick(gauge); speedSinks.add(gauge)
        top.addView(gauge, Ui.lin(0, u(400), 1f).apply { leftMargin = u(12); rightMargin = u(12) })
        val right = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
        clickableMedia(right)
        premiumType(t.accent, 24); artPad = 0.26f
        val ring = FrameLayout(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0); setStroke(u(2), t.accent) } }
        val art = mediaArt(true, 0)
        (art as CoverView).circle = true
        ring.addView(art, Ui.frame(u(176), u(176)))
        right.addView(ring, Ui.lin(u(192), u(192)))
        right.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(16) })
        right.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        right.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        right.addView(progress(false, 2, Color.WHITE, t.accent), Ui.lin(u(240), Ui.WRAP).apply { topMargin = u(12) })
        right.addView(controls(50, 60, 16, 24, 28, playBg = 0x1FFFFFFF, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(10) })
        top.addView(right, Ui.lin(u(320), Ui.WRAP))
        col.addView(top, Ui.lin(Ui.MATCH, 0, 1f))
        col.addView(View(a).apply { setBackgroundColor(0x24FFFFFF) }, Ui.lin(Ui.MATCH, u(1)).apply { bottomMargin = u(14) })
        val dock = Ui.hbox(a).apply { gravity = Gravity.CENTER }
        fun fill() {
            dock.removeAllViews()
            for (i in visibleTiles()) dock.addView(appIcon(i, 56, 13, round = false, labelColor = Ui.alpha(Color.WHITE, 0.8f)).apply {
                (getChildAt(1) as? TextView)?.let { it.text = it.text.toString().uppercase(ru); it.letterSpacing = 0.1f; it.typeface = Ui.LIGHT } },
                Ui.lin(0, Ui.WRAP, 1f))
            dock.addView(allAppsIcon(56, 13, false, 0x1FFFFFFF).apply { (getChildAt(1) as? TextView)?.let { it.text = "ВСЕ"; it.letterSpacing = 0.1f; it.typeface = Ui.LIGHT } }, Ui.lin(0, Ui.WRAP, 1f))
        }
        fill(); appsRebuild = { fill() }
        col.addView(dock, Ui.lin(Ui.MATCH, Ui.WRAP))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ---- 3. ЕВРОПЕЙСКИЙ ПРЕМИУМ: black lacquer, ambient light strokes, chrome round instrument, pill app list ----
    private fun buildPremium(): View {
        val t = T
        val root = FrameLayout(a)
        root.addView(View(a).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xE8080A10.toInt(), 0xF5020203.toInt())) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(AmbientLines(a, t.accent), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val row = Ui.hbox(a).apply { setPadding(u(24), u(24), u(24), u(24)) }
        val gauge = LuxGauge(a, t.accent)
        gpsClick(gauge); speedSinks.add(gauge)
        row.addView(gauge, Ui.lin(u(430), u(430)))

        val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        val mid = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
        clickableMedia(mid)
        mid.addView(clockText(88, Color.WHITE, 60).apply { typeface = thin; gravity = Gravity.CENTER; letterSpacing = 0.04f }, Ui.lin(Ui.MATCH, u(96)))
        mid.addView(dateText(14, Ui.alpha(Color.WHITE, 0.6f)).apply { gravity = Gravity.CENTER; letterSpacing = 0.32f; isAllCaps = true }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        premiumType(t.accent); artPad = 0.26f
        val ring = FrameLayout(a).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0); setStroke(u(2), t.accent) } }
        val art = mediaArt(true, 0)
        (art as CoverView).circle = true
        ring.addView(art, Ui.frame(u(168), u(168)))
        mid.addView(ring, Ui.lin(u(184), u(184)).apply { topMargin = u(26) })
        mid.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(18) })
        mid.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        mid.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        mid.addView(progress(false, 2, Color.WHITE, t.accent), Ui.lin(u(260), Ui.WRAP).apply { topMargin = u(14) })
        mid.addView(controls(54, 64, 18, 26, 30, playBg = 0x1FFFFFFF, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(12) })
        row.addView(mid, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(16); rightMargin = u(16) })

        val list = Ui.vbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        fun pill(icon: View, label: String, onBind: (View) -> Unit) {
            val p = Ui.hbox(a).apply { setPadding(u(12), 0, u(16), 0) }
            p.background = premiumPill()
            p.addView(icon, Ui.lin(u(36), u(36)))
            p.addView(Ui.text(a, 14, Ui.alpha(Color.WHITE, 0.92f), Ui.LIGHT).apply { text = label.uppercase(ru); letterSpacing = 0.08f }, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(14) })
            onBind(p)
            list.addView(p, Ui.lin(Ui.MATCH, u(58)).apply { topMargin = u(8) })
        }
        fun fill() {
            list.removeAllViews()
            for (i in visibleTiles()) pill(tileIcon(i), tileTitle(i)) { bindTile(it, i) }
            pill(Ui.icon(a, R.drawable.ic_apps, Color.WHITE).apply { setPadding(u(6), u(6), u(6), u(6)) }, "Все приложения") { it.isClickable = true; it.setOnClickListener { a.show(1) } }
        }
        fill(); appsRebuild = { fill() }
        val lsc = android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        lsc.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        row.addView(lsc, Ui.lin(u(250), Ui.MATCH))
        root.addView(row, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ---- 4. СПОРТ-КОКПИТ: carbon, twin segmented dials (speed + time), chamfered cards ----
    private fun buildSport(): View {
        val t = T
        val root = FrameLayout(a)
        val carbon = android.graphics.drawable.BitmapDrawable(a.resources, android.graphics.BitmapFactory.decodeResource(a.resources, R.drawable.carbon)).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); alpha = 245 }
        root.addView(View(a).apply { background = carbon }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(SportStripes(a, t.accent), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
        val cardBg = 0xE6101114.toInt(); val cardPr = 0xFF22242A.toInt(); val hair = 0x33FFFFFF
        val col = Ui.vbox(a).apply { setPadding(u(20), u(16), u(20), u(20)) }
        val top = Ui.hbox(a)
        val sg = SportGauge(a, t.accent, false); gpsClick(sg); speedSinks.add(sg)
        top.addView(sg, Ui.lin(u(400), u(400)))
        val mc = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(u(18), u(22), u(18), u(18)); background = ChamferDrawable(cardBg, hair, t.accent, u(22).toFloat()) }
        clickableMedia(mc)
        mediaTexts(12, 21, 15, 1, Color.WHITE, cond); artPad = 0.24f
        srcTv.setTextColor(t.accent); srcTv.gravity = Gravity.CENTER; titleTv.gravity = Gravity.CENTER; artistTv.gravity = Gravity.CENTER
        titleTv.isAllCaps = true
        mc.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP))
        mc.addView(mediaArt(true, 4), Ui.lin(u(150), u(150)).apply { topMargin = u(12) })
        mc.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        mc.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        mc.addView(View(a), Ui.lin(1, 0, 1f))
        mc.addView(progress(false, 3, Color.WHITE, t.accent), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(12) })
        mc.addView(controls(52, 62, 10, 26, 30))
        top.addView(mc, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(14); rightMargin = u(14) })
        val cg = SportGauge(a, t.accent, true)
        tickUpdaters.add {
            val cal = java.util.Calendar.getInstance()
            val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            cg.setTime(m, SimpleDateFormat(if (Prefs.use24) "HH:mm" else "h:mm", ru).format(cal.time), SimpleDateFormat("EE d.MM", ru).format(cal.time).uppercase(ru))
        }
        top.addView(cg, Ui.lin(u(400), u(400)))
        col.addView(top, Ui.lin(Ui.MATCH, u(400)))

        // chamfered navigator buttons instead of the trip strip
        val nav = Ui.hbox(a)
        val st = NavStyle(Color.WHITE, Ui.alpha(Color.WHITE, 0.6f), cardBg, cardPr, t.accent, 0, cond, upper = true, chamfer = true)
        navEntries().forEachIndexed { n, e -> nav.addView(navPill(e, st, 56, 28, 18).apply { gravity = Gravity.CENTER }, Ui.lin(0, Ui.MATCH, 1f).apply { if (n > 0) leftMargin = u(10) }) }
        col.addView(nav, Ui.lin(Ui.MATCH, u(56)).apply { topMargin = u(14) })
    

        val apps = Ui.hbox(a)
        fun tileOf(icon: View, label: String): LinearLayout {
            val b = Ui.vbox(a).apply { gravity = Gravity.CENTER; background = ChamferDrawable(cardBg, hair, 0, u(18).toFloat(), cardPr) }
            b.addView(sportPlate(icon, t.accent), Ui.lin(u(66), u(64)))
            b.addView(Ui.text(a, 15, Color.WHITE, cond).apply { text = label.uppercase(ru); gravity = Gravity.CENTER; letterSpacing = 0.04f }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10); leftMargin = u(4); rightMargin = u(4) })
            return b
        }
        fun fill() {
            apps.removeAllViews()
            visibleTiles().forEachIndexed { n, i -> apps.addView(tileOf(tileIcon(i), tileTitle(i)).also { bindTile(it, i) }, Ui.lin(0, Ui.MATCH, 1f).apply { if (n > 0) leftMargin = u(10) }) }
            apps.addView(tileOf(Ui.icon(a, R.drawable.ic_apps, Color.WHITE).apply { setPadding(u(6), u(6), u(6), u(6)) }, "Все").apply { isClickable = true; setOnClickListener { a.show(1) } },
                Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(10) })
        }
        fill(); appsRebuild = { fill() }
        col.addView(apps, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(14) })
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ====================================================================================
    // v1.6 «приборные» layouts (originals inspired by AGAMA / CarWebGuru templates; no OBD, no map, no visualizer)
    // Decor is cached bitmaps (Dash.kt); only speed / clock / media change.
    // ====================================================================================
    private class DashCtx(val light: Boolean, val accent: Int) {
        val fg = Dash.fg(light); val fg2 = Dash.fg2(light)
        val card = if (light) 0xE6FFFFFF.toInt() else 0xB80E1014.toInt()
        val cardPr = if (light) 0xFFE3E6EA.toInt() else 0xD0262A32.toInt()
        val btn = if (light) 0x14000000 else 0x1FFFFFFF
        val btnPr = if (light) 0x2E000000 else 0x40FFFFFF
    }
    private fun dashCtx(): DashCtx {
        val d = DashCtx(Dash.light(a), Dash.accent())
        // day ↔ night in «auto»: checked once a minute, rebuilds only when the mode actually flips
        tickUpdaters.add { if (Prefs.dayMode == "auto" && Dash.light(a) != d.light) h.post { a.rebuild() } }
        return d
    }

    /** Chrome-rimmed holder (cheap gradient drawable) for the album cover. */
    private fun chromeHolder(child: View, radius: Int, round: Boolean, pad: Int = 5): FrameLayout {
        val f = FrameLayout(a).apply { setPadding(u(pad), u(pad), u(pad), u(pad)) }
        f.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF8E939C.toInt(), 0xFFEDEFF2.toInt(), 0xFF5B6069.toInt(), 0xFFC8CCD2.toInt())).apply {
            if (round) shape = android.graphics.drawable.GradientDrawable.OVAL else cornerRadius = u(radius + pad).toFloat()
        }
        if (child is CoverView) { child.shadow = false; child.circle = round; if (!round) child.radiusPx = u(radius).toFloat() }
        else if (round) {
            child.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            child.clipToOutline = true
        }
        f.addView(child, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return f
    }

    /** App dock: up to 8 visible tiles + «Все», labels in the layout's text colour. */
    private fun dashDock(d: DashCtx, icon: Int, label: Int): LinearLayout {
        val dock = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        fun fill() {
            dock.removeAllViews()
            for (i in visibleTiles()) dock.addView(appIcon(i, icon, label, labelColor = d.fg), Ui.lin(0, Ui.WRAP, 1f))
            val all = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener { a.show(1) } }
            val f = FrameLayout(a).apply { background = Ui.round(d.btn, u(icon * 0.23f)) }
            f.addView(Ui.icon(a, R.drawable.ic_apps, d.fg), Ui.frame(u(icon * 0.45f), u(icon * 0.45f)))
            all.addView(f, Ui.lin(u(icon), u(icon)))
            if (label > 0) all.addView(Ui.text(a, label, d.fg, Ui.MEDIUM).apply { text = "Все"; gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
            dock.addView(all, Ui.lin(0, Ui.WRAP, 1f))
        }
        fill(); appsRebuild = { fill() }
        return dock
    }

    private fun dashNav(d: DashCtx) = NavStyle(d.fg, d.fg2, d.btn, d.btnPr, d.accent, 26)

    private fun speedText(d: DashCtx, size: Int, unitSize: Int, center: Boolean = true): LinearLayout {
        val col = Ui.vbox(a).apply { gravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START }
        val num = Ui.text(a, size, d.fg, Typeface.create("sans-serif-condensed", Typeface.BOLD)).apply { text = "—"; includeFontPadding = false; gravity = Gravity.CENTER }
        val st = Ui.text(a, unitSize, d.fg2, Ui.MEDIUM).apply { letterSpacing = 0.2f; text = "КМ/Ч" }
        col.addView(num); col.addView(st)
        speedSinks.add(object : SpeedSink {
            override fun setSpeed(kmh: Int?, status: String) {
                num.text = kmh?.toString() ?: "—"; num.setTextColor(if (kmh == null) Ui.alpha(d.fg, 0.35f) else d.fg)
                st.text = if (kmh == null) "КМ/Ч · " + (if (status.startsWith("Нет доступа")) "нажмите для GPS" else "нет GPS") else "КМ/Ч"
            }
        })
        gpsClick(col)
        return col
    }

    // ---- 12. ПРИБОРКА: chrome binnacle, road with the SX4 in perspective, big digital speed, cover on the right ----
    private fun buildCluster(): View {
        val d = dashCtx()
        val root = FrameLayout(a)
        val binH = u(462); val m = u(16)
        root.addView(CachedArt(a) { cv, w, h ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), if (d.light) intArrayOf(0xF2EEF1F4.toInt(), 0xF2CDD3DA.toInt()) else intArrayOf(0xF00B0D12.toInt(), 0xF5000000.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            val r = android.graphics.RectF(m.toFloat(), m.toFloat(), (w - m).toFloat(), (m + binH).toFloat())
            p.shader = android.graphics.RadialGradient(w / 2f, m + binH * 0.4f, w * 0.6f, if (d.light) intArrayOf(0xFFFAFBFC.toInt(), 0xFFDDE2E8.toInt()) else intArrayOf(0xFF161A22.toInt(), 0xFF030406.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRoundRect(r, u(150).toFloat(), u(150).toFloat(), p)
            Dash.chromeRim(cv, r, u(150).toFloat(), u(7).toFloat(), d.light)
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(m, m, m, u(10)) }
        val bin = Ui.hbox(a).apply { setPadding(u(56), u(30), u(56), u(30)) }
        val left = Ui.vbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(clockText(66, d.fg, 44).apply { typeface = Ui.LIGHT }, Ui.lin(Ui.MATCH, u(78)))
        left.addView(dateText(17, d.fg2), Ui.lin(Ui.MATCH, Ui.WRAP))
        left.addView(navPillColumn(dashNav(d), 56, 30, 17, 10), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(22) })
        if (Prefs.camTile) left.addView(camPill(dashNav(d), 56, 30, 17), Ui.lin(Ui.MATCH, u(56)).apply { topMargin = u(10) })
        bin.addView(left, Ui.lin(u(250), Ui.MATCH))
        val ctr = FrameLayout(a)
        ctr.addView(road(d.accent, d.light, carH = 0.64f, maxCarW = 0.5f, horizon = 0.3f, ground = 0.95f), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        ctr.addView(speedText(d, 104, 15), FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = u(2) })
        bin.addView(ctr, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(10); rightMargin = u(10) })
        val right = Ui.vbox(a).apply { gravity = Gravity.CENTER }
        clickableMedia(right)
        mediaTexts(12, 21, 16, 1, d.fg, Ui.BOLD); artPad = 0.26f
        srcTv.setTextColor(d.accent); for (tv in listOf(srcTv, titleTv, artistTv)) tv.gravity = Gravity.CENTER
        artistTv.setTextColor(d.fg2)
        right.addView(chromeHolder(mediaArt(true, 20), 20, false), Ui.lin(u(212), u(212)))
        right.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        right.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        right.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        right.addView(progress(false, 3, d.fg, d.accent), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        bin.addView(right, Ui.lin(u(250), Ui.MATCH))
        col.addView(bin, Ui.lin(Ui.MATCH, binH))
        val bottom = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(20), 0, u(12), 0) }
        bottom.addView(dashDock(d, 56, 14), Ui.lin(0, Ui.WRAP, 1f))
        bottom.addView(controls(60, 74, 14, 28, 36, fg = d.fg, playBg = d.accent, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(24) })
        col.addView(bottom, Ui.lin(Ui.MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ---- 13. ХРОНОГРАФ: analog speedometer + analog clock with date window and weekday sub-dial ----
    private fun buildChrono(): View {
        val d = dashCtx()
        val root = FrameLayout(a)
        root.addView(CachedArt(a) { cv, w, h ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), if (d.light) intArrayOf(0xF5F3F0EA.toInt(), 0xF5D9D3C8.toInt()) else intArrayOf(0xF2181A1F.toInt(), 0xF7040506.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            // brushed-metal hairlines (static)
            p.shader = null; p.strokeWidth = 1f
            val rnd = java.util.Random(3)
            for (y in 0 until h step 3) { p.color = if (d.light) Color.argb(rnd.nextInt(10), 0, 0, 0) else Color.argb(rnd.nextInt(9), 255, 255, 255); cv.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p) }
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(20), u(16), u(20), u(12)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val sp = ChronoDial(a, d.accent, d.light, true); speedSinks.add(sp); gpsClick(sp)
        top.addView(sp, Ui.lin(u(440), u(440)))
        val mid = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
        clickableMedia(mid)
        mediaTexts(12, 20, 15, 2, d.fg, Ui.BOLD); artPad = 0.26f
        srcTv.setTextColor(d.accent); for (tv in listOf(srcTv, titleTv, artistTv)) tv.gravity = Gravity.CENTER
        artistTv.setTextColor(d.fg2)
        mid.addView(clockText(40, d.fg, 30).apply { gravity = Gravity.CENTER; typeface = Ui.LIGHT }, Ui.lin(Ui.MATCH, u(48)))
        mid.addView(chromeHolder(mediaArt(true, 0), 0, true, 4), Ui.lin(u(168), u(168)).apply { topMargin = u(10) })
        mid.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        mid.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        mid.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        mid.addView(progress(false, 2, d.fg, d.accent), Ui.lin(u(200), Ui.WRAP).apply { topMargin = u(10) })
        mid.addView(controls(50, 60, 8, 24, 30, fg = d.fg, playBg = d.accent, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(8) })
        top.addView(mid, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(8); rightMargin = u(8) })
        val clk = ChronoDial(a, d.accent, d.light, false)
        tickUpdaters.add { clk.invalidate() }
        top.addView(clk, Ui.lin(u(440), u(440)))
        col.addView(top, Ui.lin(Ui.MATCH, u(452)))
        col.addView(navPillRow(dashNav(d), 50, 28, 16), Ui.lin(Ui.WRAP, u(50)).apply { topMargin = u(6); gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(dashDock(d, 56, 14), Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(8) })
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ---- 14. РАДИАЛ: status bar, round cover inside a speed arc, pill buttons on both sides, leather ----
    private fun buildRadial(): View {
        val d = dashCtx()
        val root = FrameLayout(a)
        root.addView(CachedArt(a) { cv, w, h ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = Dash.leatherShader(a, d.light); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = android.graphics.RadialGradient(w / 2f, h * 0.48f, w * 0.62f, intArrayOf(0, if (d.light) 0x40000000 else 0xB0000000.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            // stitched top bar
            p.color = if (d.light) 0x30FFFFFF else 0x66000000; cv.drawRect(0f, 0f, w.toFloat(), u(58).toFloat(), p)
            p.color = Ui.alpha(d.accent, 0.55f); p.strokeWidth = u(1.5f).toFloat()
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(u(8).toFloat(), u(6).toFloat()), 0f)
            cv.drawLine(0f, u(62).toFloat(), w.toFloat(), u(62).toFloat(), p); p.pathEffect = null
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val col = Ui.vbox(a)
        // status bar
        val bar = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(28), 0, u(28), 0) }
        val wifi = Ui.icon(a, R.drawable.ic_wifi, d.fg); val bt = Ui.icon(a, R.drawable.ic_bt, d.fg); val gps = Ui.icon(a, R.drawable.ic_gps, d.fg)
        for (v in listOf(wifi, bt, gps)) bar.addView(v, Ui.lin(u(24), u(24)).apply { rightMargin = u(18) })
        bar.addView(dateText(17, d.fg2).apply { gravity = Gravity.CENTER }, Ui.lin(0, Ui.WRAP, 1f))
        bar.addView(clockText(30, d.fg).apply { typeface = Ui.MEDIUM })
        val upd = {
            val cm = a.getSystemService(android.net.ConnectivityManager::class.java)
            val wifiOn = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } catch (e: Exception) { false }
            @Suppress("DEPRECATION") val btOn = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: Exception) { false }
            wifi.alpha = if (wifiOn) 1f else 0.3f; bt.alpha = if (btOn) 1f else 0.3f
            gps.alpha = if (lastFix != 0L && SystemClock.elapsedRealtime() - lastFix < 5000) 1f else 0.3f
            if (wifiOn) wifi.imageTintList = android.content.res.ColorStateList.valueOf(d.accent)
            if (btOn) bt.imageTintList = android.content.res.ColorStateList.valueOf(d.accent)
        }
        upd(); tickUpdaters.add(upd)
        speedSinks.add(object : SpeedSink { override fun setSpeed(kmh: Int?, status: String) {
            val on = kmh != null; gps.alpha = if (on) 1f else 0.3f
            gps.imageTintList = android.content.res.ColorStateList.valueOf(if (on) d.accent else d.fg) } })
        col.addView(bar, Ui.lin(Ui.MATCH, u(60)))

        val body = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(24), 0, u(24), 0) }
        fun pill(icon: View, label: String, bind: (View) -> Unit): View {
            val p = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(10), 0, u(20), 0) }
            val bg = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                if (d.light) intArrayOf(0xF2FFFFFF.toInt(), 0xF2E4E1DB.toInt()) else intArrayOf(0xF2262830.toInt(), 0xF20C0D10.toInt())).apply {
                cornerRadius = u(38).toFloat(); setStroke(u(2), Ui.alpha(d.accent, 0.6f)) }
            val pr = android.graphics.drawable.GradientDrawable().apply { cornerRadius = u(38).toFloat(); setColor(Ui.alpha(d.accent, 0.35f)); setStroke(u(2), d.accent) }
            p.background = android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), pr); addState(intArrayOf(), bg) }
            val ic = FrameLayout(a).apply { background = Ui.oval(Ui.alpha(d.accent, 0.18f)) }
            ic.addView(icon, Ui.frame(u(36), u(36)))
            p.addView(ic, Ui.lin(u(56), u(56)))
            p.addView(Ui.text(a, 19, d.fg, Ui.MEDIUM).apply { text = label }, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(14) })
            bind(p)
            return p
        }
        fun side(items: List<Triple<View, String, (View) -> Unit>>): LinearLayout {
            val c = Ui.vbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
            items.forEachIndexed { n, (ic, l, b) -> c.addView(pill(ic, l, b), Ui.lin(Ui.MATCH, u(76)).apply { if (n > 0) topMargin = u(22) }) }
            return c
        }
        val appsIc = Ui.icon(a, R.drawable.ic_apps, d.accent)
        body.addView(side(listOf(
            Triple(tileIcon(0), "Навигатор", { v: View -> bindTile(v, 0) }),
            Triple(tileIcon(1), "Радио", { v: View -> bindTile(v, 1) }),
            Triple(tileIcon(4), "Музыка", { v: View -> bindTile(v, 4) }))), Ui.lin(0, Ui.MATCH, 1f))
        val hub = FrameLayout(a)
        val rh = RadialHub(a, d.accent, d.light); speedSinks.add(rh)
        hub.addView(rh, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        clickableMedia(hub)
        hub.addView(chromeHolder(mediaArt(true, 0), 0, true, 0).apply { background = null }, Ui.frame(u(240), u(240)))
        artPad = 0.28f
        val badge = speedText(d, 34, 11)
        badge.setPadding(u(18), u(4), u(18), u(6)); badge.background = Ui.round(if (d.light) 0xF2FFFFFF.toInt() else 0xF20C0D10.toInt(), u(30))
        hub.addView(badge, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = u(4) })
        body.addView(hub, Ui.lin(u(420), u(420)).apply { leftMargin = u(30); rightMargin = u(30) })
        body.addView(side(listOf(
            Triple(tileIcon(2), "Телефон", { v: View -> bindTile(v, 2) }),
            Triple(tileIcon(5), "YouTube", { v: View -> bindTile(v, 5) }),
            Triple(appsIc, "Приложения", { v: View -> v.isClickable = true; v.setOnClickListener { a.show(1) } }))), Ui.lin(0, Ui.MATCH, 1f))
        col.addView(body, Ui.lin(Ui.MATCH, u(440)).apply { topMargin = u(12) })

        mediaTexts(12, 22, 16, 1, d.fg, Ui.BOLD)
        srcTv.setTextColor(d.accent); artistTv.setTextColor(d.fg2)
        val info = Ui.hbox(a).apply { gravity = Gravity.CENTER; setPadding(u(40), 0, u(40), 0) }
        val tcol = Ui.vbox(a).apply { gravity = Gravity.END }
        for (tv in listOf(srcTv, titleTv, artistTv)) { tv.gravity = Gravity.END; tcol.addView(tv, Ui.lin(Ui.MATCH, Ui.WRAP)) }
        tcol.addView(progress(false, 2, d.fg, d.accent), Ui.lin(u(260), Ui.WRAP).apply { topMargin = u(8) })
        info.addView(tcol, Ui.lin(0, Ui.WRAP, 1f))
        info.addView(controls(58, 72, 14, 28, 34, fg = d.fg, playBg = d.accent, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(32); rightMargin = u(32) })
        val rightBox = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        rightBox.addView(navPill(navEntries().first(), dashNav(d), 56, 30, 17), Ui.lin(Ui.WRAP, u(56)))
        info.addView(rightBox, Ui.lin(0, u(56), 1f))
        col.addView(info, Ui.lin(Ui.MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        appsRebuild = { a.rebuild() }
        return root
    }

    // ---- 15. ВИТРИНА: the SX4 from behind on an open road (v1.6.2), big clock and speed, media + navigator column ----
    private fun buildShowcase(): View {
        val d = dashCtx()
        val root = FrameLayout(a)
        root.addView(CachedArt(a) { cv, w, h ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = if (d.light) 0xFFEEF0F3.toInt() else 0xFF06070A.toInt(); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = android.graphics.RadialGradient(w * 0.36f, h * 0.42f, w * 0.55f,
                if (d.light) intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9ECF0.toInt(), 0xFFD3D8DF.toInt()) else intArrayOf(0xFF2E3440.toInt(), 0xFF12151B.toInt(), 0xFF06070A.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val row = Ui.hbox(a).apply { setPadding(u(24), u(20), u(20), u(14)) }
        val stage = FrameLayout(a)
        stage.addView(road(d.accent, d.light, carH = 0.84f, maxCarW = 0.5f, horizon = 0.08f, ground = 0.94f), FrameLayout.LayoutParams(Ui.MATCH, u(460), Gravity.TOP).apply { topMargin = u(90) })
        val tl = Ui.vbox(a)
        tl.addView(clockText(92, d.fg, 60).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); includeFontPadding = false })
        tl.addView(dateText(18, d.fg2))
        stage.addView(tl, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START))
        stage.addView(speedText(d, 76, 14), FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = u(12) })
        val model = Ui.text(a, 14, d.fg2, Ui.MEDIUM).apply { text = "SUZUKI SX4 S-CROSS"; letterSpacing = 0.35f }
        stage.addView(model, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = u(540) })
        stage.addView(dashDock(d, 54, 13), FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        row.addView(stage, Ui.lin(0, Ui.MATCH, 1f))

        val right = Ui.vbox(a)
        val mc = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(16)); gravity = Gravity.CENTER_HORIZONTAL }
        Ui.card(mc, solid = d.card, radius = 28); clickableMedia(mc)
        mediaTexts(12, 22, 16, 1, d.fg, Ui.BOLD); artPad = 0.26f
        srcTv.setTextColor(d.accent); artistTv.setTextColor(d.fg2)
        for (tv in listOf(srcTv, titleTv, artistTv)) tv.gravity = Gravity.CENTER
        mc.addView(mediaArt(true, 18), Ui.lin(u(176), u(176)))
        mc.addView(srcTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        mc.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        mc.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        mc.addView(progress(false, 3, d.fg, d.accent), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        mc.addView(controls(54, 66, 12, 26, 32, fg = d.fg, playBg = d.accent, playIconFg = Color.WHITE), Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(12) })
        right.addView(mc, Ui.lin(Ui.MATCH, Ui.WRAP))
        val nv = Ui.vbox(a).apply { setPadding(u(16), u(16), u(16), u(16)) }
        Ui.card(nv, solid = d.card, radius = 28)
        nv.addView(navPillColumn(dashNav(d), 50, 28, 16, 8), Ui.lin(Ui.MATCH, Ui.WRAP))
        if (Prefs.camTile) nv.addView(camPill(dashNav(d), 50, 28, 16), Ui.lin(Ui.MATCH, u(50)).apply { topMargin = u(8) })
        right.addView(nv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        row.addView(right, Ui.lin(u(330), Ui.MATCH).apply { leftMargin = u(16) })
        root.addView(row, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return root
    }

    // ====================================================================================
    // v1.7.1 ЭКРАНЫ (default): «Главная / Музыка / Камера», swiped horizontally, big switcher on top + page dots
    // ====================================================================================
    private var pager: HomePager? = null
    private val segBtns = ArrayList<LinearLayout>()
    private var dots: PageDots? = null
    private var segAccent = 0
    private var segFg = Color.WHITE
    val pageCount: Int get() = pager?.childCount ?: 0
    val currentPage: Int get() = pager?.page ?: 0
    /** Jump to a page (switcher buttons, «Главная» in the menu, ‹ › in the menu). */
    fun goPage(i: Int, animate: Boolean = true) { val p = pager ?: return; p.go(if (p.childCount == 0) 0 else ((i % p.childCount) + p.childCount) % p.childCount, animate); markPage(p.page) }
    private fun markPage(i: Int) {
        Prefs.homePage = i
        dots?.current = i
        segBtns.forEachIndexed { k, b ->
            val on = k == i
            b.background = Ui.pressable(if (on) segAccent else 0, u(26), if (on) Ui.blend(segAccent, 0x40000000) else Ui.alpha(segFg, 0.16f))
            b.isSelected = on
            val fg = if (on) Color.WHITE else Ui.alpha(segFg, 0.8f)
            (b.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            (b.getChildAt(1) as? TextView)?.setTextColor(fg)
        }
    }

    /** Big quick button: icon + label, ≥ 84 dp high. */
    private fun bigBtn(d: DashCtx, icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        val b = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(18), 0, u(14), 0); minimumHeight = u(84) }
        b.background = Ui.pressable(d.card, u(24), d.cardPr)
        b.addView(Ui.icon(a, icon, d.accent), Ui.lin(u(40), u(40)))
        b.addView(Ui.text(a, 21, d.fg, Ui.MEDIUM, 2).apply { text = label }, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(14) })
        b.isClickable = true; b.contentDescription = label.replace("\n", " "); b.setOnClickListener { onClick() }
        return b
    }

    private fun buildScreens(): View {
        val d = dashCtx()
        segAccent = d.accent; segFg = d.fg
        val pages = ArrayList<Pair<String, Int>>()
        pages.add("Главная" to R.drawable.ic_home); pages.add("Музыка" to R.drawable.ic_music)
        if (Prefs.camPage) pages.add("Камера" to R.drawable.ic_videocam)

        val root = Ui.vbox(a)
        // switcher: always visible, three big segments (icon + label), the active one filled with the accent
        val seg = Ui.hbox(a).apply { gravity = Gravity.CENTER; setPadding(u(6), u(6), u(6), u(6)); background = Ui.round(if (d.light) 0x1A000000 else 0x99000000.toInt(), u(32)) }
        segBtns.clear()
        pages.forEachIndexed { i, (label, icon) ->
            val b = Ui.hbox(a).apply { gravity = Gravity.CENTER; setPadding(u(22), 0, u(26), 0); isClickable = true; contentDescription = "Экран «$label»" }
            b.addView(Ui.icon(a, icon, d.fg), Ui.lin(u(34), u(34)))
            b.addView(Ui.text(a, 22, d.fg, Ui.BOLD, 1).apply { text = label }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(10) })
            b.setOnClickListener { goPage(i) }
            segBtns.add(b)
            seg.addView(b, Ui.lin(u(220), u(68)).apply { if (i > 0) leftMargin = u(6) })
        }
        val head = FrameLayout(a)
        head.addView(seg, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_HORIZONTAL))
        root.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        val dt = PageDots(a, pages.size, d.accent, Ui.alpha(d.fg, 0.3f)); dots = dt
        root.addView(dt, Ui.lin(Ui.MATCH, u(14)).apply { topMargin = u(6) })

        val pg = HomePager(a); pager = pg
        pg.addView(pageHome(d, pg))
        pg.addView(pageMusic())
        if (Prefs.camPage) pg.addView(pageCamera(d))
        pg.onPage = { markPage(it) }
        root.addView(pg, Ui.lin(Ui.MATCH, 0, 1f))
        val start = Prefs.homePage.coerceIn(0, pages.size - 1)
        pg.go(start, false); markPage(start)
        return root
    }

    // ---- screen 1 «Главная»: clock/date, quick buttons, compact now-playing strip, dock apps ----
    private var stripArt: CoverView? = null
    private var stripTitle: TextView? = null
    private var stripArtist: TextView? = null
    private var stripPlay: ImageView? = null
    private fun syncStrip() {
        val t = stripTitle ?: return
        if (!::titleTv.isInitialized || !::playIv.isInitialized) return
        t.text = titleTv.text
        stripArtist?.text = listOf(srcTv.text, artistTv.text).filter { it.isNotBlank() }.joinToString(" · ")
        stripPlay?.setImageDrawable(playIv.drawable)
        val sa = stripArt ?: return
        val b = lastArt
        if (b != null && !idle) { sa.setPadding(0, 0, 0, 0); sa.scaleType = ImageView.ScaleType.CENTER_CROP; sa.setCover(b) }
        else {
            sa.setCover(null); sa.placeholderPad = 0.26f; sa.placeholderBg = Ui.alpha(Color.WHITE, 0.08f); sa.scaleType = ImageView.ScaleType.FIT_CENTER
            sa.setImageResource(if (radioArt) R.drawable.tile_radio else if (phoneArt) R.drawable.tile_phone else R.drawable.tile_music)
        }
    }

    private fun pageHome(d: DashCtx, pg: HomePager): View {
        val p = Ui.vbox(a).apply { setPadding(u(20), u(10), u(20), u(12)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val left = Ui.vbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(clockText(118, d.fg, 70).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = false; maxLines = 1 }, Ui.lin(Ui.MATCH, Ui.WRAP))
        left.addView(dateText(26, d.fg2).apply { maxLines = 1 }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(2) })
        val sp = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(16), 0, u(20), 0); background = Ui.round(d.card, u(24)) }
        val num = Ui.text(a, 30, d.fg, Ui.BOLD).apply { text = "—" }
        sp.addView(Ui.icon(a, R.drawable.ic_speed, d.accent), Ui.lin(u(30), u(30)))
        sp.addView(num, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(10) })
        sp.addView(Ui.text(a, 18, d.fg2, Ui.MEDIUM).apply { text = " км/ч" })
        speedSinks.add(TextSpeed(num, null, d.fg)); gpsClick(sp)
        left.addView(sp, Ui.lin(Ui.WRAP, u(64)).apply { topMargin = u(14) })
        top.addView(left, Ui.lin(0, Ui.WRAP, 1f))
        // quick buttons 2 × 2
        val grid = Ui.vbox(a)
        val nav = navEntries().first()
        fun row(b1: View, b2: View) = Ui.hbox(a).apply {
            addView(b1, Ui.lin(0, u(88), 1f)); addView(b2, Ui.lin(0, u(88), 1f).apply { leftMargin = u(12) })
        }
        val navB = bigBtn(d, R.drawable.ic_nav, "Навигатор") { }; bindNav(navB, nav)
        val camB = bigBtn(d, R.drawable.ic_videocam, "Камера") { if (Prefs.camPage) goPage(2) else CamApps.openCamera(a) }
        grid.addView(row(navB, camB))
        grid.addView(row(bigBtn(d, R.drawable.ic_bt_switch, "Сменить\nтелефон") { Rail.run(a, "phone") },
            bigBtn(d, R.drawable.ic_wifi, "Wi-Fi") { Rail.run(a, "wifi") }), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        top.addView(grid, Ui.lin(u(500), Ui.WRAP).apply { leftMargin = u(20) })
        p.addView(top, Ui.lin(Ui.MATCH, 0, 1f))

        // compact now-playing strip (mirrors the «Музыка» screen; tap → that screen)
        val st = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(12), u(10), u(14), u(10)) }
        st.background = Ui.pressable(d.card, u(26), d.cardPr)
        st.isClickable = true; st.contentDescription = "Сейчас играет"; st.setOnClickListener { goPage(1) }
        val sa = CoverView(a, true).apply { radiusPx = u(16).toFloat(); shadow = false }; stripArt = sa
        st.addView(sa, Ui.lin(u(84), u(84)))
        val tx = Ui.vbox(a).apply { setPadding(u(16), 0, u(10), 0) }
        stripTitle = Ui.text(a, 24, d.fg, Ui.BOLD, 1).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
        stripArtist = Ui.text(a, 17, d.fg2, Ui.SANS, 1).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
        tx.addView(stripTitle); tx.addView(stripArtist, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        st.addView(tx, Ui.lin(0, Ui.WRAP, 1f))
        val bb = Ui.alpha(d.fg, 0.12f); val bp = Ui.alpha(d.fg, 0.26f)
        st.addView(Ui.iconButton(a, R.drawable.ic_prev, 80, 36, bb, d.fg, bp) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }.apply { contentDescription = "Предыдущий" })
        val pl = Ui.iconButton(a, R.drawable.ic_play, 88, 42, d.accent, Color.WHITE, Ui.blend(d.accent, 0x40000000)) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }.apply { contentDescription = "Играть / пауза" }
        stripPlay = pl.getChildAt(0) as ImageView
        st.addView(pl, (pl.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = u(12); rightMargin = u(12) })
        st.addView(Ui.iconButton(a, R.drawable.ic_next, 80, 36, bb, d.fg, bp) { media(KeyEvent.KEYCODE_MEDIA_NEXT) }.apply { contentDescription = "Следующий" })
        p.addView(st, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })

        // the most-used apps (the dock tiles)
        p.addView(dashDock(d, 76, 15), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        @Suppress("UNUSED_VARIABLE") val unused = pg
        return p
    }

    // ---- screen 2 «Музыка»: big sharp cover, track info, big controls; colours from the cover ----
    private var musicBg: View? = null
    private var musicPlayBtn: View? = null
    private var lastArt: Bitmap? = null
    private var musicGen = 0
    private fun pageMusic(): View {
        val f = FrameLayout(a)
        val bg = View(a); musicBg = bg
        f.addView(bg, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        tintMusic(null)
        val row = Ui.hbox(a).apply { setPadding(u(24), u(12), u(24), u(16)); gravity = Gravity.CENTER_VERTICAL }
        clickableMedia(row)
        mediaTexts(17, 44, 26, 2); artPad = 0.26f
        val art = FrameLayout(a)
        art.addView(mediaArt(true, 30), FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
        row.addView(art, Ui.lin(Ui.WRAP, Ui.MATCH))
        val col = Ui.vbox(a).apply { setPadding(u(34), u(6), 0, u(4)) }
        col.addView(srcTv)
        col.addView(titleTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        col.addView(artistTv, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        col.addView(View(a), Ui.lin(1, 0, 1f))
        col.addView(progress(true, 8), Ui.lin(Ui.MATCH, Ui.WRAP).apply { bottomMargin = u(16) })
        val ctl = controls(104, 132, 28, 46, 60)
        musicPlayBtn = playIv.parent as? View
        tintMusic(null)
        col.addView(ctl, Ui.lin(Ui.WRAP, Ui.WRAP))
        val extra = Ui.hbox(a)
        fun pill(icon: Int, label: String, onClick: () -> Unit) = Ui.hbox(a).apply {
            gravity = Gravity.CENTER; setPadding(u(18), 0, u(22), 0); background = Ui.pressable(0x33FFFFFF, u(36), 0x55FFFFFF)
            addView(Ui.icon(a, icon, Color.WHITE), Ui.lin(u(30), u(30)))
            addView(Ui.text(a, 19, Color.WHITE, Ui.MEDIUM, 1).apply { text = label }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(10) })
            isClickable = true; contentDescription = label; setOnClickListener { onClick() }
        }
        extra.addView(pill(R.drawable.ic_music, "Источник") { chooseSource() }, Ui.lin(Ui.WRAP, u(72)))
        extra.addView(pill(R.drawable.ic_apps, "Открыть плеер") { mediaCard.performClick() }, Ui.lin(Ui.WRAP, u(72)).apply { leftMargin = u(12) })
        col.addView(extra, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(18) })
        row.addView(col, Ui.lin(0, Ui.MATCH, 1f))
        f.addView(row, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return f
    }

    /** Background + play button + progress in the cover colour (calm dark tint so white text always reads). */
    private fun tintMusic(c: Int?) {
        val bg = musicBg ?: return
        val base = c ?: T.accent
        val hsv = FloatArray(3); Color.colorToHSV(base, hsv)
        val deep = Color.HSVToColor(0xE6, floatArrayOf(hsv[0], (hsv[1] * 0.8f).coerceAtMost(0.75f), 0.30f))
        val dark = Color.HSVToColor(0xF2, floatArrayOf(hsv[0], (hsv[1] * 0.6f).coerceAtMost(0.6f), 0.08f))
        bg.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(deep, dark))
        val vivid = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.9f), hsv[2].coerceIn(0.7f, 0.95f)))
        musicPlayBtn?.background = Ui.pressableOval(vivid, Ui.blend(vivid, 0x40000000))
        if (::progBar.isInitialized) progBar.setFill(vivid)
        if (::srcTv.isInitialized) srcTv.setTextColor(Ui.blend(vivid, 0x66FFFFFF))
    }

    // ---- screen 3 «Камера»: rear camera tile / Cam REC + speedometer ----
    private fun pageCamera(d: DashCtx): View {
        val row = Ui.hbox(a).apply { setPadding(u(20), u(10), u(20), u(16)) }
        row.addView(cameraTile(28), Ui.lin(0, Ui.MATCH, 1.5f))
        val right = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(u(18), u(18), u(18), u(18)); background = Ui.round(d.card, u(28)) }
        right.addView(speedText(d, 150, 22), Ui.lin(Ui.MATCH, 0, 1f))
        right.addView(clockText(48, d.fg).apply { gravity = Gravity.CENTER; maxLines = 1 }, Ui.lin(Ui.MATCH, Ui.WRAP))
        right.addView(bigBtn(d, R.drawable.ic_videocam, "Записи\nрегистратора") { Rail.run(a, "dvr") }, Ui.lin(Ui.MATCH, u(84)).apply { topMargin = u(14) })
        right.addView(bigBtn(d, R.drawable.ic_videocam, "Открыть\nкамеру") { CamApps.openCamera(a) }, Ui.lin(Ui.MATCH, u(84)).apply { topMargin = u(12) })
        row.addView(right, Ui.lin(0, Ui.MATCH, 1f).apply { leftMargin = u(16) })
        return row
    }

    // ====================================================================================
    // media art
    // ====================================================================================
    private fun setIdle(v: Boolean) {
        idle = v
        if (v) { artKey = null; artGen++; lastArt = null; showArtPlaceholder(); Wallpaper.setCover(null); tintMusic(null) }
        syncStrip()
    }

    private fun showArtPlaceholder() {
        artIv.background = null
        (artIv as? CoverView)?.apply { setCover(null); placeholderBg = Ui.alpha(if (light) Color.BLACK else Color.WHITE, if (light) 0.06f else 0.08f) }
        artIv.imageTintList = null
        artIv.scaleType = ImageView.ScaleType.FIT_CENTER
        artIv.setImageResource(if (yt) R.drawable.brand_youtube else if (radioArt) R.drawable.tile_radio else if (phoneArt) R.drawable.tile_phone else R.drawable.tile_music)
        val cvw = artIv as? CoverView
        if (cvw != null) { cvw.placeholderPad = artPad; artIv.setPadding(0, 0, 0, 0); return }
        val p = (minOf(artIv.width, artIv.height).takeIf { it > 0 } ?: u(200)) * artPad
        artIv.setPadding(p.toInt(), p.toInt(), p.toInt(), p.toInt())
    }

    private fun showArt(b: Bitmap) {
        artIv.background = null
        artIv.imageTintList = null
        artIv.setPadding(0, 0, 0, 0)
        artIv.scaleType = ImageView.ScaleType.CENTER_CROP
        val cvw = artIv as? CoverView
        if (cvw != null) cvw.setCover(b) else artIv.setImageBitmap(b)
        Wallpaper.setCover(b)
        lastArt = b; syncStrip()
        if (musicBg != null) {   // «Музыка» screen recolours with every cover (tiny sample, off the UI thread)
            val g = ++musicGen
            AppRepo.exec.execute { val c = try { CoverColor.pick(b) } catch (_: Throwable) { null }; AppRepo.main.post { if (g == musicGen) tintMusic(c) } }
        }
    }

    private fun fmt(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

    private fun updateProgress() {
        if (!::progBox.isInitialized) return
        val c = controller
        val st = c?.playbackState
        val dur = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (st == null || dur <= 0L || st.position < 0) { progBox.visibility = View.INVISIBLE; return }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        pos = pos.coerceIn(0L, dur)
        progBar.fraction = pos.toFloat() / dur
        posTv.text = fmt(pos); durTv.text = "−" + fmt(dur - pos)
        progBox.visibility = View.VISIBLE
    }

    // ====================================================================================
    // clock tick (1/s) — text only; analog clock redrawn once a minute
    // ====================================================================================
    private var lastMinute = -1
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val tstr = SimpleDateFormat(if (Prefs.use24) "HH:mm" else "h:mm", ru).format(now)
            for (tv in timeViews) if (tv.text != tstr) tv.text = tstr
            val dstr = dateFmt.format(now).replaceFirstChar { it.titlecase(ru) }
            for (tv in dateViews) if (tv.text != dstr) tv.text = dstr
            val cal = java.util.Calendar.getInstance()
            val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            if (mins != lastMinute) {
                lastMinute = mins
                analogClocks.forEach { it.invalidate() }
                buildWeek()
                dayBar?.fraction = mins / 1440f
                dayTv?.text = "НЕДЕЛЯ ${cal.get(java.util.Calendar.WEEK_OF_YEAR)}  ·  ДЕНЬ ${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
                tickUpdaters.forEach { it() }
                a.checkRotation(false)
            }
            if (lastFix == 0L || SystemClock.elapsedRealtime() - lastFix > 5000) {
                val s = when {
                    !a.has(Manifest.permission.ACCESS_FINE_LOCATION) -> "Нет доступа к GPS — нажмите"
                    lastFix == 0L -> "Поиск спутников…"
                    else -> "Нет сигнала GPS"
                }
                speedSinks.forEach { it.setSpeed(null, s) }
                compasses.forEach { it.setBearing(null) }
            }
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) updateProgress()
            h.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    // ====================================================================================
    // GPS — speed, bearing, trip, map centre. Listener only while the home screen is visible.
    // ====================================================================================
    private var lastFix = 0L
    private var lastSaved = 0L
    private val lm by lazy { a.getSystemService(LocationManager::class.java) }
    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun onFix(l: Location) {
        lastFix = SystemClock.elapsedRealtime()
        val kmh = if (l.hasSpeed()) Math.round(l.speed * 3.6f) else 0
        val st = "GPS" + if (l.hasAccuracy()) " · ±${Math.round(l.accuracy)} м" else ""
        speedSinks.forEach { it.setSpeed(kmh, st) }
        val b = if (l.hasBearing() && kmh >= 3) l.bearing else null
        compasses.forEach { it.setBearing(b) }
        maps.forEach { it.setLocation(l.latitude, l.longitude, b) }
        if (lastFix - lastSaved > 60_000) { lastSaved = lastFix; Prefs.lastLat = l.latitude.toFloat(); Prefs.lastLon = l.longitude.toFloat() }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (speedSinks.isEmpty() && maps.isEmpty() && compasses.isEmpty()) return
        if (!a.has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper()) } catch (_: Exception) {}
    }

    // ---------------- media (v1.7.0: MediaHub follows every session, shows the one that plays) ----------------
    private val controller: MediaController? get() = MediaHub.current
    private val hubListener: () -> Unit = { if (MediaHub.access || MediaHub.radioActive() || MediaHub.phoneActive()) updateMedia() else noAccess(); syncStrip() }

    private fun listenerEnabled(): Boolean = MediaHub.listenerEnabled(a)

    private fun startMedia() {
        if (!MediaHub.attach(a, hubListener) && !MediaHub.radioActive() && !MediaHub.phoneActive()) noAccess() else updateMedia()
        syncStrip()
    }

    private fun stopMedia() { MediaHub.detach(hubListener) }

    private fun noAccess() {
        srcTv.text = "МУЗЫКА"
        titleTv.text = "Нет доступа к плееру"
        artistTv.text = "Нажмите: «Доступ к уведомлениям» → Car Launcher — тогда здесь будет любая музыка (Я.Музыка, BT, радио…)"
        setIdle(true)
        progBox.visibility = View.INVISIBLE
        playIv.setImageResource(R.drawable.ic_play)
        mediaCard.setOnClickListener {
            AppRepo.startSafe(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Long press on the music card: pick the source by hand (all sessions + Bluetooth phone + radio). */
    private fun chooseSource() {
        val list = MediaHub.all
        val names = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        for (c in list) {
            val st = if (MediaHub.isPlaying(c.playbackState?.state)) " · играет" else ""
            names.add(Tiles.label(a, c.packageName) + st + (MediaHub.title(c)?.let { " — $it" } ?: ""))
            acts.add { MediaHub.pin(c.packageName) }
        }
        CamApps.btMusic(a)?.let { p -> names.add("Bluetooth-музыка с телефона (открыть)"); acts.add { AppRepo.launch(a, p) } }
        Tiles.resolve(a, 1).pkg?.let { p -> names.add("Радио магнитолы" + (if (MediaHub.radioActive()) " · играет" else "")); acts.add { MediaHub.pinRadio(p) } }
        names.add("Автоматически — что играет"); acts.add { MediaHub.pin(null) }
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("Источник музыки")
            .setItems(names.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }

    // ---- album art: bitmap from metadata, else *_URI via ContentResolver/HTTP; always downscaled off the UI thread ----
    private var artKey: String? = null
    private var artGen = 0

    private fun artTarget(): Int = artIv.height.takeIf { it > 0 } ?: u(460)

    private fun loadArt(md: MediaMetadata?) {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = if (bmp == null) (md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))?.takeIf { it.isNotBlank() } else null
        val key = if (bmp != null) "b" + System.identityHashCode(bmp) + "|" + bmp.width + "x" + bmp.height else uri?.let { "u$it" }
        if (key == artKey) return
        artKey = key
        val g = ++artGen
        if (key == null) { showArtPlaceholder(); return }
        val target = artTarget()
        if (bmp != null && maxOf(bmp.width, bmp.height) <= target) { showArt(bmp); return }
        val cr = a.contentResolver
        AppRepo.exec.execute {
            val out = try { if (bmp != null) ArtLoader.scale(bmp, target) else ArtLoader.fromUri(cr, uri!!, target) } catch (e: Throwable) { null }
            AppRepo.main.post {
                if (g != artGen) return@post
                if (out != null) showArt(out) else showArtPlaceholder()
            }
        }
    }

    /** v1.7.0: phone over Bluetooth that has no session (AVRCP / BT-app broadcasts) — placeholder «Телефон», keys go to the phone. */
    private var phoneArt = false
    private fun showPhone() {
        phoneArt = true
        srcTv.text = "ТЕЛЕФОН · BLUETOOTH"
        titleTv.text = BtMeta.title ?: "Музыка с телефона"
        artistTv.text = listOfNotNull(BtMeta.artist, BtMeta.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Играет на телефоне — кнопки управляют им" }
        playIv.setImageResource(if (BtMeta.playing) R.drawable.ic_pause else R.drawable.ic_play)
        a.glow?.setPlaying(BtMeta.playing)
        setIdle(true)
        progBox.visibility = View.INVISIBLE
        mediaCard.setOnClickListener { CamApps.btMusic(a)?.let { AppRepo.launch(a, it) } }
        mediaCard.setOnLongClickListener { chooseSource(); true }
    }

    /** v1.7.0: head-unit radio without a session — ⏮ ⏭ = previous / next station, ⏯ opens the radio. */
    private var radioArt = false
    private fun showRadio() {
        radioArt = true; phoneArt = false
        setIdle(true)
        srcTv.text = "РАДИО"
        titleTv.text = Radio.freq ?: "Радио"
        artistTv.text = "⏮ ⏭ — соседняя станция · нажмите, чтобы открыть радио"
        playIv.setImageResource(R.drawable.ic_pause)
        a.glow?.setPlaying(true)
        progBox.visibility = View.INVISIBLE
        mediaCard.setOnClickListener { Radio.open(a) }
        mediaCard.isLongClickable = true
        mediaCard.setOnLongClickListener { chooseSource(); true }
    }

    private fun updateMedia() {
        if (MediaHub.radioActive()) { showRadio(); return }
        if (radioArt) { radioArt = false; showArtPlaceholder() }
        if (MediaHub.phoneActive()) { showPhone(); return }
        val c = controller
        val phone = MediaHub.isPhone(a, c?.packageName)
        if (phone != phoneArt) { phoneArt = phone; if (idle) showArtPlaceholder() }
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val playing = MediaHub.isPlaying(c?.playbackState?.state)
        srcTv.text = (if (c != null) (if (phone) "Телефон · Bluetooth" else Tiles.label(a, c.packageName)) + (if (MediaHub.all.size > 1) "  ⇅" else "") else if (yt) "YouTube" else "МУЗЫКА").uppercase()
        titleTv.text = title ?: if (c == null) (if (yt) "YouTube" else "Ничего не играет") else if (playing) "Играет" else "Нажмите ▶, чтобы продолжить"
        artistTv.text = listOfNotNull(artist, album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty {
            if (c == null) (if (yt) "«Рядом» — рядом с лаунчером, или на весь экран" else "Нажмите — откроется плеер и заиграет. Долгое нажатие — выбор источника") else "" }
        playIv.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        a.glow?.setPlaying(playing)
        if (c == null) setIdle(true) else { if (idle) setIdle(false); loadArt(md) }
        updateProgress()
        mediaCard.setOnClickListener {
            val cur = controller?.packageName
            val p = cur ?: Prefs.lastPlayer.takeIf { it.isNotEmpty() && Tiles.installed(a, it) } ?: Tiles.resolve(a, 4).pkg
            if (p != null && AppRepo.launch(a, p) && Prefs.autoPlay && !MediaHub.playing) MediaHub.playWhenReady(a, p)
        }
        mediaCard.isLongClickable = true
        mediaCard.setOnLongClickListener { chooseSource(); true }
    }

    private fun media(code: Int) = MediaHub.command(a, code)

    // ---------------- lifecycle ----------------
    override fun onShow() {
        lastMinute = -1
        h.removeCallbacks(tick); tick.run()
        startGps()
        startMedia()
        a.glow?.setActive(true)
        if (Prefs.camLive) liveCams.forEach { it.startPreview() }
    }

    override fun onHide() {
        h.removeCallbacks(tick)
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        stopMedia()
        a.glow?.setActive(false)
        liveCams.forEach { it.stopPreview() }
    }

    override fun onPackagesChanged() = rebuildApps()
}
