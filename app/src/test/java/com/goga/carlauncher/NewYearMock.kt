package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

/** v1.8 design phase: New Year mockups (not shipped — test sources only). Run: gradle testDebugUnitTest --tests "*NewYearMock" -Pmock */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class NewYearMock {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Jingle Bell Rock"; BtMeta.artist = "Bobby Helms"; BtMeta.playAt = 0
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null; Radio.muted = false
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true; FmCtl.reset(); Ck.coverArt = null; Ck.coverColor = 0
        Wx.temp = -12.0; Wx.code = 73; Wx.at = System.currentTimeMillis()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private val out = File("/workspace/car-design/png-newyear").apply { mkdirs() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun cover() = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
    private fun open(style: String, screen: String): Pair<MainActivity, View> {
        K.style = style; K.startScreen = screen; K.particles = true; K.fxAuto = false; K.fxKind = "snow"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle()
        Cockpit::class.java.getDeclaredMethod("setArt", Bitmap::class.java).apply { isAccessible = true }.invoke(a.ck, cover())
        settle(6); relayout(root); settle()
        return a to root
    }
    private fun dump(a: MainActivity, name: String) {
        val sb = StringBuilder()
        for (v in all(a.ck.page)) if (v.isShown && v.width >= 40 && v !== a.ck.page) {
            val p = IntArray(2); v.getLocationInWindow(p)
            sb.append("${v.javaClass.simpleName} ${v.contentDescription ?: (v as? android.widget.TextView)?.text ?: ""} ${p[0]},${p[1]} ${v.width}x${v.height}\n")
        }
        File(out, "$name.layout.txt").writeText(sb.toString())
    }

    @Test fun mockups() {
        val festive = System.getProperty("festive", "1") == "1"
        for ((st, sc, name) in listOf(Triple("C", "home", "C-home"), Triple("C", "music", "C-music"), Triple("D", "home", "D-home"), Triple("D", "music", "D-music"), Triple("D", "fon", "D-fon"))) {
            if (sc == "fon") { K.fonKind = "scene" }
            val (a, root) = open(st, sc)
            dump(a, name)
            if (festive) { NewYear.attach(a, name); settle(); relayout(root); settle() }
            shot(root, if (festive) "ny-$name" else "base-$name")
            a.finish()
        }
        if (festive) {
            val (a, root) = open("D", "home")
            a.ck.overlays.settings("look"); settle(); relayout(root); settle()
            NewYear.settingsMock(a, root); settle(); relayout(root); settle(); relayout(root); settle()
            shot(root, "ny-D-settings")
            a.finish()
        }
    }
}
