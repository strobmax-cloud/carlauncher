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

/** v1.7.5: the FYT system bar on top — stage fits the real window, backgrounds edge to edge; ◁ ○ □ in the launcher; the service is minimal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V175Test {
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
        val out = File(System.getProperty("user.dir"), "../preview/robo-175-$name.png"); out.parentFile!!.mkdirs()
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

    // ---------------- 1. real window height, edge-to-edge background ----------------
    @Test fun stageFitsTheRealWindow() {
        for (st in listOf("D", "C")) for (sc in listOf("home", "music")) {
            K.bg = "scene"
            val (a, root) = open(st, sc)
            for (hh in listOf(720, 650)) {
                relayout(root, 1280, hh); settle(); relayout(root, 1280, hh); settle()
                val stg = a.ck.root
                assertEquals("$st $sc $hh scale", minOf(1f, hh / 720f), stg.scale, 0.01f)
                val bgR = rect(a.ck.bg)
                assertTrue("$st $sc $hh bg covers the window: $bgR", bgR.left <= 0 && bgR.top <= 0 && bgR.right >= 1279 && bgR.bottom >= hh - 1)
                val pg = rect(a.ck.page)
                assertTrue("$st $sc $hh content inside and centred: $pg", pg.left >= 0 && pg.right <= 1280 && pg.bottom <= hh + 1 && Math.abs(pg.centerX() - 640) <= 2)
                if (st == "D" || sc == "home") shot(root, "$st-$sc-$hh")
            }
            a.finish()
        }
    }
    @Test fun barOverTheWindowIsKeptFree() {
        K.hideBar = false
        val (a, root) = open("D", "home")
        val ins = android.view.WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(0, 70, 0, 0)).build()
        a.ck.root.onApplyWindowInsets(ins); settle(); relayout(root); settle()
        assertEquals(70, a.ck.root.topInset)
        val pg = rect(a.ck.page); val bgR = rect(a.ck.bg)
        assertTrue("content below the bar: $pg", pg.top >= 70 && pg.bottom <= 721)
        assertTrue("background under the bar too: $bgR", bgR.top <= 0 && bgR.left <= 0 && bgR.right >= 1279)
        shot(root, "D-home-bar70")
        // manual override
        K.topPad = 60; a.ck.root.onApplyWindowInsets(ins); settle(); relayout(root); settle()
        assertEquals(60, a.ck.root.topInset)
        // hidden bar → immersive flags on our window only
        K.hideBar = true; a.applyBars()
        @Suppress("DEPRECATION") val f = a.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION") assertTrue((f and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 && (f and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0)
        K.hideBar = false; a.applyBars()
        @Suppress("DEPRECATION") assertEquals(0, a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // ---------------- 2. ◁ ○ □ in the launcher ----------------
    @Test fun sysButtonsFitBetweenVolumeAndTabs() {
        for (st in listOf("D", "C")) for (big in listOf(false, true)) for (pos in listOf("left", "right")) {
            K.navPos = pos
            val (a, root) = open(st, "music", big)
            val pill = all(root).first { it.contentDescription == "Системные кнопки" && it.isShown }
            val names = all(pill).mapNotNull { it.contentDescription?.toString() }
            assertTrue(names.containsAll(listOf("Назад", "Домой"))); assertFalse("no recents without the service", names.contains("Недавние"))
            val p = rect(pill)
            if (pos == "left") assertTrue("$st $big left: $p", p.right < 640) else assertTrue("$st $big right: $p", p.left > 640)
            val others = all(root).filter { it.isShown && it.contentDescription in listOf("Тише", "Громче", "Главная", "Музыка", "Фон") && it.width < 400 && rect(it).top > 560 }
            assertTrue(others.size >= 7)
            for (o in others) assertFalse("$st $big $pos overlaps ${o.contentDescription} ${rect(o)} / $p", Rect.intersects(rect(o), p))
            for (b in all(pill).filter { it.contentDescription == "Назад" || it.contentDescription == "Домой" }) assertTrue("tap target ${b.width}×${b.height}", b.width >= 54 && b.height >= 60)
            if (pos == "left") shot(root, "nav-$st${if (big) "-big" else ""}")
            all(pill).first { it.contentDescription == "Домой" }.performClick(); settle()
            assertEquals(0, a.ck.screen)
            a.finish()
        }
        K.navPos = "off"
        val (_, root) = open("D", "home")
        assertTrue(all(root).none { it.contentDescription == "Системные кнопки" })
    }
    @Test fun settingsSectionAndHelp() {
        val (a, root) = open("D", "home")
        a.ck.overlays.settings("sysnav"); settle(); relayout(root); settle()
        val t = texts(root)
        assertTrue(t.toString(), t.containsAll(listOf("Скрывать системную панель в лаунчере", "Плавающие кнопки ◁ ○ □", "Где показывать")))
        assertTrue(t.any { it.startsWith("Экран ") && it.contains("окно") })
        shot(root, "settings")
        a.ck.overlays.navHelp(); settle(); relayout(root); settle()
        assertTrue(texts(root).any { it.contains("Спец. возможности") })
        shot(root, "navhelp")
    }

    // ---------------- 3. the service is minimal ----------------
    @Test fun accessibilityServiceIsMinimal() {
        val x = File("src/main/res/xml/nav_access.xml").readText()
        assertFalse(x.contains("flagRequestFilterKeyEvents")); assertFalse(x.contains("canRequestFilterKeyEvents"))
        assertTrue(x.contains("android:canRetrieveWindowContent=\"false\""))
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:name=\".NavAccess\"") && m.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(m.contains("SYSTEM_ALERT_WINDOW") && m.contains("NavAccess\" android:permission=\"android.permission.SYSTEM_ALERT_WINDOW"))
        // no key handling anywhere in the service
        val src = File("src/main/java/com/goga/carlauncher/NavButtons.kt").readText()
        assertFalse(src.contains("onKeyEvent"))
    }
}
