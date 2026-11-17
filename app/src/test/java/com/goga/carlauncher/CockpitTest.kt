package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Region
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.Calendar

/** v1.7.1 new interface: styles C «Линза» / D «Стекло», two screens, tiles, settings, backgrounds, volume, safety. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class CockpitTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.title = null; BtMeta.artist = null
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true
        Wx.temp = -12.0; Wx.code = 71; Wx.at = System.currentTimeMillis()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View, w: Int = 1280, h: Int = 720) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-ck-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun playing() {
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо-исполнитель"
    }
    private fun open(style: String, big: Boolean = false, screen: String = "home", music: Boolean = true): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (music) playing()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        if (music) {
            val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
            Cockpit::class.java.getDeclaredMethod("setArt", Bitmap::class.java).apply { isAccessible = true }.invoke(a.ck, cover)
            settle(); relayout(root); settle()
        }
        return a to root
    }

    @Test fun screensRenderInBothStylesAndSizes() {
        for (st in listOf("C", "D")) for (big in listOf(false, true)) for (sc in listOf("home", "music")) {
            val (a, root) = open(st, big, sc)
            val t = texts(root)
            assertTrue("$st $sc bottom switch", t.contains("Главная") && t.contains("Музыка"))
            assertTrue("$st $sc track shown: $t", t.any { it.contains("Ночная трасса") })
            val vols = all(root).filter { it.contentDescription == "Громче" || it.contentDescription == "Тише" }
            assertEquals("$st $sc volume buttons in both corners", 4, vols.size)
            if (sc == "home") {
                val want = CkTiles.DEFAULT.map { it.name }.take(if (st == "C") (if (big) 2 else 4) else (if (big) 4 else 5))   // tiles per page
                assertTrue("$st tiles: $t", t.containsAll(want))
                assertTrue(t.any { it.matches(Regex("\\d{1,2}:\\d\\d")) })
            }
            assertTrue("$st weather", t.any { it == "−12°" })
            shot(root, "$st-$sc" + if (big) "-big" else "")
            a.finish()
        }
    }

    @Test fun switchScreensAndNature() {
        val (a, root) = open("D")
        all(root).first { it.contentDescription == "Музыка" }.performClick(); settle(); relayout(root); settle()
        assertEquals(1, a.ck.screen)
        assertTrue(texts(root).contains("Природа"))
        all(root).first { it.contentDescription == "Природа" }.performClick(); settle(); relayout(root); settle()
        assertTrue(a.ck.nature)
        shot(root, "D-nature")
        a.onBackPressed(); settle()
        assertFalse(a.ck.nature)
        a.ck.home(); settle()
        assertEquals(0, a.ck.screen)
    }

    @Test fun volumeButtonsStepAndShowIndicator() {
        val (a, root) = open("C")
        Prefs.volMethod = "stream"
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 5, 0)
        val plus = all(root).first { it.contentDescription == "Громче" }
        val now = android.os.SystemClock.uptimeMillis()
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        plus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0))
        settle(2)
        assertEquals(6, am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        assertEquals("stream", Volume.lastMethod)
        relayout(root); settle(1)
        shot(root, "C-volume")
        val minus = all(root).first { it.contentDescription == "Тише" }
        minus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        minus.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0))
        settle(2)
        assertEquals(5, am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
    }

    @Test fun settingsSectionsAndVolumeTest() {
        val (a, root) = open("D")
        a.ck.overlays.settings("look"); settle(); relayout(root); settle()
        val t = texts(root)
        for (s in listOf("Внешний вид", "Главный экран и плитки", "Музыка и радио", "Машина и пробег", "Громкость", "Память и очистка", "Обновления", "О программе"))
            assertTrue("section $s", t.contains(s))
        shot(root, "settings-look")
        a.ck.overlays.settings("vol"); settle(); relayout(root); settle()
        assertTrue(texts(root).any { it.contains("Проверка громкости", true) })
        shot(root, "settings-vol")
        a.ck.overlays.settings("about"); settle(); relayout(root); settle()
        assertTrue("«Вернуть штатный экран» must stay", texts(root).any { it.contains("Вернуть штатный экран", true) })
        assertTrue(texts(root).contains("Выбрать главный экран"))
        shot(root, "settings-about")
        a.ck.overlays.settings("mem"); settle(); relayout(root); settle()
        a.ck.overlays.clean(); settle(6); relayout(root); settle()
        shot(root, "clean")
        assertTrue(a.onBackPressed().let { true }); settle()
    }

    @Test fun backgroundPanelGalleryAndVideo() {
        val (a, root) = open("C", music = false)
        a.ck.overlays.bgPanel(); settle(); relayout(root); settle()
        assertTrue(texts(root).containsAll(listOf("Тема", "Обложка", "Природа", "Видео")))
        shot(root, "bg-panel")
        a.ck.overlays.gallery(); settle(6); relayout(root); settle()
        shot(root, "gallery")
        // a video on the "flash drive"
        val tmp = File(System.getProperty("java.io.tmpdir"), "cktest-usb").apply { deleteRecursively() }
        File(tmp, Videos.DIR).mkdirs(); File(tmp, Videos.DIR + "/road-night_city.mp4").writeBytes(ByteArray(2048))
        Media.fixedVolumes = listOf(Media.Vol("USB", tmp, true))
        try {
            val l = Videos.scan(ctx)
            assertTrue(l.any { it.name == "Night city" && it.group == "Дороги" })
            K.bg = "video"; a.ck.overlays.closeAll(); a.ck.applyBackground(); settle()
            assertNotNull(Videos.chosen())
            assertTrue(all(root).any { it is VideoBg })
            a.ck.overlays.gallery(true); settle(); relayout(root); settle()
            assertTrue(texts(root).any { it.contains("Night city") })
        } finally { Media.fixedVolumes = null; tmp.deleteRecursively() }
    }

    @Test fun natureSceneWithParticles() {
        K.bg = "scene"; K.wxAuto = false; K.wxKind = "snow"; K.wxTod = "night"
        val (a, root) = open("D")
        settle(4); relayout(root); settle()
        assertEquals("snow", Wx.kind())
        shot(root, "D-scene-snow")
        a.finish()
        K.style = "C"; K.wxKind = "rain"; K.wxTod = "day"
        val (b, r2) = open("C", screen = "music")
        settle(4); relayout(r2); settle()
        shot(r2, "C-scene-rain")
        b.finish()
    }

    @Test fun tilesEditPagesAndDefaults() {
        assertEquals(listOf("Радио", "Навигатор", "Камера", "Все приложения", "Настройки"), CkTiles.DEFAULT.map { it.name })
        assertEquals(1, CkTiles.pages(5, 5)); assertEquals(2, CkTiles.pages(6, 5)); assertEquals(1, CkTiles.pages(0, 5))
        val l = CkTiles.DEFAULT + listOf(CkTiles.Tile("app", "org.smarttube.stable", "", "play", "SmartTube"), CkTiles.Tile("app", "ru.yandex.music", "", "note", "Музыка"))
        assertEquals(l, CkTiles.decode(CkTiles.encode(l)))
        CkTiles.save(l)
        val (a, root) = open("D", music = false)
        val arrows = all(root).filter { it.contentDescription == "Следующие плитки" }
        assertEquals(1, arrows.size)
        assertEquals(1f, arrows[0].alpha, 0.01f)     // overflow → bright
        arrows[0].performClick(); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("Музыка"))
        a.ck.editing = true; a.ck.build(); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("Готово"))
        shot(root, "D-edit")
        a.ck.overlays.tileSheet(0); settle(); relayout(root); settle()
        shot(root, "tile-sheet")
        a.ck.overlays.closeAll(); a.ck.editing = false
        CkTiles.save(CkTiles.DEFAULT)
        a.ck.build(); settle(); relayout(root); settle()
        val dim = all(root).first { it.contentDescription == "Следующие плитки" }
        assertTrue("no overflow → dim arrow", dim.alpha < 0.5f)
    }

    @Test fun mileageDayResetsAt4AndManualReset() {
        val c = Calendar.getInstance().apply { set(2026, 10, 17, 3, 59) }
        val d = Calendar.getInstance().apply { set(2026, 10, 17, 4, 1) }
        assertTrue(Trip.dayKey(c) != Trip.dayKey(d))
        K.day = 12f; K.trip = 5f; K.total = 1234f; K.dayKey = Trip.dayKey(c)
        Trip.checkDay(d)
        assertEquals(0f, K.day, 0.001f); assertEquals(5f, K.trip, 0.001f)
        val (a, root) = open("C", music = false)
        a.ck.overlays.resetPopup("trip"); settle(); relayout(root); settle()
        shot(root, "C-reset")
        all(root).filterIsInstance<TextView>().first { it.text == "Сбросить" }.performClick(); settle()
        assertEquals(0f, K.trip, 0.001f); assertEquals(1234f, K.total, 0.001f)
    }

    @Test fun weatherCodes() {
        assertEquals("clear", Wx.kindOf(0)); assertEquals("cloudy", Wx.kindOf(3)); assertEquals("rain", Wx.kindOf(61)); assertEquals("snow", Wx.kindOf(73)); assertEquals("rain", Wx.kindOf(95))
        Wx.temp = 3.4; assertEquals("+3°", Wx.tempText()); Wx.temp = -0.2; assertEquals("0°", Wx.tempText())
    }

    /** STEP 0 geometry: C panels sit inside the lens and below the frame's upward curve */
    @Test fun cPanelsInsideLensClearOfTheFrame() {
        val clip = Region(0, 0, 1280, 720)
        val lens = Region().apply { setPath(Lens.path, clip) }
        val ring = Region().apply { setPath(android.graphics.Path().apply { addCircle(Lens.X, Lens.Y, Lens.RO + 6f, android.graphics.Path.Direction.CW) }, clip) }
        for (big in listOf(false, true)) {
            val top = if (big) 240 else 244; val ph = if (big) 168 else 158; val low = if (big) 420 else 418; val lh = if (big) 100 else 98
            for (x in listOf(162, 822)) for ((y, h) in listOf(top to ph, low to lh)) {
                for ((px, py) in listOf(x + 4 to y + 4, x + 292 to y + 4, x + 4 to y + h - 4, x + 292 to y + h - 4))
                    assertTrue("corner $px,$py inside lens (big=$big)", lens.contains(px, py))
                val r = Region(x, y, x + 296, y + h); assertFalse("panel $x,$y overlaps ring", r.op(ring, Region.Op.INTERSECT))
            }
        }
    }

    @Test fun updaterDeletesLeftoverApks() {
        val f = File(ctx.cacheDir, "update.apk").apply { writeBytes(ByteArray(1000)) }
        val g = File(ctx.cacheDir, "old.apk").apply { writeBytes(ByteArray(500)) }
        val keep = File(ctx.cacheDir, "notes.txt").apply { writeText("x") }
        assertEquals(1500L, Updater.cleanupLeftovers(ctx))
        assertFalse(f.exists()); assertFalse(g.exists()); assertTrue(keep.exists())
    }

    /** owner's rule: no steering-wheel / key interception, nothing that controls the MCU, the way back stays */
    @Test fun safety() {
        for (cls in listOf(MainActivity::class.java, ClassicActivity::class.java)) {
            for (m in listOf("onKeyDown", "onKeyUp", "onKeyLongPress", "dispatchKeyEvent", "onKeyMultiple"))
                assertTrue("${cls.simpleName}.$m must not be overridden", cls.declaredMethods.none { it.name == m })
        }
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SERVICES)
        assertTrue("no accessibility / key-filter service", pi.services.orEmpty().none { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
        val src = File(System.getProperty("user.dir"), "src/main/java/com/goga/carlauncher").listFiles()!!.filter { it.name.endsWith(".kt") }.joinToString("\n") { it.readText() }
        for (bad in listOf("Settings.System.put", "Settings.Global.put", "Settings.Secure.put", "setMasterMute", "android.permission.WRITE_SECURE_SETTINGS"))
            assertFalse("never touch system settings: $bad", src.contains(bad))
        // the only com.syu.ms commands we send: SOUND module volume up/down/set
        assertTrue(src.contains("const val SOUND = 4"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(Intent0.isHome(pm, ctx.packageName))
        home.finish()
    }
}

private object Intent0 {
    fun isHome(pm: android.content.pm.PackageManager, pkg: String): Boolean {
        val r = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).setPackage(pkg), 0)
        return r.any { it.activityInfo.name.endsWith(".MainActivity") }
    }
}
