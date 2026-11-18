package com.goga.carlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ck.put
import com.goga.carlauncher.W.lin
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** What plays now, independent of the source (session / phone over BT / FYT radio). */
class Np(val src: String, val title: String, val artist: String, val playing: Boolean, val pos: Long, val dur: Long, val pkg: String?, val md: MediaMetadata?) {
    val key get() = "$src|$pkg|$title|$artist"
    companion object {
        fun now(c: Context): Np {
            if (MediaHub.radioActive()) {
                val f = Radio.freq ?: "FM"
                return Np("radio", Stations.name(f) ?: "Радио", f, !Radio.muted, -1, -1, Radio.pkg, null)
            }
            if (MediaHub.phoneActive()) {
                return Np("phone", BtMeta.title ?: "Телефон", BtMeta.artist ?: "Bluetooth", BtMeta.playing, -1, -1, null, null)
            }
            val mc = MediaHub.current
            if (mc != null) {
                val md = mc.metadata
                val st = mc.playbackState
                var pos = st?.position ?: -1
                if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
                    pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1
                val title = MediaHub.title(mc) ?: Tiles.label(c, mc.packageName)
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: Tiles.label(c, mc.packageName)
                val src = if (MediaHub.isPhone(c, mc.packageName)) "phone" else "music"
                return Np(src, title, artist, MediaHub.isPlaying(st?.state), pos, dur, mc.packageName, md)
            }
            return Np("none", "Ничего не играет", "Нажмите ▶ или выберите источник", false, -1, -1, null, null)
        }
    }
}

/** Radio stations of Astana for names / colours (the FYT radio reports only the frequency). */
object Stations {
    class St(val f: Double, val name: String, val index: Int, val c0: Int, val c1: Int)
    private val COLORS = intArrayOf(0xFF3A7BD5.toInt(), 0xFF7B2FF7.toInt(), 0xFFFF4B2B.toInt(), 0xFFB31217.toInt(), 0xFF11998E.toInt(), 0xFF0B5D8A.toInt(),
        0xFFFF5FA2.toInt(), 0xFFC2185B.toInt(), 0xFFFF9F0A.toInt(), 0xFFE8590C.toInt(), 0xFF56AB2F.toInt(), 0xFF2B7A0B.toInt())
    /** the preset chips (v1.7.2: editable — hold a chip to store the current station there) */
    val ALL: List<St> get() = Presets.list().mapIndexed { i, p -> St(p.khz / 1000.0, p.name.ifEmpty { Presets.mhz(p.khz) }, i, COLORS[(i % 6) * 2], COLORS[(i % 6) * 2 + 1]) }
    fun mhz(f: String?): Double? = f?.removePrefix("FM")?.trim()?.replace(',', '.')?.toDoubleOrNull()
    fun of(f: String?): St? { val m = mhz(f) ?: return null; return ALL.firstOrNull { Math.abs(it.f - m) < 0.05 } }
    fun name(f: String?) = of(f)?.name ?: FmCtl.ps
}

/**
 * v1.7.1 main interface (approved mockup /workspace/car-design, styles C «Линза» and D «Стекло»). Two screens:
 * 0 Главная, 1 Музыка, 2 Фон (v1.7.4: full-screen photo / video + minimal music). Everything is placed in design px on a 1280×720 [Stage].
 */
