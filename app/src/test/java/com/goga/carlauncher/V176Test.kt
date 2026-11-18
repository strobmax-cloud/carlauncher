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

/** v1.7.6: with the system bar hidden the stage uses the whole 1280×720 window — the top row hugs the real top edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V176Test {
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
        Wx.temp = -5.0; Wx.code = 0; Wx.at = System.currentTimeMillis()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View, w: Int = 1280, h: Int = 720) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-176-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** on-screen rect (window px) through the stage scale */
    private fun rect(v: View): Rect { val p = IntArray(2); v.getLocationInWindow(p); var s = 1f; var x: View? = v; while (x != null) { s *= x.scaleX; x = x.parent as? View }
        return Rect(p[0], p[1], p[0] + Math.round(v.width * s), p[1] + Math.round(v.height * s)) }
    private fun open(style: String, screen: String, big: Boolean = false): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        return a to root
    }

    private val stale = android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, 70, 0, 0)).build()

    @Test fun hiddenBarUsesTheWholeScreen() {
        for (st in listOf("C", "D")) for (sc in listOf("home", "music", "fon")) for (big in listOf(false, true)) {
            K.hideBar = true
            val (a, root) = open(st, sc, big)
            a.applyBars(); settle()
            // the firmware / stable insets still report the bar's 70 px although it is hidden
            a.ck.root.onApplyWindowInsets(stale); settle(); relayout(root); settle(); relayout(root); settle()
            val tag = "$st $sc ${if (big) "big" else "normal"}"
            assertEquals("$tag topInset", 0, a.ck.root.topInset)
            assertEquals("$tag scale", 1f, a.ck.root.scale, 0.001f)
            val pg = rect(a.ck.page)
            assertTrue("$tag page fills the window: $pg", pg.left == 0 && pg.top == 0 && pg.right == 1280 && pg.bottom == 720)
            // the top row (weather, ‹ Фон › ⚙, clock …) starts within 40 px of the real top edge
            val top = all(a.ck.page).filter { it.isShown && it.width in 20..700 && it.height in 16..120 && (it is TextView || it.contentDescription != null) }
                .map { rect(it) }.filter { it.top < 160 }
            assertTrue("$tag top row exists", top.isNotEmpty())
            assertTrue("$tag top row hugs the top edge: ${top.minOf { it.top }}", top.minOf { it.top } <= 40)
            if (!big) shot(root, "hidden-$st-$sc") else shot(root, "hidden-$st-$sc-big")
            // the bar comes back (setting off) → the stage leaves room for it again
            K.hideBar = false; a.applyBars(); settle(); a.ck.root.onApplyWindowInsets(stale); settle(); relayout(root); settle()
            assertEquals("$tag bar shown again", 70, a.ck.root.topInset)
            assertTrue("$tag content below the bar", rect(a.ck.page).top >= 70)
            a.finish()
        }
    }
}
