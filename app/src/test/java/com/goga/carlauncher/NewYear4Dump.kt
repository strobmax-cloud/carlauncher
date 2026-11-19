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

/** design phase v4 (not shipped): base renders + layout dump. Run: gradle testDebugUnitTest --tests "*NewYear4Dump" */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class NewYear4Dump {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() { NY4Env.fresh(ctx) }
    @Test fun dump() {
        val out = File("/workspace/car-design/png-newyear-v4/layout")
        for ((st, sc) in NY4Env.SCREENS) {
            val (a, root) = NY4Env.open(st, sc, false)
            val s = Festive.scan(a.ck.page)
            val sb = StringBuilder()
            fun r(x: android.graphics.RectF) = "${x.left.toInt()},${x.top.toInt()},${x.right.toInt()},${x.bottom.toInt()}"
            for ((c, rad) in s.cards) sb.append("card ${r(c)} $rad\n")
            for (b in s.blocks) sb.append("block ${r(b)}\n")
            for (c in s.controls) sb.append("ctrl ${r(c)}\n")
            for (v in NY4Env.all(a.ck.page)) if (v.isShown && v.width > 200 && v.height > 200 && v !is ViewGroup) sb.append("bigview ${v.javaClass.simpleName} ${r(NY4Env.rect(a.ck.page, v))}\n")
            File(out, "$st-$sc.txt").writeText(sb.toString())
            NY4Env.shot(root, File(out, "base-$st-$sc.png"))
            a.finish()
        }
    }
}

/** v4 mockups: 2 variants × C/D × Главная / Музыка / Фон → /workspace/car-design/png-newyear-v4 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class NewYear4Mock {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() { NY4Env.fresh(ctx) }
    @Test fun mockups() {
        val out = File("/workspace/car-design/png-newyear-v4b").apply { mkdirs() }
        for (v in NY4.V.values()) for ((st, sc) in NY4Env.SCREENS) {
            val (a, root) = NY4Env.open(st, sc, false)
            NewYear4.attach(a, v, "$st-$sc"); NY4Env.settle(); NY4Env.relayout(root); NY4Env.settle()
            NY4Env.shot(root, File(out, "${v.name.lowercase()}-$st-$sc.png"))
            a.finish()
        }
    }
}

object NY4Env {
    val SCREENS = listOf("C" to "home", "C" to "music", "C" to "fon", "D" to "home", "D" to "music", "D" to "fon")
    fun fresh(ctx: android.content.Context) {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null; Radio.muted = false
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true; FmCtl.reset(); Ck.coverArt = null; Ck.coverColor = 0
        Wx.temp = -12.0; Wx.code = 73; Wx.at = System.currentTimeMillis()
        // the unit has «Car Launcher — кнопки навигации» on → □ «Недавние» is shown in the ◁ ○ □ pill
        android.provider.Settings.Secure.putString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ctx.packageName + "/" + NavAccess::class.java.name)
        K.nyMode = "off"; K.nyFrost = true; K.particles = true; K.fxAuto = false; K.fxKind = "snow"
    }
    fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    fun rect(page: ViewGroup, v: View): android.graphics.RectF { val r = android.graphics.Rect(0, 0, v.width, v.height); page.offsetDescendantRectToMyCoords(v, r); return android.graphics.RectF(r) }
    fun shot(v: View, f: File) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun cover() = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
    fun open(style: String, screen: String, big: Boolean): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (screen == "fon") K.fonKind = "scene"
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Jingle Bell Rock"; BtMeta.artist = "Bobby Helms"; BtMeta.playAt = 0
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle()
        try { Cockpit::class.java.getDeclaredMethod("setArt", Bitmap::class.java).apply { isAccessible = true }.invoke(a.ck, cover()) } catch (_: Throwable) {}
        settle(6); relayout(root); settle()
        return a to root
    }
}