@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class Cockpit(val a: MainActivity) {
    val root = Stage(a)
    private val st get() = root.stage
    val bg = BgView(a)
    private var video: VideoBg? = null
    val particles = Particles(a)
    /** v1.7.2: «Яркость фона» for every background (photos get it baked in; theme / cover / video — this veil) */
    private val dim = View(a)
    val page = FrameLayout(a)
    val over = FrameLayout(a)
    private val volo = FrameLayout(a)
    private lateinit var toast: TextView
    var screen = 0; private set
    /** v1.7.4: screen 2 = «Фон» (replaces the «Природа» mode of Музыка): full-screen photo / video + minimal music */
    val fon get() = screen == 2
    val overlays = Overlays(this)

    // live views (rebuilt with the page)
    private var lens: LensView? = null
    private var eq: EqView? = null
    private var gauge: GaugeD? = null
    private var clockTv: TextView? = null
    private var dateTv: TextView? = null
    private var speedTv: TextView? = null
    private val milTv = HashMap<String, TextView>()
    private var npTitle: TextView? = null
    private var npArtist: TextView? = null
    private var npCap: TextView? = null
    private var npCover: ImageView? = null
    private var playBtn: View? = null
    /** play / pause icon names of the current [playBtn] */
    private var playIcons = "pb_play" to "pb_pause"
    private var seek: SeekCk? = null
    private var tPos: TextView? = null
    private var tDur: TextView? = null
    private var wxIcon: ImageView? = null
    private var wxTemp: TextView? = null
    private var wxPlace: TextView? = null
    private var lowStrip: View? = null
    private val srcBtns = HashMap<String, View>()
    private val presetBtns = ArrayList<Pair<Stations.St, View>>()

    var np: Np = Np("none", "", "", false, -1, -1, null, null); private set
    private var art: Bitmap? = null
    private var artKey = ""
    private var resumed = false
    var editing = false
    private var tilePage = 0

    private val h = AppRepo.main
    private val tick = object : Runnable {
        override fun run() {
            try {
                updClock(); updMedia(false); Trip.checkDay(); if (Trip.checkTrip()) updMil()
                if (Trip.stale() && Trip.speed != null) updSpeed()
            } catch (t: Throwable) { CrashLog.soft(t) }
            h.postDelayed(this, 1000)
        }
    }
    private val mediaListener: () -> Unit = { updMedia(true) }
    private val wxListener: () -> Unit = { updWx(); if (bg.mode() == "scene" || bg.mode() == "video") bg.refresh(); updParticles() }
    // v1.7.3: the mileage on screen follows every GPS fix (1.7.1 refreshed only the speed → «Поездка» looked frozen)
    private val tripListener: () -> Unit = { updSpeed(); updMil() }

    private fun setup() {
        st.addView(bg, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(dim, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(particles, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(page, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(over, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(volo, FrameLayout.LayoutParams(Ck.W, Ck.H))
        volo.isClickable = false
        toast = Ck.text(a, 21f, Color.WHITE, Ck.face(true), 2, Gravity.CENTER).apply {
            setPadding(26, 14, 26, 14); background = Panel(0xE6141820.toInt(), 0x40FFFFFF, 1.2f, floatArrayOf(24f, 24f, 24f, 24f), false); visibility = View.GONE; maxWidth = 900
        }
        st.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = 110 })
        CkToast.sink = { showToast(it) }
        screen = when (K.startScreen) { "music" -> 1; "fon" -> 2; "last" -> K.lastScreen.coerceIn(0, 2); else -> 0 }
        tilePage = K.tilePage
        np = try { Np.now(a) } catch (_: Throwable) { np }
        setupSwipe()
        applyBackground()
        build()
    }

    // ------------------------------------------------------------------ lifecycle
    fun onStart() {
        MediaHub.attach(a, mediaListener)
        Wx.listeners.add(wxListener); Trip.listeners.add(tripListener)
        Trip.start(a); Wx.refresh(a)
        Eq.open(a)
        Videos.scanAsync(a) { onStorage() }
        Syu.listener = { volUpdate() }   // posted to the main thread by Syu
        h.removeCallbacks(tick); h.post(tick)
        updAll()
    }
    fun onResume() { resumed = true; overlays.onResume(); MediaHub.refresh(); updAll(); checkLow(); video?.pauseIfHidden(true); bg.updateRunning(); particles.update() }
    fun onPause() { resumed = false; video?.pauseIfHidden(false) }
    fun onStop() {
        MediaHub.detach(mediaListener); Wx.listeners.remove(wxListener); Trip.listeners.remove(tripListener)
        h.removeCallbacks(tick); Eq.close(); Syu.listener = null
        K.lastScreen = screen
    }
    fun destroy() { video?.release(); Trip.stop(); CkToast.sink = null }
    fun onPackages() { if (!editing) build(); overlays.onResume() }
    fun onStorage() { if (K.bg == "video") applyBackground(); checkLow(); overlays.storageChanged() }

    /** HOME key / «Главная»: close everything, show the home screen */
    fun home() { overlays.closeAll(); editing = false; show(0) }
    fun back(): Boolean = overlays.back() || (editing.also { if (it) { editing = false; build() } }) || (fon.also { if (it) show(1) })

    fun show(s: Int) {
        val changed = s != screen
        screen = s.coerceIn(0, 2); K.lastScreen = screen
        if (changed) { fonShown = true }
        applyBackground(); build()
    }

    /** style / size / theme changed → everything */
    fun rebuildAll() { applyBackground(); build() }

    // ------------------------------------------------------------------ background
    fun applyBackground() { try { applyBackgroundInner() } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun applyBackgroundInner() {
        bg.music = screen == 1; bg.fon = fon
        val mode = bg.mode()
        val photo = mode == "scene" || mode == "video" || mode == "cover"
        W.photoBehind = photo
        // v1.7.4: a video plays wherever the CURRENT screen's background is a video (also on «Фон»)
        val vf = if (mode == "video") Videos.chosen() else null
        if (vf != null) {
            if (video == null) { video = VideoBg(a).also { v -> v.onFail = { a.runOnUiThread { showToast("Видео не воспроизводится — показываю фото"); st.removeView(v); video = null } }; st.addView(v, 1, FrameLayout.LayoutParams(Ck.W, Ck.H)) } }
            video?.file = vf
        } else if (video != null) { video?.release(); st.removeView(video); video = null }
        bg.refresh()
        updParticles()
    }
    private fun updParticles() {
        val md = bg.mode()
        particles.kind = fxKind(md == "scene" || md == "video" || md == "cover")
        // brightness veil (not for photos / covers: their brightness is in the picture)
        val b = K.bright
        if (md == "scene" || md == "cover") dim.setBackgroundColor(0)
        else if (md == "video") dim.setBackgroundColor(Color.argb(Math.round((100 - b) / 100f * 0.85f * 255), 0, 0, 0))
        else if (b < 80) dim.setBackgroundColor(Color.argb(Math.round((80 - b) / 50f * 0.6f * 255).coerceIn(0, 255), 0, 0, 0))
        else dim.setBackgroundColor(Color.argb(Math.round((b - 80) / 20f * 0.14f * 255), 255, 255, 255))
    }
    /** rain / snow: «По погоде» → only on photos and only when it rains / snows; otherwise what the owner chose, on any background */
    companion object {
        /** hold time at the edge before a dragged tile flips to the next / previous page */
        const val FLIP_MS = 600L
        /** «Фон»: the controls hide after this long without a touch */
        const val FON_HIDE_MS = 6000L
        fun fxKind(photo: Boolean): String {
            if (!K.particles) return "none"
            if (!K.fxAuto) return if (K.fxKind == "rain" || K.fxKind == "snow") K.fxKind else "none"
            val k = Wx.kind()
            return if (photo && (k == "rain" || k == "snow")) k else "none"
        }
    }
    fun refreshFx() = updParticles()

    // ------------------------------------------------------------------ gestures
    private fun setupSwipe() {
        val gd = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (fon) { fonTouch(); return true }; return false }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || editing || overlays.open) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (Math.abs(dx) > 160 && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    // tiles row swipes pages, elsewhere screens
                    if (e1.y in tileTop..tileBottom && tilePages > 1) { pageTiles(if (dx < 0) 1 else -1); return true }
                    if (dx < 0 && screen < 2) { show(screen + 1); return true }
                    if (dx > 0 && screen > 0) { show(screen - 1); return true }
                }
                return false
            }
        })
        page.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        swipeDetector = gd
    }
    private var swipeDetector: GestureDetector? = null
    /** children consume touches: we still want swipes → intercept by a transparent layer under the controls */
    private fun swipeLayer(): View = View(a).apply { setOnTouchListener { _, e -> swipeDetector?.onTouchEvent(e); true } }
    private var tileTop = 0f; private var tileBottom = 0f; private var tilePages = 1

    // ------------------------------------------------------------------ build
    private fun big() = K.big
    fun build() {
        try { buildInner() } catch (t: Throwable) {
            // never leave the owner without a screen: log, then a plain fallback with the way to settings / classic screen
            CrashLog.soft(t)
            try {
                page.removeAllViews()
                val col = W.vbox(a).apply { gravity = Gravity.CENTER }
                col.addView(t(26f, Color.WHITE, true, 2, Gravity.CENTER).apply { text = "Экран не построился — ошибка записана в журнал" })
                val r = W.hbox(a).apply { gravity = Gravity.CENTER }
                r.addView(W.button(a, "Настройки", 200, 60) { overlays.settings("about") }, lin(-2, 60).apply { rightMargin = 12 })
                r.addView(W.button(a, "Стиль «Стекло», фон «Тема»", 320, 60, primary = true) { K.style = "D"; K.bg = "theme"; K.big = false; rebuildAll() }, lin(-2, 60))
                col.addView(r, lin(-2, -2).apply { topMargin = 24 })
                page.put(col, 0, 200, Ck.W, 260)
            } catch (_: Throwable) {}
        }
    }
    private fun buildInner() {
        page.removeAllViews(); milTv.clear(); srcBtns.clear(); presetBtns.clear()
        glows.clear(); lens = null; eq = null; gauge = null; clockTv = null; dateTv = null; speedTv = null; npTitle = null; npArtist = null; npCap = null; npCover = null
        playBtn = null; seek = null; tPos = null; tDur = null; wxIcon = null; wxTemp = null; wxPlace = null; lowStrip = null
        page.put(swipeLayer(), 0, 0, Ck.W, Ck.H)
        if (fon) buildFon() else if (Ck.isC) buildC() else buildD()
        if (!fon) checkLow()
        updAll()
    }

    private fun accent() = Ck.accent()
    private fun t(size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1, g: Int = Gravity.START or Gravity.CENTER_VERTICAL) = Ck.text(a, size, color, Ck.face(bold), lines, g)
    private fun orb(size: Float, color: Int = Color.WHITE, g: Int = Gravity.START or Gravity.CENTER_VERTICAL) = Ck.text(a, size, color, Ck.orb, 1, g)

    // ---- shared pieces ----
    /** [compact] = one line (Музыка «Стекло» / Природа: the cover starts right under it) */
    private fun weatherLine(x: Int, y: Int) {
        val box = W.hbox(a).apply { setPadding(14, 0, 18, 0) }
        val sz = if (big()) 34 else 30
        val ic = Ck.icon(a, Wx.icon()); wxIcon = ic; box.addView(ic, lin(sz, sz))
        val tt = (if (Ck.isC) orb(if (big()) 30f else 26f) else t(if (big()) 30f else 26f, Color.WHITE)); wxTemp = tt
        box.addView(tt, lin(-2, -2).apply { leftMargin = 10 })
        val pl = t(if (big()) 21f else 19f, 0xB3FFFFFF.toInt()); wxPlace = pl; box.addView(pl, lin(-2, -2).apply { leftMargin = 12 })
        box.background = Panel(if (W.photoBehind) 0x80060810.toInt() else 0x1AFFFFFF, 0x26FFFFFF, 1f, floatArrayOf(25f, 25f, 25f, 25f), false)
        box.contentDescription = "Погода"
        Ck.pressable(box) { overlays.weatherMenu() }
        page.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, if (big()) 54 else 50).apply { leftMargin = x; topMargin = y })
    }
    private fun weather(x: Int, y: Int, compact: Boolean = false) {
        if (compact) { weatherLine(x, y); return }
        val box = W.hbox(a)
        val ic = Ck.icon(a, Wx.icon()); wxIcon = ic
        box.addView(ic, lin(if (big()) 42 else 36, if (big()) 42 else 36))
        val col = W.vbox(a)
        val tt = if (Ck.isC) orb(if (big()) 40f else 34f) else t(if (big()) 42f else 36f).apply { typeface = Ck.sansL }
        wxTemp = tt; col.addView(tt)
        val pl = t(if (big()) 22f else 20f, 0x9EFFFFFF.toInt()); wxPlace = pl; col.addView(pl, lin(W.WR, W.WR).apply { topMargin = 2 })
        box.addView(col, lin(W.WR, W.WR).apply { leftMargin = 12 })
        if (W.photoBehind) { box.setPadding(12, 6, 18, 6); box.background = Panel(0x80060810.toInt(), 0x14FFFFFF, 1f, floatArrayOf(18f, 18f, 18f, 18f), false) }
        box.contentDescription = "Погода"
        Ck.pressable(box) { overlays.weatherMenu() }
        page.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = x; topMargin = y })
    }

    /** top-right: [Природа] [Фон] [⚙] (+ clock on Музыка) and the BT / Wi-Fi status icons */
    private fun topBar(music: Boolean, clock: Boolean) {
        val row = W.hbox(a)
        val ph = if (big()) 54 else 50
        // v1.7.3: ◀ Фон ▶ — next / previous background of the current kind right from the screen
        row.addView(bgArrow(-1, ph), lin(ph + 8, ph).apply { rightMargin = 6 })
        row.addView(W.pill(a, "layers", "Фон", ph, overlays.isBgPanel()) { overlays.bgPanel() }, lin(W.WR, ph).apply { rightMargin = 6 })
        row.addView(bgArrow(1, ph), lin(ph + 8, ph).apply { rightMargin = 12 })
        val gear = W.pill(a, "gear", null, ph) { overlays.settings() }; gear.contentDescription = "Настройки"
        row.addView(gear, lin(ph, ph))
        if (clock) { val ct = if (Ck.isC) orb(if (big()) 44f else 36f) else t(if (big()) 44f else 38f).apply { typeface = Ck.sansL }; clockTv = ct; row.addView(ct, lin(W.WR, W.WR).apply { leftMargin = 20 }) }
        page.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ph, Gravity.END or Gravity.TOP).apply { rightMargin = 40; topMargin = 30 })
        // status icons (open the system Bluetooth / Wi-Fi settings, change nothing themselves)
        val bt = Ck.icon(a, "bt", 0xB3FFFFFF.toInt()).apply { setPadding(10, 6, 10, 6); contentDescription = "Bluetooth" }
        Ck.pressable(bt) { SysOpen.open(a, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth") }
        val wf = Ck.icon(a, "wifi", 0xB3FFFFFF.toInt()).apply { setPadding(10, 6, 10, 6); contentDescription = "Wi-Fi" }
        Ck.pressable(wf) { SysOpen.open(a, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi") }
        page.put(bt, 1178, 0, 44, 30); page.put(wf, 1226, 0, 44, 30)
    }

    /** bottom switch «Главная / Музыка» + volume −/+ in both bottom corners */
    private fun bottom() {
        val bigk = big()
        if (Ck.isC) {
            val bw = if (bigk) 200 else 180; val bh = if (bigk) 72 else 66
            val row = W.hbox(a)
            if (W.photoBehind) row.background = Panel(0x80060810.toInt(), 0x14FFFFFF, 1f, floatArrayOf(24f, 24f, 24f, 24f), false)
            TABS.forEachIndexed { i, (ic, n) ->
                val on = i == screen
                val b = FrameLayout(a)
                val r = W.hbox(a).apply { gravity = Gravity.CENTER }
                r.addView(Ck.icon(a, "c_$ic", if (on) Color.WHITE else 0x73FFFFFF), lin(if (bigk) 32 else 28, if (bigk) 32 else 28))
                r.addView(t(if (bigk) 25f else 21f, if (on) Color.WHITE else 0x73FFFFFF, true).apply { text = n }, lin(W.WR, W.WR).apply { leftMargin = 10 })
                b.addView(r, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                if (on) b.addView(View(a).apply { setBackgroundColor(accent()) }, FrameLayout.LayoutParams(bw - 68, 2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 6 })
                b.contentDescription = n
                Ck.pressable(b) { if (i == 0) home() else show(i) }
                row.addView(b, lin(bw, bh).apply { if (i > 0) leftMargin = 8 })
            }
            page.addView(row, FrameLayout.LayoutParams(-2, bh, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 8 })
            val vw = if (bigk) 80 else 70; val vh = if (bigk) 70 else 62; val vy = if (bigk) 636 else 640
            volBtn(false, 20, vy, vw, vh, false); volBtn(true, 20 + vw + 6, vy, vw, vh, false)
            volBtn(false, 1260 - 2 * vw - 6, vy, vw, vh, false); volBtn(true, 1260 - vw, vy, vw, vh, false)
        } else {
            val bw = if (bigk) 180 else 150; val bh = if (bigk) 78 else 72
            val row = W.hbox(a)
            TABS.forEachIndexed { i, (ic, n) ->
                val on = i == screen
                val col = W.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
                col.addView(Ck.icon(a, ic, if (on) Color.WHITE else 0x9EFFFFFF.toInt()), lin(if (bigk) 32 else 28, if (bigk) 32 else 28).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 8 })
                col.addView(t(if (bigk) 22f else 18f, if (on) Color.WHITE else 0x9EFFFFFF.toInt(), on, g = Gravity.CENTER).apply { text = n }, lin(W.M, W.WR).apply { topMargin = 4 })
                val f = FrameLayout(a); f.addView(col, FrameLayout.LayoutParams(-1, -1))
                if (on) f.addView(View(a).apply { background = Panel(accent(), 0, 0f, floatArrayOf(2f, 2f, 2f, 2f), false).apply { glow = accent() } }, FrameLayout.LayoutParams(bw - 96, 3, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                f.contentDescription = n
                Ck.pressable(f) { if (i == 0) home() else show(i) }
                row.addView(f, lin(bw, bh).apply { if (i > 0) leftMargin = 10 })
            }
            page.addView(row, FrameLayout.LayoutParams(-2, bh, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 6 })
            val s = if (bigk) 74 else 64; val y = 720 - 16 - s
            volBtn(false, 28, y, s, s, true); volBtn(true, 28 + s + 14, y, s, s, true)
            volBtn(false, 1252 - 2 * s - 14, y, s, s, true); volBtn(true, 1252 - s, y, s, s, true)
        }
    }

    private val TABS = listOf("home" to "Главная", "note" to "Музыка", "img" to "Фон")
    private fun volBtn(up: Boolean, x: Int, y: Int, w: Int, hh: Int, glass: Boolean) {
        val f = FrameLayout(a)
        if (glass) f.background = Panel(0x24FFFFFF, 0x40FFFFFF, 1.2f, floatArrayOf(w / 2f, w / 2f, w / 2f, w / 2f), true)
        else if (W.photoBehind) f.background = Panel(0x73060810, 0x14FFFFFF, 1f, floatArrayOf(16f, 16f, 16f, 16f), false)
        f.addView(Ck.icon(a, if (Ck.isC) (if (up) "c_vp" else "c_vm") else (if (up) "vp" else "vm")), FrameLayout.LayoutParams(if (big()) 36 else 30, if (big()) 36 else 30, Gravity.CENTER))
        f.contentDescription = if (up) "Громче" else "Тише"
        val rep = object : Runnable { override fun run() { volStep(up); h.postDelayed(this, 160) } }
        f.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = .6f; volStep(up); h.postDelayed(rep, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; h.removeCallbacks(rep) }
            }
            true
        }
        page.put(f, x, y, w, hh)
    }

    // ---- volume ----
    private var volView: FrameLayout? = null
    private var volBar: View? = null
    private var volNum: TextView? = null
    private val volHide = Runnable { volView?.animate()?.alpha(0f)?.setDuration(220)?.withEndAction { volView?.visibility = View.GONE }?.start() }
    fun volStep(up: Boolean) {
        val n = K.volStep.coerceIn(1, 5)
        repeat(n) { Volume.step(a, up) }
        if (Syu.level < 0) K.volShown = K.volShown + if (up) n else -n
        h.postDelayed({ volUpdate(true) }, 120)
        volUpdate(true)
    }
    /** level 0..40 that we show: the unit's (com.syu.ms) or Android's scaled, else our own counter */
    fun volLevel(): Int {
        if (Syu.level >= 0) return Syu.level.coerceIn(0, 40)
        val (v, max) = Volume.music(a.getSystemService(android.media.AudioManager::class.java))
        if (v >= 0 && max > 0 && Volume.lastMethod != "syu") return Math.round(v * 40f / max)
        return K.volShown
    }
    fun volUpdate(show: Boolean = false) {
        if (!show && volView?.visibility != View.VISIBLE) return
        if (!K.volOverlay) { if (show) showToast("Громкость: ${volLevel()}"); return }
        val v = volView ?: FrameLayout(a).also { f ->
            f.background = Panel(0xE6101218.toInt(), 0x40FFFFFF, 1.2f, floatArrayOf(30f, 30f, 30f, 30f), true)
            val row = W.hbox(a).apply { setPadding(28, 0, 28, 0) }
            row.addView(Ck.icon(a, "spk"), lin(40, 40))
            val track = FrameLayout(a).apply { background = Panel(0x33FFFFFF, 0, 0f, floatArrayOf(6f, 6f, 6f, 6f), false) }
            val fill = View(a).apply { background = Panel(accent(), 0, 0f, floatArrayOf(6f, 6f, 6f, 6f), false) }
            track.addView(fill, FrameLayout.LayoutParams(0, 12)); volBar = fill
            row.addView(track, lin(360, 12).apply { leftMargin = 22; rightMargin = 22 })
            val num = Ck.text(a, 40f, Color.WHITE, if (Ck.isC) Ck.orb else Ck.sansM, 1, Gravity.CENTER).apply { minWidth = 70 }; volNum = num
            row.addView(num, lin(W.WR, W.WR))
            f.addView(row, FrameLayout.LayoutParams(-1, -1))
            volo.addView(f, FrameLayout.LayoutParams(600, 100, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = 96 })
            volView = f
        }
        val lvl = volLevel()
        volNum?.text = lvl.toString()
        volBar?.layoutParams = (volBar?.layoutParams as? FrameLayout.LayoutParams)?.apply { width = Math.round(360 * lvl / 40f) }
        volBar?.requestLayout()
        if (show) { v.visibility = View.VISIBLE; v.alpha = 1f; h.removeCallbacks(volHide); h.postDelayed(volHide, 1600) }
    }

    // ---- toast ----
    private val toastHide = Runnable { toast.visibility = View.GONE }
    fun showToast(s: String) { toast.text = s; toast.visibility = View.VISIBLE; toast.bringToFront(); h.removeCallbacks(toastHide); h.postDelayed(toastHide, 2600) }

    // ---- low storage strip (ours): dismissable for 3 days, never over the controls, opens «Память и очистка» ----
    fun checkLow() {
        lowStrip?.let { page.removeView(it) }; lowStrip = null
        if (fon || editing || System.currentTimeMillis() < K.lowHideUntil) return
        AppRepo.exec.execute {
            val w = try { Storage.all(a).filter { it.low }.minByOrNull { it.freeFraction } } catch (_: Throwable) { null }
            h.post {
                if (w == null || lowStrip != null) return@post
                val f = W.hbox(a).apply { setPadding(16, 0, 6, 0); background = Panel(0xE6B3261E.toInt(), 0x66FF8A80, 1.2f, floatArrayOf(23f, 23f, 23f, 23f), false) }
                f.addView(t(19f, Color.WHITE, true).apply { text = "⚠ Мало места · ${Math.round(w.freeFraction * 100)} %" }, lin(0, -2, 1f))
                val x = Ck.icon(a, "x").apply { setPadding(10, 10, 10, 10); contentDescription = "Скрыть" }
                Ck.pressable(x) { K.lowHideUntil = System.currentTimeMillis() + 3 * 86_400_000L; page.removeView(f); lowStrip = null; showToast("Скрыто на 3 дня. «Память и очистка» — в настройках") }
                f.addView(x, lin(44, 44))
                f.contentDescription = "Мало места"
                Ck.pressable(f) { overlays.clean() }
                page.put(f, if (screen == 0) 780 else 330, 32, 268, 46); lowStrip = f
            }
        }
    }

    // ================================================================== C «Линза»
    private fun buildC() {
        val music = screen == 1
        val photo = W.photoBehind
        val radio = np.src == "radio"
        val L = LensView(a).apply { home = !music; this.photo = photo; accent = accent(); mode = if (!music) "clock" else if (radio) "radio" else "cover" }
        lens = L; page.put(L, 0, 0, Ck.W, Ck.H)
        val E = EqView(a).apply { home = !music; this.radio = radio; accent = accent(); this.photo = photo }
        eq = E; page.put(E, 0, 0, Ck.W, Ck.H)
        weather(34, 30)
        topBar(music, music)
        val bigk = big()
        val top = if (bigk) 240 else 244; val ph = if (bigk) 168 else 158
        val low = if (bigk) 420 else 418; val lh = if (bigk) 100 else 98
        // ---- speed + mileage (left top) ----
        val sp = FrameLayout(a).apply { background = cPanel(true) }
        if (bigk) {
            val col = W.vbox(a).apply { setPadding(18, 12, 18, 12) }
            val r = W.hbox(a); val s = orb(56f); speedTv = s; r.addView(s); r.addView(t(22f, 0x99FFFFFF.toInt(), true).apply { text = "км/ч" }, lin(-2, -2).apply { leftMargin = 10 })
            col.addView(r)
            col.addView(W.divider(a), lin(W.M, 1).apply { topMargin = 8; bottomMargin = 8 })
            val m = W.hbox(a)
            for ((k, n) in listOf("day" to "За день", "trip" to "Поездка")) {
                val c = W.vbox(a); c.addView(t(20f, 0x9EFFFFFF.toInt(), true).apply { text = n })
                val v = t(26f, Color.WHITE, true); milTv[k] = v; c.addView(v)
                if (k != "day") resettable(c, k)
                m.addView(c, lin(0, -2, 1f))
            }
            col.addView(m)
            sp.addView(col, FrameLayout.LayoutParams(-1, -1))
        } else {
            val row = W.hbox(a).apply { setPadding(16, 14, 16, 14); gravity = Gravity.TOP }
            val l = W.vbox(a)
            l.addView(t(18f, 0x99FFFFFF.toInt(), true).apply { text = "СКОРОСТЬ"; letterSpacing = 0.08f })
            val s = orb(52f); speedTv = s; l.addView(s, lin(-2, -2).apply { topMargin = 10 })
            l.addView(t(18f, 0x99FFFFFF.toInt(), true).apply { text = "км/ч" }, lin(-2, -2).apply { topMargin = 8 })
            row.addView(l, lin(98, -1))
            row.addView(View(a).apply { setBackgroundColor(0x24FFFFFF) }, lin(1, -1).apply { leftMargin = 4; rightMargin = 14 })
            val r = W.vbox(a)
            r.addView(t(18f, 0x99FFFFFF.toInt(), true).apply { text = "ПРОБЕГ, КМ"; letterSpacing = 0.06f })
            for ((k, n) in listOf("day" to "За день", "trip" to "Поездка", "total" to "Всего")) {
                val rr = W.hbox(a)
                rr.addView(t(18f, 0xB3FFFFFF.toInt()).apply { text = n }, lin(0, -2, 1f))
                val v = t(21f, Color.WHITE, true, g = Gravity.END or Gravity.CENTER_VERTICAL); milTv[k] = v; rr.addView(v)
                if (k != "day") resettable(rr, k)
                r.addView(rr, lin(W.M, 36).apply { topMargin = if (k == "day") 6 else 0 })
            }
            row.addView(r, lin(0, -1, 1f))
            sp.addView(row, FrameLayout.LayoutParams(-1, -1))
        }
        page.put(sp, 162, top, 296, ph)
        if (!music) {
            // ---- clock in the ring ----
            val ct = orb(64f, Color.WHITE, Gravity.CENTER).apply { letterSpacing = 0.03f; setShadowLayer(18f, 0f, 0f, 0x33FFFFFF) }
            clockTv = ct; page.put(ct, 640 - 120, 286 - 52, 240, 70)
            val dt = t(20f, 0x99FFFFFF.toInt(), g = Gravity.CENTER); dateTv = dt; page.put(dt, 640 - 120, 286 + 26, 240, 30)
            // ---- now playing (right top) ----
            val np8 = FrameLayout(a).apply { background = cPanel(false) }
            val cv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = OvalOutline }
            npCover = cv; np8.put(cv, 16, 12, 72, 72)
            val ttl = t(21f, Color.WHITE, true, 2); npTitle = ttl; np8.put(ttl, 102, 10, 180, 52)
            val art = t(18f, 0x99FFFFFF.toInt()); npArtist = art; np8.put(art, 102, 64, 180, 24)
            val pr = W.round(a, "c_prev", 60, 28) { cmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val pp = W.round(a, "c_play", 56, 24, true) { cmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }; playBtn = pp; playIcons = "c_play" to "c_pause"
            val nx = W.round(a, "c_next", 60, 28) { cmd(KeyEvent.KEYCODE_MEDIA_NEXT) }
            np8.put(pr, 26, 92, 60, 56); np8.put(pp, 120, 92, 56, 56); np8.put(nx, 210, 92, 60, 56)
            Ck.pressable(np8, { overlays.sourceMenu() }) { show(1) }
            page.put(np8, 822, top, 296, ph)
            tilesC(low, lh)
        } else {
            // ---- sources (left bottom) ----
            val src = W.hbox(a).apply { setPadding(6, 6, 6, 6); background = cPanel(true) }
            for ((k, n, ic) in listOf(Triple("radio", "Радио", "c_radio"), Triple("music", if (bigk) "Яндекс" else "Яндекс", "c_ya"), Triple("phone", "Телефон", "c_bt"))) {
                val b = W.vbox(a).apply { gravity = Gravity.CENTER }
                b.addView(Ck.icon(a, ic), lin(if (bigk) 34 else 30, if (bigk) 34 else 30))
                b.addView(t(if (bigk) 19f else 18f, Color.WHITE, true, g = Gravity.CENTER).apply { text = n }, lin(W.M, -2).apply { topMargin = 6 })
                b.contentDescription = n
                Ck.pressable(b, if (k == "radio") ({ radioChoose(true) }) else null) { pickSource(k) }
                srcBtns[k] = b
                src.addView(b, lin(0, W.M, 1f))
            }
            page.put(src, 162, low, 296, lh)
            // ---- track (right top) ----
            val tk = W.vbox(a).apply { setPadding(16, 14, 16, 14); background = cPanel(false) }
            val cap = t(if (bigk) 20f else 18f, accent(), true); npCap = cap; tk.addView(cap)
            val ttl = t(if (bigk) 30f else 26f, Color.WHITE, true, 2); npTitle = ttl; tk.addView(ttl, lin(W.M, -2).apply { topMargin = 10 })
            val art = t(if (bigk) 21f else 19f, 0x9EFFFFFF.toInt()); npArtist = art; tk.addView(art, lin(W.M, -2).apply { topMargin = 6 })
            Ck.pressable(tk, { overlays.sourceMenu() }) { openPlayer() }
            page.put(tk, 822, top, 296, ph)
            // ---- transport (right bottom) ----
            val tr = FrameLayout(a).apply { background = cPanel(true) }
            val bs = if (bigk) 82 else 72
            val pr = W.round(a, "c_prev", 72, 30) { cmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val pp = W.round(a, "c_play", bs, 28, true) { cmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }; playBtn = pp; playIcons = "c_play" to "c_pause"
            val nx = W.round(a, "c_next", 72, 30) { cmd(KeyEvent.KEYCODE_MEDIA_NEXT) }
            radioLong(pr, false); radioLong(nx, true)
            tr.put(pr, 22, (lh - 64) / 2, 72, 64); tr.put(pp, (296 - bs) / 2, (lh - bs) / 2, bs, bs); tr.put(nx, 296 - 94, (lh - 64) / 2, 72, 64)
            page.put(tr, 822, low, 296, lh)
            if (radio) presets(if (bigk) 4 else 5, if (bigk) 200 else 160, if (bigk) 536 else 540, 64)
            else seekRow(232, if (bigk) 546 else 548, 816, 54)
        }
        bottom()
    }
    private fun cPanel(innerRight: Boolean): Panel {
        val p = Panel.card(22f, W.photoBehind)
        return p
    }

    private fun resettable(v: View, which: String) {
        v.setOnLongClickListener { overlays.resetPopup(which); true }
        v.setOnClickListener { showToast("Удерживайте, чтобы сбросить «${if (which == "trip") "Поездка" else "Всего"}»") }
    }

    private fun tilesC(y: Int, hh: Int) {
        val tiles = CkTiles.load()
        val cap = if (big()) 2 else 4
        tileCap = cap
        val pages = CkTiles.pages(tiles.size, cap); tilePage = tilePage.coerceIn(0, pages - 1); tilePages = pages
        val cur = tiles.drop(tilePage * cap).take(cap)
        val per = if (pages > 1) cap / 2 else maxOf(1, (cur.size + 1) / 2)
        val g = 16; val tw = (296 - (per - 1) * g) / per
        val zx = intArrayOf(162, 822)
        tileTop = y.toFloat(); tileBottom = (y + hh).toFloat()
        tileSlots.clear()
        for (side in 0..1) for (j in 0 until per) if (side * per + j < cur.size) tileSlots.add(zx[side] + j * (tw + g) + tw / 2f)
        for (side in 0..1) for (j in 0 until per) {
            val idx = side * per + j
            val o = cur.getOrNull(idx)
            val x = zx[side] + j * (tw + g)
            if (o == null) { if (side == 1 && pages == 1 && !editing) addTile(x, y, tw, hh); continue }
            tileView(o, tilePage * cap + idx, x, y, tw, hh)
        }
        arrows(124, 1124, y + 14, 32, hh - 28, pages, 592)
    }
    private fun addTile(x: Int, y: Int, w: Int, hh: Int) {
        val f = W.vbox(a).apply { gravity = Gravity.CENTER; background = DashBox(0x4DFFFFFF, if (Ck.isC) 20f else 24f) }
        f.addView(Ck.icon(a, "plus", 0x9EFFFFFF.toInt()), lin(28, 28))
        f.addView(t(18f, 0x9EFFFFFF.toInt(), true, g = Gravity.CENTER).apply { text = "Добавить" }, lin(W.M, -2).apply { topMargin = 6 })
        f.contentDescription = "Добавить плитку"
        Ck.pressable(f) { overlays.tileSheet(-1) }
        page.put(f, x, y, w, hh)
    }
    private fun tileView(o: CkTiles.Tile, index: Int, x: Int, y: Int, w: Int, hh: Int) {
        val f = FrameLayout(a)
        f.background = if (Ck.isC) Panel(if (W.photoBehind) 0x94060810.toInt() else 0x08FFFFFF, 0x42DCE2EB, 1.3f, floatArrayOf(20f, 20f, 20f, 20f), true)
            else Panel(0x24FFFFFF, 0x38FFFFFF, 1.3f, floatArrayOf(24f, 24f, 24f, 24f), true)
        val col = W.vbox(a).apply { gravity = Gravity.CENTER }
        val isz = if (big()) 40 else if (Ck.isC) 34 else 34
        col.addView(Ck.icon(a, o.icon), lin(isz, isz))
        col.addView(t(if (big()) 23f else if (Ck.isC) 19f else 21f, Color.WHITE, Ck.isC, g = Gravity.CENTER).apply {
            text = o.name; setPadding(8, 0, 8, 0)
            // v1.7.2: a long name in a narrow tile («Все приложения» in «Линза») shrinks a little instead of «Все прило…»
            val need = paint.measureText(o.name); val room = w - 24f
            if (need > room) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxOf(14f, textSize * room / need))
        }, lin(W.M, -2).apply { topMargin = 10 })
        f.addView(col, FrameLayout.LayoutParams(-1, -1))
        f.contentDescription = o.name
        if (editing) {
            f.addView(FrameLayout(a).apply {
                background = Panel(0xFFFF453A.toInt(), 0, 0f, floatArrayOf(17f, 17f, 17f, 17f), false)
                addView(Ck.icon(a, "x"), FrameLayout.LayoutParams(20, 20, Gravity.CENTER)); contentDescription = "Убрать"
                Ck.pressable(this) { val l = CkTiles.load().toMutableList(); if (index < l.size) { l.removeAt(index); CkTiles.save(l) }; build() }
            }, FrameLayout.LayoutParams(34, 34, Gravity.END or Gravity.TOP).apply { topMargin = 4; rightMargin = 4 })
            f.rotation = if (index % 2 == 0) -1.2f else 1.2f
            dragToReorder(f, index)
        } else {
            Ck.pressable(f, { editing = true; build() }) { if (o.kind == "radio") radioChoose(false, fromTile = true) else if (!CkTiles.open(a, o)) { if (o.kind == "apps") overlays.allApps() else overlays.settings() } }
        }
        page.put(f, x, y, w, hh)
    }
    /** in edit mode: tap = sheet, drag sideways = move */
    private fun dragToReorder(v: View, index: Int) {
        var sx = 0f; var moved = false; var downX = 0f
        var hoverDir = 0
        // v1.7.2: hold a dragged tile at the left / right edge (over the arrow) → it goes to the previous / next page
        val flip = Runnable { if (hoverDir != 0 && editing) { v.setOnTouchListener(null); moveTileToPage(index, hoverDir) } }
        v.setOnTouchListener { vv, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; sx = vv.translationX; moved = false; vv.elevation = 12f; vv.alpha = .85f; hoverDir = 0 }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX) / root.scale; if (Math.abs(dx) > 12) moved = true; vv.translationX = sx + dx
                    val cx = vv.left + vv.translationX + vv.width / 2f
                    val p = tilePage
                    val d = when { moved && cx < edgeL && p > 0 -> -1; moved && cx > edgeR && p < tilePages - 1 -> 1; else -> 0 }
                    if (d != hoverDir) { AppRepo.main.removeCallbacks(flip); hoverDir = d; if (d != 0) AppRepo.main.postDelayed(flip, FLIP_MS) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    AppRepo.main.removeCallbacks(flip); hoverDir = 0
                    vv.alpha = 1f; vv.elevation = 0f
                    if (!moved) { vv.translationX = 0f; if (e.actionMasked == MotionEvent.ACTION_UP) overlays.tileSheet(index) }
                    else {
                        val cx = (vv.left + vv.translationX + vv.width / 2f)
                        val slots = tileSlots
                        val local = slots.indices.minByOrNull { Math.abs(slots[it] - cx) } ?: (index - tilePage * tileCap)
                        // slots are on the shown page → global position (v1.7.1 used the page-local number: page 2 tiles jumped to page 1 slots)
                        val target = tilePage * tileCap + local
                        val l = CkTiles.load().toMutableList()
                        if (index < l.size) { val t0 = l.removeAt(index); l.add(target.coerceIn(0, l.size), t0); CkTiles.save(l) }
                        build()
                    }
                }
            }
            true
        }
    }
    private val tileSlots = ArrayList<Float>()
    private var tileCap = 5
    private var edgeL = 0f; private var edgeR = Ck.W.toFloat()
    /** the tile at [index] goes to the near end of the previous (−1) / next (+1) page, that page is shown */
    fun moveTileToPage(index: Int, dir: Int): Int {
        val l = CkTiles.load().toMutableList(); if (index !in l.indices) return index
        val p = index / tileCap
        val t0 = l.removeAt(index)
        val to = (if (dir < 0) p * tileCap - 1 else (p + 1) * tileCap).coerceIn(0, l.size)
        l.add(to, t0); CkTiles.save(l)
        tilePage = to / tileCap; K.tilePage = tilePage
        build(); showToast("«${t0.name}» → страница ${tilePage + 1}")
        return to
    }
    /** one position left (−1) / right (+1), across pages too; returns the new index */
    fun moveTile(index: Int, delta: Int): Int {
        val l = CkTiles.load().toMutableList(); if (index !in l.indices) return index
        val to = (index + delta).coerceIn(0, l.size - 1); if (to == index) return index
        java.util.Collections.swap(l, index, to); CkTiles.save(l)
        tilePage = to / tileCap; K.tilePage = tilePage; build()
        return to
    }
    fun tilesPerPage() = tileCap
    fun tilePageForTest(p: Int) { tilePage = p; K.tilePage = p; build() }
    private fun arrows(xl: Int, xr: Int, y: Int, w: Int, hh: Int, pages: Int, dotsY: Int) {
        edgeL = (xl + w + 40).toFloat(); edgeR = (xr - 40).toFloat()
        for (dir in listOf(-1, 1)) {
            val live = pages > 1 && (if (dir < 0) tilePage > 0 else tilePage < pages - 1)
            val f = FrameLayout(a)
            f.background = if (live) Panel(0x1AFFFFFF, accent(), 1.5f, floatArrayOf(14f, 14f, 14f, 14f), false).apply { glow = accent() } else Panel(0x40000000, 0x33FFFFFF, 1f, floatArrayOf(14f, 14f, 14f, 14f), false)
            f.alpha = if (live) 1f else .28f
            f.addView(Ck.icon(a, if (dir < 0) "chL" else "chR"), FrameLayout.LayoutParams(28, 28, Gravity.CENTER))
            f.contentDescription = if (dir < 0) "Предыдущие плитки" else "Следующие плитки"
            if (live) Ck.pressable(f) { pageTiles(dir) } else f.isClickable = true
            page.put(f, if (dir < 0) xl else xr, y, w, hh)
        }
        if (pages > 1) {
            val dots = W.hbox(a).apply { gravity = Gravity.CENTER }
            for (k in 0 until pages) {
                val on = k == tilePage
                dots.addView(View(a).apply { background = Panel(if (on) accent() else 0x52FFFFFF, 0, 0f, floatArrayOf(5f, 5f, 5f, 5f), false); setOnClickListener { tilePage = k; K.tilePage = k; build() } }, lin(if (on) 26 else 10, 10).apply { leftMargin = 5; rightMargin = 5 })
            }
            page.put(dots, 0, dotsY, Ck.W, 12)
        }
        if (editing) editBar()
    }
    private fun pageTiles(d: Int) { tilePage = (tilePage + d).coerceAtLeast(0); K.tilePage = tilePage; build() }

    private fun editBar() {
        val bar = W.hbox(a).apply { setPadding(22, 0, 8, 0); background = if (Ck.isC) Panel(0xF00A0C10.toInt(), 0x4DDCE2EB, 1.2f, floatArrayOf(28f, 28f, 28f, 28f), false) else Panel(0xE62A2238.toInt(), 0x40FFFFFF, 1.2f, floatArrayOf(28f, 28f, 28f, 28f), true) }
        bar.addView(Ck.icon(a, "grid"), lin(26, 26))
        bar.addView(t(21f, Color.WHITE, true).apply { text = "Плитки" }, lin(-2, -2).apply { leftMargin = 10 })
        bar.addView(t(18f, 0xA6FFFFFF.toInt()).apply { text = "× убрать · перетащите · нажмите — настроить" }, lin(0, -2, 1f).apply { leftMargin = 18 })
        bar.addView(W.button(a, "＋ Плитка", 150, 46) { overlays.tileSheet(-1) }, lin(-2, 46).apply { rightMargin = 10 })
        bar.addView(W.button(a, "Готово", 140, 46, primary = true) { editing = false; build() }, lin(-2, 46))
        page.put(bar, 190, 26, 900, 60)
    }

    // ================================================================== D «Стекло»
    private fun buildD() {
        val music = screen == 1
        val bigk = big()
        if (!music) {
            val g = GaugeD(a).apply { accent = accent() }; gauge = g; page.put(g, 130, 142, 240, 240)
            page.put(PodiumView(a).apply { big = bigk; rim = if (K.bg == "cover") accent() else Ck.theme().rim }, 0, 0, Ck.W, Ck.H)
        } else {
            val E = EqView(a).apply { home = false; accent = accent() }; eq = E; page.put(E, 0, 0, Ck.W, Ck.H)
        }
        weather(40, 30, compact = music)
        topBar(music, music)
        if (!music) {
            val ck = t(if (bigk) 90f else 74f, Color.WHITE, g = Gravity.CENTER).apply { typeface = Ck.sansL }; clockTv = ck
            page.put(ck, 400, 18, 480, if (bigk) 96 else 80)
            val dt = t(if (bigk) 23f else 20f, 0x9EFFFFFF.toInt(), g = Gravity.CENTER).apply { letterSpacing = 0.08f }; dateTv = dt
            page.put(dt, 400, if (bigk) 116 else 100, 480, 30)
            val s = t(if (bigk) 80f else 76f, Color.WHITE, g = Gravity.CENTER).apply { typeface = Ck.sansL }; speedTv = s
            page.put(s, 150, 206, 200, 80)
            page.put(t(if (bigk) 24f else 20f, 0x9EFFFFFF.toInt(), g = Gravity.CENTER).apply { text = "км/ч"; letterSpacing = 0.05f }, 150, 288, 200, 28)
            // stats
            val stt = W.hbox(a).apply { background = Panel.card(26f) }
            if (bigk) {
                stt.setPadding(26, 0, 26, 0)
                stt.addView(Ck.icon(a, "gauge", 0x9EFFFFFF.toInt()), lin(30, 30))
                stt.addView(t(24f, 0x9EFFFFFF.toInt()).apply { text = "За день" }, lin(0, -2, 1f).apply { leftMargin = 14 })
                val v = t(32f, Color.WHITE); milTv["day"] = v; stt.addView(v)
                page.put(stt, 40, 356, 400, 88)
            } else {
                listOf(Triple("trip", "Поездка", "trip"), Triple("gauge", "За день", "day"), Triple("pin", "Всего", "total")).forEachIndexed { i, (ic, n, k) ->
                    val c = W.vbox(a).apply { gravity = Gravity.CENTER }
                    val r = W.hbox(a).apply { gravity = Gravity.CENTER }
                    r.addView(Ck.icon(a, ic, 0x9EFFFFFF.toInt()), lin(20, 20)); r.addView(t(18f, 0x9EFFFFFF.toInt()).apply { text = n }, lin(-2, -2).apply { leftMargin = 6 })
                    c.addView(r); val v = t(24f, Color.WHITE, g = Gravity.CENTER); milTv[k] = v; c.addView(v, lin(-2, -2).apply { topMargin = 6 })
                    if (k != "day") resettable(c, k)
                    if (i > 0) stt.addView(View(a).apply { setBackgroundColor(0x24FFFFFF) }, lin(1, 56))
                    stt.addView(c, lin(0, W.M, 1f))
                }
                page.put(stt, 40, 354, 400, 94)
            }
            // now card (v1.7.3: coloured light from the cover around the card)
            val gl = CoverGlow(a); glows.add(gl); page.put(gl, 856 - 120, 144 - 90, 396 + 240, 290 + 180)
            val card = FrameLayout(a).apply { background = Panel.card(26f) }
            val cv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(20f) }
            npCover = cv; card.put(cv, 24, 24, 128, 128)
            val ttl = t(if (bigk) 31f else 25f, Color.WHITE, true, 2); npTitle = ttl; card.put(ttl, 172, 26, 202, if (bigk) 72 else 62)
            val ar = t(if (bigk) 23f else 20f, 0x9EFFFFFF.toInt()); npArtist = ar; card.put(ar, 172, if (bigk) 102 else 92, 202, 28)
            if (!bigk) { val sk = SeekCk(a) { seekTo(it) }; seek = sk; card.put(sk, 166, 124, 214, 34) }
            val pr = W.round(a, "pb_prev", 62, 24) { cmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val pp = W.round(a, "pb_play", 86, 32, true) { cmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }; playBtn = pp; playIcons = "pb_play" to "pb_pause"
            val nx = W.round(a, "pb_next", 62, 24) { cmd(KeyEvent.KEYCODE_MEDIA_NEXT) }
            card.put(pr, 62, 192, 62, 62); card.put(pp, 162, 180, 86, 86); card.put(nx, 286, 192, 62, 62)
            Ck.pressable(card, { overlays.sourceMenu() }) { show(1) }
            page.put(card, 856, 144, 396, 290)
            tilesD()
        } else {
            val cv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(40f); elevation = 18f }
            val gl = CoverGlow(a); glows.add(gl); page.put(gl, 100 - 190, 104 - 170, 366 + 380, 366 + 340)
            npCover = cv; page.put(cv, 100, 104, 366, 366)
            Ck.pressable(cv, { overlays.sourceMenu() }) { openPlayer() }
            // v1.7.4: which app plays (RadioDroid / Яндекс Музыка / Телефон …) — small line above the title
            val cap = t(17f, accent(), true).apply { letterSpacing = 0.06f }; npCap = cap; page.put(cap, 542, 86, 660, 24)
            val ttl = t(if (bigk) 48f else 44f, Color.WHITE, true, 2); npTitle = ttl; page.put(ttl, 540, 110, 670, if (bigk) 106 else 98)
            val ar = t(if (bigk) 26f else 24f, 0x9EFFFFFF.toInt()); npArtist = ar; page.put(ar, 540, if (bigk) 218 else 210, 670, 34)
            val pr = W.round(a, "pb_prev", 104, 36) { cmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val pp = W.round(a, "pb_play", 130, 48, true) { cmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }; playBtn = pp; playIcons = "pb_play" to "pb_pause"
            val nx = W.round(a, "pb_next", 104, 36) { cmd(KeyEvent.KEYCODE_MEDIA_NEXT) }
            radioLong(pr, false); radioLong(nx, true)
            page.put(pr, 574, 262, 104, 104); page.put(pp, 742, 249, 130, 130); page.put(nx, 936, 262, 104, 104)
            val row = W.hbox(a)
            for ((k, n, ic) in listOf(Triple("radio", "Радио", "radio"), Triple("music", "Я.Музыка", "ya"), Triple("phone", "Телефон", "bt"))) {
                val b = W.hbox(a).apply { gravity = Gravity.CENTER; setPadding(18, 0, 22, 0) }
                b.addView(Ck.icon(a, ic, 0xCCFFFFFF.toInt()), lin(24, 24)); b.addView(t(20f, Color.WHITE).apply { text = n }, lin(-2, -2).apply { leftMargin = 10 })
                b.contentDescription = n
                Ck.pressable(b, if (k == "radio") ({ radioChoose(true) }) else null) { pickSource(k) }
                srcBtns[k] = b
                row.addView(b, lin(-2, 54).apply { rightMargin = 12 })
            }
            page.put(row, 560, 404, 640, 54)
            if (np.src == "radio") presets(6, 0, 490, 70) else {
                val bar = FrameLayout(a).apply { background = Panel.card(33f) }
                val tp = t(20f, 0xCCFFFFFF.toInt()); tPos = tp; bar.put(tp, 28, 18, 70, 30)
                val sk = SeekCk(a) { seekTo(it) }; seek = sk; bar.put(sk, 96, 16, 896, 34)
                val td = t(20f, 0xCCFFFFFF.toInt(), g = Gravity.END or Gravity.CENTER_VERTICAL); tDur = td; bar.put(td, 990, 18, 70, 30)
                page.put(bar, 96, 490, 1088, 66)
            }
        }
        bottom()
    }

    private fun tilesD() {
        val tiles = CkTiles.load()
        val bigk = big()
        val cap = if (bigk) 4 else 5; val y = if (bigk) 452 else 462; val hh = if (bigk) 136 else 116
        tileCap = cap
        val L = 104; val Wd = 1072; val g = 16
        val pages = CkTiles.pages(tiles.size, cap); tilePage = tilePage.coerceIn(0, pages - 1); tilePages = pages
        val cur = tiles.drop(tilePage * cap).take(cap)
        val m = if (pages > 1) cap else maxOf(1, cur.size)
        val tw = minOf(if (pages > 1) 999f else 330f, (Wd - (m - 1) * g) / m.toFloat())
        val tot = cur.size * tw + (cur.size - 1).coerceAtLeast(0) * g
        val x0 = L + (Wd - tot) / 2
        tileTop = y.toFloat(); tileBottom = (y + hh).toFloat()
        tileSlots.clear()
        cur.forEachIndexed { k, o -> val x = x0 + k * (tw + g); tileSlots.add(x + tw / 2); tileView(o, tilePage * cap + k, Math.round(x), y, Math.round(tw), hh) }
        if (cur.isEmpty() || editing) { val x = if (cur.isEmpty()) 640 - 165 else -1; if (x > 0) addTile(x, y, 330, hh) }
        arrows(24, 1192, y, 64, hh, pages, y + hh + 10)
    }

    private fun presets(n: Int, w: Int, y: Int, hh: Int) {
        val list = Stations.ALL.take(n)
        val gap = if (Ck.isC) 12 else 10
        val total = if (w > 0) n * w + (n - 1) * gap else 1088
        val bw = if (w > 0) w else (1088 - (n - 1) * gap) / n
        val x0 = if (Ck.isC) 640 - total / 2 else 96
        list.forEachIndexed { i, s ->
            val b = W.vbox(a).apply { gravity = Gravity.CENTER }
            b.addView((if (Ck.isC) orb(22f, g = Gravity.CENTER) else t(22f, Color.WHITE, g = Gravity.CENTER)).apply { text = String.format(Locale.US, "%.1f", s.f) })
            b.addView(t(18f, 0x9EFFFFFF.toInt(), g = Gravity.CENTER).apply { text = s.name }, lin(W.M, -2).apply { topMargin = 2 })
            b.contentDescription = s.name
            // v1.7.2: tap = tune this station right here (the radio app stays closed); hold = store the current station on this chip
            Ck.pressable(b, { storePreset(i) }) { tunePreset(s) }
            presetBtns.add(s to b)
            page.put(b, x0 + i * (bw + gap), y, bw, hh)
        }
    }
    private fun tunePreset(s: Stations.St) {
        val khz = Math.round(s.f * 1000).toInt()
        if (np.src != "radio") Radio.select(a)
        if (FmCtl.tuneTo(khz)) { showToast("${s.name} · ${Presets.mhz(khz)}"); Radio.freq = "FM " + Presets.mhz(khz); updMedia(true) }
        else { showToast("Радио магнитолы не отвечает — открываю его"); Radio.open(a) }
    }
    private fun storePreset(i: Int) {
        val k = FmCtl.khz.takeIf { it > 0 } ?: Stations.mhz(Radio.freq)?.let { Math.round(it * 1000).toInt() }
        if (k == null) { showToast("Сначала включите радио"); return }
        val name = FmCtl.ps ?: Presets.list().firstOrNull { Math.abs(it.khz - k) < 50 }?.name ?: Presets.mhz(k)
        Presets.set(i, Presets.P(k, name)); showToast("Сохранено: $name · ${Presets.mhz(k)}"); build()
    }

    private fun seekRow(x: Int, y: Int, w: Int, hh: Int) {
        val bar = FrameLayout(a).apply { background = Panel(if (W.photoBehind) 0x94060810.toInt() else 0x0DFFFFFF, 0x42DCE2EB, 1.3f, floatArrayOf(hh / 2f, hh / 2f, hh / 2f, hh / 2f), false) }
        val tp = orb(20f); tPos = tp; bar.put(tp, 22, 0, 70, hh)
        val sk = SeekCk(a) { seekTo(it) }; seek = sk; bar.put(sk, 90, (hh - 34) / 2, w - 180, 34)
        val td = orb(20f, g = Gravity.END or Gravity.CENTER_VERTICAL); tDur = td; bar.put(td, w - 92, 0, 70, hh)
        page.put(bar, x, y, w, hh)
    }

    /** radio: tap ⏮ ⏭ = search the next / previous station (FmCtl seek), hold = one step 0.1 MHz */
    private fun radioLong(v: View, next: Boolean) {
        v.setOnLongClickListener {
            if (np.src == "radio") {
                if (FmCtl.step(next)) showToast(if (next) "+0,1 МГц" else "−0,1 МГц")
                else { MediaHub.key(a, if (next) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND); showToast(if (next) "Поиск станции ▶" else "◀ Поиск станции") }
            }
            true
        }
    }

    // ================================================================== Природа
    private fun bgArrow(dir: Int, ph: Int): View {
        val f = W.pill(a, if (dir < 0) "chL" else "chR", null, ph) { bgStep(dir) }
        f.contentDescription = if (dir < 0) "Предыдущий фон" else "Следующий фон"
        return f
    }
    /** v1.7.3: the arrows: next / previous photo, video, theme (persisted) */
    fun bgStep(dir: Int) {
        val name = BgCycle.step(dir, Ck.isC, fon)
        applyBackground(); build(); showToast(name)
    }
    /**
     * v1.7.4 «Фон»: the chosen photo or video full screen, the least possible interface: a small clock, a thin
     * «title — artist» line with ⏮ ⏯ ⏭ (no cover), and — shown on a tap, hidden after a few seconds — photo / video,
     * ◀ ▶, «Выбрать…» and rain / snow.
     */
    private var fonShown = true
    private var fonCtl: View? = null
    private val fonHide = Runnable { fonCtl?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction { fonCtl?.visibility = View.INVISIBLE }?.start(); fonShown = false }
    fun fonTouch() {
        val c = fonCtl ?: return
        h.removeCallbacks(fonHide)
        c.animate().cancel(); c.visibility = View.VISIBLE; c.alpha = 1f; fonShown = true
        h.postDelayed(fonHide, FON_HIDE_MS)
    }
    private fun buildFon() {
        val ct = (if (Ck.isC) orb(34f) else t(34f).apply { typeface = Ck.sansL }).apply { setShadowLayer(10f, 0f, 2f, 0x99000000.toInt()) }; clockTv = ct
        page.addView(ct, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = 40; topMargin = 26 })
        // ---- controls (auto-hide) ----
        val ctl = FrameLayout(a); fonCtl = ctl
        val left = W.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val ph = 46
        left.addView(bgArrow(-1, ph), lin(ph + 6, ph).apply { rightMargin = 8 })
        val kinds = listOf("scene" to "Фото", "video" to "Видео")
        left.addView(W.seg(a, kinds.map { it.second }, if (K.fonKind == "video") 1 else 0, ph, 18f) { i ->
            val k = kinds[i].first
            if (k == "video" && Videos.list.isEmpty()) { showToast("Видео не найдено — выберите папку"); overlays.folderPicker(null); return@seg }
            K.fonKind = k; applyBackground(); build()
        }, lin(W.WR, ph))
        left.addView(bgArrow(1, ph), lin(ph + 6, ph).apply { leftMargin = 8 })
        left.addView(W.pill(a, if (K.fonKind == "video") "folder" else "img", "Выбрать", ph) { if (K.fonKind == "video") overlays.gallery(true) else overlays.gallery() }, lin(W.WR, ph).apply { leftMargin = 12 })
        ctl.put(left, 40, 26, 700, ph)
        val fx = listOf("auto" to "По погоде", "rain" to "Дождь", "snow" to "Снег", "off" to "Выкл")
        val cur = if (!K.particles) "off" else if (K.fxAuto) "auto" else K.fxKind
        val fxRow = W.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        fxRow.addView(Ck.icon(a, Wx.icon(), 0xCCFFFFFF.toInt()), lin(26, 26).apply { rightMargin = 10 })
        fxRow.addView(W.seg(a, fx.map { it.second }, fx.indexOfFirst { it.first == cur }.coerceAtLeast(0), ph, 17f) { i ->
            when (val k = fx[i].first) { "off" -> K.particles = false; "auto" -> { K.particles = true; K.fxAuto = true }; else -> { K.particles = true; K.fxAuto = false; K.fxKind = k } }
            refreshFx(); build()
        }, lin(W.WR, ph))
        ctl.addView(fxRow, FrameLayout.LayoutParams(-2, ph, Gravity.TOP or Gravity.END).apply { topMargin = 26; rightMargin = 170 })
        page.put(ctl, 0, 0, Ck.W, 100)
        // ---- now playing: one thin line, no cover ----
        val bar = W.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 22, 0); background = Panel(0x8C060810.toInt(), 0x1FFFFFFF, 1f, floatArrayOf(30f, 30f, 30f, 30f), false) }
        fun nb(icon: String, s: Int, isz: Int, code: Int): FrameLayout {
            val f = FrameLayout(a); val iv = Ck.icon(a, icon); iv.tag = "icon"; f.addView(iv, FrameLayout.LayoutParams(isz, isz, Gravity.CENTER)); f.contentDescription = icon
            Ck.pressable(f) { cmd(code); fonTouch() }; return f
        }
        bar.addView(nb("pb_prev", 52, 24, KeyEvent.KEYCODE_MEDIA_PREVIOUS), lin(52, 52))
        val pp = nb("pb_play", 56, 28, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); playBtn = pp; playIcons = "pb_play" to "pb_pause"; bar.addView(pp, lin(56, 56))
        bar.addView(nb("pb_next", 52, 24, KeyEvent.KEYCODE_MEDIA_NEXT), lin(52, 52))
        val ttl = t(22f, Color.WHITE, true); npTitle = ttl; bar.addView(ttl, lin(W.WR, W.WR).apply { leftMargin = 14 })
        val ar = t(20f, 0xB3FFFFFF.toInt()); npArtist = ar; bar.addView(ar, lin(W.WR, W.WR).apply { leftMargin = 10 })
        ttl.maxWidth = 520; ar.maxWidth = 300; ttl.ellipsize = android.text.TextUtils.TruncateAt.END; ar.ellipsize = android.text.TextUtils.TruncateAt.END
        Ck.pressable(bar, { overlays.sourceMenu() }) { fonTouch() }
        page.addView(bar, FrameLayout.LayoutParams(-2, 60, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (if (big()) 78 else 72) + 18 })
        bottom()
        if (fonShown) { h.removeCallbacks(fonHide); h.postDelayed(fonHide, FON_HIDE_MS) } else { ctl.visibility = View.INVISIBLE; ctl.alpha = 0f }
    }

    // ================================================================== data → views
    fun updAll() { updClock(); updSpeed(); updMil(); updWx(); updMedia(true) }

    private val fTime = SimpleDateFormat("H:mm", Locale("ru"))
    private val fDateD = SimpleDateFormat("EEEE · d MMMM", Locale("ru"))
    private val fDateC = SimpleDateFormat("EE, d MMMM", Locale("ru"))
    fun updClock() {
        val now = java.util.Date()
        clockTv?.text = fTime.format(now)
        dateTv?.text = if (Ck.isC) fDateC.format(now).replaceFirstChar { it.uppercase() } else fDateD.format(now).uppercase(Locale("ru"))
    }
    fun updSpeed() {
        val s = if (Trip.stale()) null else Trip.speed
        speedTv?.text = s?.toString() ?: "—"
        gauge?.let { it.speed = (s ?: 0).toFloat(); it.invalidate() }
        updMil()
    }
    fun updMil() {
        Trip.checkDay(); Trip.checkTrip()
        milTv["day"]?.text = Trip.km(K.day) + if (Ck.isC) "" else " км"
        milTv["trip"]?.text = Trip.km(K.trip) + if (Ck.isC) "" else " км"
        milTv["total"]?.text = Trip.km(K.total) + if (Ck.isC) "" else " км"
    }
    fun updWx() {
        wxIcon?.setImageResource(Ck.iconRes(a, Wx.icon()))
        wxTemp?.text = Wx.tempText()
        wxPlace?.text = Wx.place()
    }

    private var lastSrc = ""
    fun updMedia(full: Boolean) { try { updMediaInner() } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun updMediaInner() {
        val n = try { Np.now(a) } catch (_: Throwable) { return }
        val srcChanged = n.src != np.src && (n.src == "radio" || np.src == "radio")
        np = n
        Eq.playing = n.playing; for (g in glows) g.playing = n.playing
        if (srcChanged && !editing && !overlays.open) { build(); return }
        run {
            npTitle?.let { if (it.text.toString() != n.title) it.text = n.title }
            npArtist?.let { if (it.text.toString() != n.artist) it.text = n.artist }
            npCap?.text = "● " + when (n.src) { "radio" -> "РАДИО · " + (Radio.freq ?: "FM"); "phone" -> "ТЕЛЕФОН · BT"; "music" -> (n.pkg?.let { Tiles.label(a, it) } ?: "Музыка").uppercase(Locale("ru")); else -> "ИСТОЧНИК НЕ ВЫБРАН" }
            playBtn?.let { W.setIcon(it, if (n.playing) playIcons.second else playIcons.first) }
            for ((k, v) in srcBtns) {
                // v1.7.4: lit = what really plays (RadioDroid lights «Радио», another player lights nothing)
                val on = when (k) { "radio" -> n.src == "radio" || (n.src == "music" && MediaHub.isNetRadio(n.pkg)); "music" -> n.src == "music" && n.pkg == MediaHub.YANDEX; else -> k == n.src }
                if (Ck.isC) { v.background = if (on) Panel(Ck.alpha(accent(), .14f), 0, 0f, floatArrayOf(16f, 16f, 16f, 16f), false) else null; v.alpha = if (on) 1f else .62f }
                else v.background = if (on) Panel(Ck.alpha(accent(), .22f), accent(), 1.6f, floatArrayOf(27f, 27f, 27f, 27f), false).apply { glow = accent() } else Panel(0x1AFFFFFF, 0x33FFFFFF, 1.2f, floatArrayOf(27f, 27f, 27f, 27f), false)
            }
            val cur = Stations.of(Radio.freq)
            for ((s, v) in presetBtns) {
                val on = cur === s
                v.background = if (on) Panel(Ck.alpha(accent(), .2f), accent(), 1.5f, floatArrayOf(18f, 18f, 18f, 18f), false).apply { glow = accent() }
                    else Panel(if (W.photoBehind) 0x94060810.toInt() else 0x12FFFFFF, 0x29FFFFFF, 1f, floatArrayOf(18f, 18f, 18f, 18f), false)
            }
            lens?.let { L ->
                val st = Stations.of(Radio.freq)
                L.station = st?.name ?: "Радио"; L.freq = Stations.mhz(Radio.freq)?.let { String.format(Locale.US, "%.1f", it) } ?: (Radio.freq ?: "FM"); L.freqVal = Stations.mhz(Radio.freq) ?: 0.0
                if (st != null) L.stColors = intArrayOf(st.c0, st.c1)
            }
        }
        // progress
        if (n.dur > 0 && n.pos >= 0) {
            val f = (n.pos.toFloat() / n.dur).coerceIn(0f, 1f)
            seek?.fraction = f; eq?.progress = f
            tPos?.text = fmt(n.pos); tDur?.text = fmt(n.dur)
        } else { seek?.fraction = 0f; eq?.progress = -1f; tPos?.text = "—"; tDur?.text = "—" }
        eq?.let { if (it.live()) it.kick() else it.invalidate() }
        // cover art
        val key = n.key
        if (key != artKey) {
            artKey = key
            val md = n.md
            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) setArt(bmp)
            else {
                val uri = md?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                setArt(null)
                if (uri != null) AppRepo.exec.execute { val b = try { ArtLoader.fromUri(a.contentResolver, uri, 512) } catch (_: Throwable) { null }; h.post { if (artKey == key && b != null) setArt(b) } }
            }
        } else if (npCover?.drawable == null) showArt()
    }
    private fun setArt(b: Bitmap?) {
        art = b?.let { try { ArtLoader.scale(it, 512) } catch (_: Throwable) { it } }
        val col = art?.let { try { CoverColor.pick(it) } catch (_: Throwable) { null } } ?: 0
        val prev = Ck.coverColor
        val prevArt = Ck.coverArt
        Ck.coverColor = col; Ck.coverArt = art
        showArt()
        if (K.bg == "cover" && (col != prev || prevArt !== art)) { h.removeCallbacks(coverRebuild); h.postDelayed(coverRebuild, 400) }
    }
    private val coverRebuild = Runnable { if (!editing && !overlays.open) { bg.refresh(); build() } else bg.refresh() }
    private fun showArt() {
        val b = art
        npCover?.let { iv ->
            if (b != null) { iv.setImageBitmap(b); iv.background = null }
            else {
                iv.setImageDrawable(null)
                val st = if (np.src == "radio") Stations.of(Radio.freq) else null
                iv.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    if (st != null) intArrayOf(st.c0, st.c1) else intArrayOf(Ck.mix(accent(), Color.BLACK, .35f), Ck.mix(accent(), Color.BLACK, .8f)))
            }
        }
        lens?.let { it.cover = b; it.invalidate() }
        for (g in glows) { g.set(b); g.playing = np.playing }
    }
    private val glows = ArrayList<CoverGlow>()
    /** after «Сила подсветки» changed */
    fun refreshGlow() { refreshGlowLive(); if (K.bg == "cover") bg.refresh() }
    fun refreshGlowLive() { for (g in glows) g.invalidate(); lens?.invalidate() }
    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}" }

    // ================================================================== actions
    fun cmd(code: Int) {
        if (np.src == "none" && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY)) { MediaHub.resume(a); h.postDelayed({ updMedia(true) }, 600); return }
        MediaHub.command(a, code)
        h.postDelayed({ updMedia(true) }, 350)
    }
    private fun seekTo(f: Float) {
        val mc = MediaHub.current ?: return
        if (np.dur > 0) try { mc.transportControls.seekTo((np.dur * f).toLong()) } catch (_: Throwable) {}
    }
    /** a player by package: its session → play (stay here); no session yet → open it, press Play when it is up */
    private fun playApp(p: String) {
        val s = MediaHub.all.firstOrNull { it.packageName == p }
        if (s != null) { MediaHub.pin(p); try { s.transportControls.play() } catch (_: Throwable) {}; showToast(Tiles.label(a, p)) }
        else { AppRepo.launch(a, p); MediaHub.playWhenReady(a, p) }
    }
    /**
     * v1.7.4 «Радио»: FM of the unit (works without internet) or internet radio (RadioDroid, only if installed).
     * Asks unless a choice was remembered; [ask] = always ask (long press).
     */
    fun radioChoose(ask: Boolean, fromTile: Boolean = false) {
        val net = Tiles.installed(a, MediaHub.RADIODROID)
        val c = if (!net) "fm" else if (ask) "ask" else K.radioChoice
        when (c) {
            "fm" -> { pickSource("fm"); if (fromTile) show(1) }
            "net" -> radioNet(fromTile)
            else -> overlays.radioChooser(fromTile)
        }
    }
    fun radioNet(fromTile: Boolean) { if (Radio.muted && Syu.vol(-4)) Radio.muted = false; playApp(MediaHub.RADIODROID); if (fromTile) show(1) }
    /** source switch (Радио / Я.Музыка / Телефон) */
    fun pickSource(k: String) {
        if (k != "radio" && Radio.muted) { if (Syu.vol(-4)) Radio.muted = false }
        when (k) {
            "radio" -> radioChoose(false)
            "fm" -> {
                // v1.7.2: just select the radio (tuner becomes the source), stay on our screen
                if (Radio.select(a)) showToast("Радио" + (Radio.freq?.let { " · $it" } ?: ""))
                else {
                    val p = MediaHub.RADIO_PKGS.firstOrNull { a.packageManager.getLaunchIntentForPackage(it) != null }
                    if (p != null) { MediaHub.pinRadio(p); AppRepo.launch(a, p) } else showToast("Радио магнитолы не найдено")
                }
            }
            "music" -> {
                // v1.7.4: «Я.Музыка» = Яндекс Музыка by its package, never «the last player» (RadioDroid took its place in 1.7.3)
                if (Tiles.installed(a, MediaHub.YANDEX)) playApp(MediaHub.YANDEX)
                else {
                    val p = Nav.musicApp(a, null)?.takeIf { !MediaHub.isNetRadio(it) }
                    if (p != null) playApp(p) else showToast("Яндекс Музыка не установлена")
                }
            }
            "phone" -> {
                // v1.7.3: play the phone's sound without opening anything
                when (BtAudio.select(a)) {
                    BtAudio.Result.OK -> showToast("Телефон · Bluetooth" + (BtMeta.title?.let { " · $it" } ?: ""))
                    BtAudio.Result.OPENED -> showToast("Магнитола не переключила звук сама — открыл Bluetooth-музыку")
                    BtAudio.Result.NONE -> showToast("Bluetooth-музыка не найдена. Подключите телефон по Bluetooth")
                }
            }
        }
        h.postDelayed({ updMedia(true) }, 500)
    }
    fun openPlayer() {
        when (np.src) {
            "radio" -> Radio.open(a)
            "phone" -> CamApps.btMusic(a)?.let { AppRepo.launch(a, it) }
            else -> np.pkg?.let { AppRepo.launch(a, it) } ?: overlays.sourceMenu()
        }
    }
    // last: every property above is initialised by now
    init { setup() }
}

/** rounded outline for covers */
class RoundOutline(private val r: Float) : android.view.ViewOutlineProvider() {
    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
}
object OvalOutline : android.view.ViewOutlineProvider() {
    override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
}
/** dashed «＋ Добавить» box */
class DashBox(private val color: Int, private val r: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    override fun draw(c: android.graphics.Canvas) { p.color = color; val b = android.graphics.RectF(bounds); b.inset(1f, 1f); c.drawRoundRect(b, r, r, p) }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
