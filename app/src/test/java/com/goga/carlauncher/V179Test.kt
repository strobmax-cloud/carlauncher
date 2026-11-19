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

/** v1.7.9: festive = design v4b Minimal; D «Стекло» speedometer no longer cut at its edges */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V179Test {
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
    private val stale = android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, 70, 0, 0)).build()
    private fun open(style: String, screen: String, big: Boolean = false): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (screen == "fon") K.fonKind = "scene"
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Jingle Bell Rock"; BtMeta.artist = "Bobby Helms"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        return a to root
    }

    /** the dial ring (full scale, with glow) is drawn completely inside its view: nothing touches the view edge */
    @Test fun speedometerRingIsNotClipped() {
        for (big in listOf(false, true)) for (bar in listOf(false, true)) {
            K.hideBar = !bar
            val (a, root) = open("D", "home", big)
            a.applyBars(); settle(); a.ck.root.onApplyWindowInsets(stale); settle(); relayout(root); settle()
            val tag = "${if (big) "big" else "normal"}${if (bar) "-bar" else ""}"
            val g = all(root).filterIsInstance<GaugeD>().single()
            assertTrue("$tag laid out", g.width > 0 && g.width == g.height)
            g.speed = 160f
            val b = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888); g.draw(Canvas(b))
            var edge = 0
            for (i in 0 until g.width) for (e in listOf(0, 1, g.height - 2, g.height - 1)) {
                if (android.graphics.Color.alpha(b.getPixel(i, e)) > 8) edge++
                if (android.graphics.Color.alpha(b.getPixel(e.coerceAtMost(g.width - 1), i.coerceAtMost(g.height - 1))) > 8) edge++
            }
            assertEquals("$tag ring pixels at the view edge (= cut off)", 0, edge)
            // the ring itself is still there and keeps its designed size: outer radius ≈ 120 + 7 design px
            val cy = g.height / 2; var left = -1
            for (x in 0 until g.width) if (android.graphics.Color.alpha(b.getPixel(x, cy)) > 120) { left = x; break }
            val k = g.width / (240f + 2 * GaugeD.M)
            assertTrue("$tag ring at the designed radius (left=$left)", Math.abs((g.width / 2f - left) / k - 127f) < 4f)
            // the parent never clips it either: the view lies inside the page
            val r = Rect(0, 0, g.width, g.height); a.ck.page.offsetDescendantRectToMyCoords(g, r)
            assertTrue("$tag inside the page $r", r.left >= 0 && r.top >= 0 && r.right <= a.ck.page.width && r.bottom <= a.ck.page.height)
            a.finish()
        }
    }

    /** C «Линза» speed / mileage card: every text fits its view (normal / «Крупный», bar hidden / shown) */
    @Test fun lensSpeedCardTextFits() {
        for (big in listOf(false, true)) for (bar in listOf(false, true)) {
            K.hideBar = !bar
            val (a, root) = open("C", "home", big)
            a.applyBars(); settle(); a.ck.root.onApplyWindowInsets(stale); settle(); relayout(root); settle()
            val tv = all(a.ck.page).filterIsInstance<TextView>().filter { it.isShown && it.text.toString() in setOf("СКОРОСТЬ", "ПРОБЕГ, КМ", "км/ч", "За день", "Поездка", "Всего") }
            assertTrue("found the card", tv.size >= 3)
            for (t in tv) {
                val inner = t.width - t.totalPaddingLeft - t.totalPaddingRight
                assertTrue("«${t.text}» width ${t.layout.getLineWidth(0)} > $inner", t.layout.getLineWidth(0) <= inner + 1)
                assertTrue("«${t.text}» height ${t.layout.height} > ${t.height}", t.layout.height <= t.height - t.totalPaddingTop - t.totalPaddingBottom + 1)
            }
            a.finish()
        }
    }

    /** strands, LEDs, frost and drift live under the page; only the frost lines are above it */
    @Test fun festiveLayersSitBehindTheInterface() {
        K.nyMode = "on"
        val (a, _) = open("D", "home")
        val st = a.ck.root.stage; val ny = a.ck.ny
        val ip = st.indexOfChild(a.ck.page)
        assertTrue(st.indexOfChild(ny.back) < ip && st.indexOfChild(ny.lights) < ip && st.indexOfChild(ny.front) > ip)
        a.finish()
    }

    @Test fun branchesAreGoneFromTheApk() {
        val names = ctx.assets.list("newyear")!!.toList()
        assertEquals(listOf("ny_frost.webp"), names)
        assertFalse(File("src/main/java/com/goga/carlauncher/CkPrefs.kt").readText().contains("nyFir"))
    }
}
