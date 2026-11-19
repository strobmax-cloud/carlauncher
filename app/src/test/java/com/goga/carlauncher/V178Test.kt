package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** v1.7.8: «Праздничный режим» (approved New Year design v3) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V178Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.title = null; BtMeta.artist = null; BtMeta.playAt = 0
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null; Radio.muted = false
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true; FmCtl.reset(); Ck.coverArt = null; Ck.coverColor = 0
        Wx.temp = -12.0; Wx.code = 73; Wx.at = System.currentTimeMillis()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View, w: Int = 1280, h: Int = 720) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-178-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun cal(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }
    private fun open(style: String, screen: String, big: Boolean = false): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (screen == "fon") K.fonKind = "scene"
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Jingle Bell Rock"; BtMeta.artist = "Bobby Helms"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        return a to root
    }
    private val stale = android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, 70, 0, 0)).build()

    @Test fun autoDatesAcrossTheYearBoundary() {
        assertEquals("default is Авто", "auto", K.nyMode)
        val on = listOf(cal(2026, 12, 20), cal(2026, 12, 24), cal(2026, 12, 31), cal(2027, 1, 1), cal(2027, 1, 7), cal(2027, 1, 14), cal(2028, 12, 20))
        val off = listOf(cal(2026, 12, 19), cal(2027, 1, 15), cal(2027, 2, 1), cal(2027, 6, 15), cal(2026, 11, 19), cal(2026, 12, 1))
        for (c in on) assertTrue("on ${c.time}", Festive.on(c))
        for (c in off) assertFalse("off ${c.time}", Festive.on(c))
        // leap year / end of month edges
        assertTrue(Festive.inSeason(1, 14)); assertFalse(Festive.inSeason(1, 15)); assertFalse(Festive.inSeason(12, 19)); assertTrue(Festive.inSeason(12, 20))
        assertFalse(Festive.on(cal(2028, 2, 29)))
        K.nyMode = "on"; assertTrue(Festive.on(cal(2027, 7, 1))); assertEquals("Включено вручную", Festive.status(cal(2027, 7, 1)))
        K.nyMode = "off"; assertFalse(Festive.on(cal(2026, 12, 31))); assertEquals("Выключено", Festive.status())
        K.nyMode = "auto"
        assertTrue(Festive.status(cal(2027, 1, 3)).contains("после 14 января"))
        assertTrue(Festive.status(cal(2026, 11, 19)).contains("20 декабря"))
        K.nyMode = "bogus"; assertEquals("auto", K.nyMode)
    }

    @Test fun twinkleIsSmooth() {
        val g = Festive.Glow(0f, 0f, 26f, Festive.WARM[0], .9f, 1.3f)
        val l = NyLights(ctx)
        // no twinkle → steady; with twinkle → bounded, continuous (no jumps between 50 ms frames)
        for (t in 0..200) assertEquals(.9f, l.level(g, t * .05f, 0), 1e-4f)
        var prev = l.level(g, 0f, 100)
        for (t in 1..400) { val v = l.level(g, t * .05f, 100); assertTrue(v in 0f..1f); assertTrue("jump at $t", Math.abs(v - prev) < .25f); prev = v }
    }

    /** every style × screen × size, bar hidden and shown: nothing festive covers a letter, icon or control */
    @Test fun decorationsNeverCoverTheInterface() {
        K.nyMode = "on"
        for (st in listOf("C", "D")) for (sc in listOf("home", "music", "fon")) for (big in listOf(false, true)) for (bar in listOf(false, true)) {
            K.hideBar = !bar
            val (a, root) = open(st, sc, big)
            a.applyBars(); settle()
            a.ck.root.onApplyWindowInsets(stale); settle(); relayout(root); settle(); relayout(root); settle()
            val tag = "$st-$sc${if (big) "-big" else ""}${if (bar) "-bar" else ""}"
            val ny = a.ck.ny
            assertTrue("$tag enabled", ny.enabled)
            val plan = ny.plan!!
            val stg = a.ck.root.stage
            assertEquals("$tag layer bleeds to the window edges", Ck.W + stg.bl + stg.br, ny.front.width)
            assertEquals("$tag back layer too", Ck.H + stg.bt + stg.bb, ny.back.height)
            if (bar) assertTrue("$tag bar shown → side bleed", stg.bl > 0) else assertEquals("$tag no bleed", 0, stg.bl)
            assertTrue("$tag garlands", plan.bulbs.size >= 4)
            assertTrue("$tag drift", plan.snow)
            assertTrue("$tag frost", plan.frost)
            if (sc == "fon") assertTrue("$tag curtains", plan.curtains.size == 4)
            // v1.7.9: the strand is ONE continuous line across the whole width (never cut) and no LED hangs over free text
            val st0 = plan.strands.single()
            assertTrue("$tag strand spans the width", st0.pts.first().x <= -stg.bl && st0.pts.last().x >= Ck.W + stg.br)
            for (i in 1 until st0.pts.size) assertTrue("$tag continuous", Math.abs(st0.pts[i].x - st0.pts[i - 1].x) < 60f)
            val free = Festive.freeText(Festive.scan(a.ck.page))
            for (b in st0.bulbs) if (!b.dim) assertTrue("$tag LED over text at (${b.x},${b.y})", free.none { it.contains(b.x, b.y) })
            val fb = ny.front.bmp!!
            assertEquals(plan.vw, fb.width)
            // pixel check of the layer above the interface over every text / icon / small control
            val sceneNow = Festive.scan(a.ck.page)
            var bad = 0; val where = StringBuilder()
            for (r in sceneNow.blocks) {
                var x = r.left.toInt() + 1
                while (x < r.right.toInt() - 1) {
                    var y = r.top.toInt() + 1
                    while (y < r.bottom.toInt() - 1) {
                        val px = x + plan.ox; val py = y + plan.oy
                        if (px in 0 until fb.width && py in 0 until fb.height && android.graphics.Color.alpha(fb.getPixel(px, py)) > 90) { bad++; if (where.length < 300) where.append("($x,$y in $r) ") }
                        y += 2
                    }
                    x += 2
                }
            }
            assertEquals("$tag festive pixels over the interface: $where", 0, bad)
            if (!bar) shot(root, tag)
            a.finish()
        }
    }

    @Test fun togglesAndSettings() {
        K.nyMode = "on"
        val (a, root) = open("D", "home")
        val ny = a.ck.ny
        assertTrue(ny.plan!!.frost && ny.plan!!.frostLines.isNotEmpty() && ny.plan!!.snow)
        assertEquals("snow falls in festive mode", "snow", Cockpit.fxKind(false))
        K.nyFrost = false; ny.apply(); settle()
        assertTrue("no frost", !ny.plan!!.frost && ny.plan!!.frostLines.isEmpty())
        K.nySnow = false; ny.apply(); settle()
        assertFalse("no drift", ny.plan!!.snow); assertTrue(ny.plan!!.shadows.isEmpty())
        assertEquals("none", Cockpit.fxKind(false))
        K.nySnow = true; K.nyFrost = true; K.nyLights = "multi"; ny.apply(); settle()
        assertTrue("multicolour", ny.plan!!.bulbs.map { it.color }.toSet().size >= 4)
        K.nyMode = "off"; ny.apply(); settle()
        assertEquals(View.GONE, ny.front.visibility); assertEquals(View.GONE, ny.lights.visibility); assertTrue(ny.front.bmp == null)
        K.nyMode = "on"; K.nyLights = "warm"; ny.apply(); settle()
        a.ck.overlays.settings("look"); settle(); relayout(root); settle()
        val tx = texts(root)
        for (s in listOf("Праздничный режим", "Мерцание гирлянды", "Огоньки", "Тёплые", "Разноцветные", "Снег и сугроб", "Иней по краям", "Авто · 20 дек – 14 янв", "Сейчас"))
            assertTrue("settings: $s", tx.contains(s))
        assertFalse("branches setting removed", tx.contains("Еловые ветки"))
        assertTrue(texts(root).contains("Вернуть штатный экран") || run { a.ck.overlays.settings("about"); settle(); relayout(root); settle(); texts(root).any { it.contains("штатный") } })
        a.ck.overlays.settings("look"); settle(); relayout(root); settle()
        shot(root, "settings")
        a.finish()
    }

    @Test fun animationPausesWhenNotInFront() {
        K.nyMode = "on"
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get(); val root = a.window.decorView
        settle(); relayout(root); settle()
        val l = a.ck.ny.lights
        assertTrue("glows", l.glows.isNotEmpty())
        assertTrue("runs while in front", l.running)
        c.pause(); settle()
        assertFalse("paused with the launcher", l.running)
        c.resume(); settle(); assertTrue(l.running)
        K.nyTwinkle = 0; l.update(); assertFalse("steady lights need no frames", l.running)
        K.nyTwinkle = 45; l.update(); assertTrue(l.running)
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(pm).setIsInteractive(false); l.update(); assertFalse("screen off", l.running)
        shadowOf(pm).setIsInteractive(true); l.update(); assertTrue(l.running)
        K.nyMode = "off"; a.ck.ny.apply(); settle(); assertFalse(l.running)
        c.pause().stop().destroy()
    }

    @Test fun releaseHasNoForbiddenHooks() {
        // the festive views never take touches or keys
        K.nyMode = "on"
        val (a, _) = open("D", "home")
        for (v in listOf(a.ck.ny.back, a.ck.ny.front, a.ck.ny.lights)) { assertFalse(v.isClickable); assertFalse(v.isFocusable) }
        for (m in listOf("onKeyDown", "onKeyUp", "dispatchKeyEvent", "onKeyLongPress"))
            assertFalse(m, MainActivity::class.java.declaredMethods.any { it.name == m })
        a.finish()
    }

    /** v1.7.8: macOS «._name» AppleDouble files, hidden and tiny files never appear as videos (picker, arrows) */
    @Test fun usbVideoPickerSkipsAppleDoubleAndTinyFiles() {
        val dir = File(ctx.cacheDir, "usb178/Backgrounds").apply { deleteRecursively(); mkdirs() }
        fun mk(n: String, size: Int) = File(dir, n).apply { writeBytes(ByteArray(size)) }
        mk("._nature ocean sunset 03.mp4", 4096); mk(".hidden.mp4", 300_000); mk("tiny.mp4", 50_000); mk("empty.mp4", 0)
        mk("nature ocean sunset 03.mp4", 300_000); mk("winter forest.mp4", 400_000)
        File(dir, "._sub").mkdirs()
        val names = Videos.videoFiles(dir).map { it.name }
        assertEquals(listOf("nature ocean sunset 03.mp4", "winter forest.mp4"), names)
        assertEquals(names.toSet(), Videos.videosIn(dir).map { it.name }.toSet())
        K.videoDir = dir.path; Videos.scan(ctx)
        assertTrue(Videos.list.none { it.file.name.startsWith(".") || it.file.length() < Videos.MIN_BYTES })
        assertEquals(2, Videos.list.size)
        // a «._» file remembered by an older version is not used
        K.video = File(dir, "._nature ocean sunset 03.mp4").path
        assertFalse(Videos.chosen()!!.name.startsWith("."))
        // the arrows cycle only through real clips
        K.bg = "video"; val seen = HashSet<String>()
        repeat(4) { BgCycle.step(1, false, false); seen.add(Videos.chosen()!!.name) }
        assertTrue(seen.none { it.startsWith(".") || it == "tiny.mp4" || it == "empty.mp4" })
    }
}
