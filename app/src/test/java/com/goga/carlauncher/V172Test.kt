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

/** v1.7.2: crash log, radio in the launcher, background tabs, rain/snow by hand, pill sizes, weather vs cover. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V172Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.title = null; BtMeta.artist = null
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null; Radio.muted = false
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true; FmCtl.reset()
        Wx.temp = -5.0; Wx.code = 0; Wx.at = System.currentTimeMillis()
        CrashLog.clear(ctx)
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-172-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun open(style: String, screen: String, big: Boolean = false): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        return a to root
    }
    private fun rect(v: View): Rect { val p = IntArray(2); v.getLocationInWindow(p); return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height) }

    @Test fun fmFrequencyFromTheTuner() {
        FmCtl.update(FmCtl.U_BAND, intArrayOf(0), null)
        FmCtl.update(FmCtl.U_FREQ, intArrayOf(10140), null)     // UNISOC: dekahertz
        assertEquals(101_400, FmCtl.khz); assertEquals("FM 101.4", Radio.freq)
        FmCtl.update(FmCtl.U_PS, null, arrayOf(" EUROPA "))
        assertEquals("EUROPA", FmCtl.ps)
        FmCtl.reset(); FmCtl.update(FmCtl.U_FREQ, intArrayOf(104000), null)   // kHz units
        assertEquals(104_000, FmCtl.khz)
        assertFalse("no service on the PC → not available", FmCtl.available())
        assertFalse(FmCtl.tuneTo(101_400))
    }

    @Test fun presetsStoreAndReset() {
        assertEquals(6, Presets.list().size); assertEquals("Europa Plus", Presets.list()[0].name)
        Presets.save(listOf(Presets.P(99_000, "Тест|;x")) + Presets.DEFAULT.drop(1))
        assertEquals(99_000, Presets.list()[0].khz); assertEquals("99.0", Presets.mhz(Presets.list()[0].khz))
        assertEquals(6, Stations.ALL.size); assertEquals(99.0, Stations.ALL[0].f, 0.001)
        K.radioPresets = "garbage"; assertEquals(Presets.DEFAULT.size, Presets.list().size)
    }

    @Test fun rainSnowByHand() {
        K.particles = true
        assertEquals("clear weather + auto → nothing", "none", Cockpit.fxKind(true))
        K.fxAuto = false; K.fxKind = "rain"
        assertEquals("rain", Cockpit.fxKind(true)); assertEquals("manual works on the theme too", "rain", Cockpit.fxKind(false))
        K.fxKind = "snow"; assertEquals("snow", Cockpit.fxKind(false))
        K.particles = false; assertEquals("none", Cockpit.fxKind(true))
        K.particles = true; K.fxAuto = true; Wx.code = 71; assertEquals("snow", Cockpit.fxKind(true)); assertEquals("none", Cockpit.fxKind(false))
    }

    @Test fun crashLogAndSafeExecutor() {
        CrashLog.write(ctx, "тест", RuntimeException("boom"))
        assertTrue(CrashLog.text(ctx).contains("boom"))
        val done = java.util.concurrent.CountDownLatch(1)
        AppRepo.exec.execute { throw IllegalStateException("bg") }
        AppRepo.exec.execute { done.countDown() }
        assertTrue("executor survives an exception", done.await(3, java.util.concurrent.TimeUnit.SECONDS))
        CrashLog.clear(ctx); assertEquals("", CrashLog.text(ctx))
    }

    @Test fun safeStartAfterTwoCrashes() {
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences("crash", android.content.Context.MODE_PRIVATE).edit().putString("times", "${now - 5000},${now - 1000}").commit()
        K.bg = "video"; K.waves = true; K.particles = true
        open("D", "home")
        assertEquals("theme", K.bg); assertFalse(K.waves); assertFalse(K.particles); assertTrue(K.safeAt > 0)
        assertEquals(0, CrashLog.recent(ctx))
    }

    @Test fun pillsFitTheirTextAndWeatherClearsTheCover() {
        for (st in listOf("D", "C")) for (big in listOf(false, true)) {
            val (_, root) = open(st, "music", big)
            for (p in all(root).filter { it.contentDescription == "Природа" || it.contentDescription == "Фон" }.filter { it.isShown }) {
                val f = p as ViewGroup; val row = f.getChildAt(0) as ViewGroup
                assertTrue("$st $big ${p.contentDescription}: text ${row.width} + padding fits ${f.width}", row.width + f.paddingLeft + f.paddingRight <= f.width + 1)
                assertTrue("$st $big ${p.contentDescription}: padding ≥ ${p.height / 3}", f.paddingLeft >= p.height / 3)
                val tv = all(row).filterIsInstance<TextView>().first()
                assertTrue("text not cut", tv.layout == null || tv.layout.getEllipsisCount(0) == 0)
            }
            if (st == "D") {
                val wx = all(root).first { it.contentDescription == "Погода" && it.isShown }
                val cover = all(root).filterIsInstance<android.widget.ImageView>().filter { it.isShown && it.width >= 300 && it.height >= 300 }.minByOrNull { it.left }!!
                val w = rect(wx); val c = rect(cover)
                assertTrue("$big weather $w above cover $c with a gap", w.bottom + 12 <= c.top)
            }
            shot(root, "music-$st${if (big) "-big" else ""}")
        }
    }

    @Test fun backgroundPanelHasSeparateTabs() {
        val (a, root) = open("D", "home")
        for (m in listOf("theme", "cover", "scene", "video")) {
            K.bg = m; a.ck.overlays.bgPanel(); settle(); relayout(root); settle()
            val t = texts(root)
            assertTrue("$m tabs: $t", t.containsAll(listOf("Тема", "Обложка", "Природа", "Видео")))
            assertTrue("$m current mode line", t.any { it.startsWith("Сейчас: ") })
            assertTrue("$m brightness for every mode", t.contains("Яркость фона"))
            assertTrue("$m rain/snow", t.contains("Показывать дождь и снег"))
            when (m) {
                "theme" -> assertTrue(t.contains("Цвет темы"))
                "cover" -> assertTrue(t.contains("Цвет из обложки трека"))
                "scene" -> assertTrue(t.contains("Фото"))
                "video" -> assertTrue(t.contains("Видео"))
            }
            shot(root, "bg-$m")
        }
        K.particles = true; K.fxAuto = false; a.ck.overlays.bgPanel(); settle(); relayout(root); settle()
        val t = texts(root); assertTrue("manual choice: $t", t.contains("Дождь") && t.contains("Снег"))
        shot(root, "bg-fx-manual")
    }

    @Test fun cleanupExplainsCacheAndCrashLogIsReachable() {
        val (a, root) = open("D", "home")
        a.ck.overlays.clean(); settle(8); relayout(root); settle()
        assertTrue(texts(root).contains("Как очистить кэш"))
        a.ck.overlays.onResume(); settle(); relayout(root); settle()
        assertTrue("still open after resume", texts(root).contains("Как очистить кэш"))
        shot(root, "clean")
        CrashLog.write(ctx, "тест", RuntimeException("boom2"))
        a.ck.overlays.crashLog(); settle(); relayout(root); settle()
        assertTrue(texts(root).any { it.contains("boom2") })
        shot(root, "crashlog")
    }

    @Test fun radioChipsWithoutTheServiceFallBack() {
        // on a PC there is no vendor service: tapping a chip must not crash
        val (a, root) = open("D", "music")
        Radio.on = true; Radio.at = android.os.SystemClock.elapsedRealtime(); Radio.freq = "FM 101.4"
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.RADIO)
        a.ck.build(); settle(); relayout(root); settle()
        val chip = all(root).firstOrNull { it.contentDescription?.toString()?.contains("Europa") == true || (it is TextView && it.text == "Europa Plus") }
        assertTrue("chips shown: ${texts(root)}", chip != null)
        shot(root, "radio")
    }

    @Test fun allAppsTileOpensSearchableGrid() {
        assertTrue(CkTiles.DEFAULT.any { it.kind == "apps" })
        val spm = shadowOf(ctx.packageManager)
        for ((pkg, label) in listOf("org.smarttube.stable" to "SmartTube", "ru.yandex.music" to "Яндекс Музыка", "com.android.chrome" to "Chrome")) {
            val cn = android.content.ComponentName(pkg, "$pkg.Main")
            spm.addActivityIfNotPresent(cn)
            spm.addIntentFilterForActivity(cn, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
            spm.getInternalMutablePackageInfo(pkg).applicationInfo.nonLocalizedLabel = label
            spm.getInternalMutablePackageInfo(pkg).activities?.forEach { it.nonLocalizedLabel = label }
        }
        AppRepo.invalidate()
        for (st in listOf("D", "C")) for (big in listOf(false, true)) {
            val (a, root) = open(st, "home", big)
            // C «Крупный» has 2 tiles per page → «Все приложения» is on page 2 (arrow); everywhere else on page 1
            if (st == "C" && big) { all(root).first { it.contentDescription == "Следующие плитки" }.performClick(); settle(); relayout(root); settle() }
            val tile = all(root).firstOrNull { it.contentDescription == "Все приложения" && it.isShown }
            assertTrue("$st $big: «Все приложения» tile: ${texts(root)}", tile != null)
            if (st == "D" && !big || st == "C" && !big) shot(root, "home-$st")
            tile!!.performClick(); settle(6); relayout(root); settle()
            val t = texts(root)
            assertTrue("$st $big grid: $t", t.contains("SmartTube") && t.contains("Chrome") && t.contains("Яндекс Музыка"))
            val labels = t.filter { it in setOf("SmartTube", "Chrome", "Яндекс Музыка") }
            assertEquals("A–Я", listOf("Chrome", "SmartTube", "Яндекс Музыка"), labels)
            if (st == "D" && !big) shot(root, "allapps")
            a.ck.overlays.closeAll()
        }
        val (a, root) = open("D", "home")
        a.ck.overlays.allApps("smart"); settle(6); relayout(root); settle()
        var t = texts(root)
        assertTrue("search: $t", t.contains("SmartTube") && !t.contains("Chrome"))
        // hold → add as tile / hide
        all(root).first { it.contentDescription == "SmartTube" }.performLongClick(); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("На главный экран"))
        all(root).filterIsInstance<TextView>().first { it.text == "На главный экран" }.performClick(); settle()
        assertTrue(CkTiles.load().any { it.pkg == "org.smarttube.stable" })
        a.ck.overlays.allApps(); settle(6); relayout(root); settle()
        all(root).first { it.contentDescription == "Chrome" }.performLongClick(); settle(); relayout(root); settle()
        all(root).filterIsInstance<TextView>().first { it.text == "Скрыть из списка" }.performClick(); settle(6); relayout(root); settle()
        t = texts(root); assertFalse("hidden: $t", t.contains("Chrome")); assertTrue(t.contains("Скрытые: 1"))
    }

    @Test fun existingTileListGetsAllAppsOnce() {
        CkTiles.save(listOf(CkTiles.Tile("radio", icon = "radio", name = "Радио"), CkTiles.Tile("settings", icon = "gear", name = "Настройки")))
        assertEquals(listOf("radio", "apps", "settings"), CkTiles.load().map { it.kind })
        CkTiles.save(CkTiles.load().filter { it.kind != "apps" })
        assertEquals("removed by the owner → stays removed", listOf("radio", "settings"), CkTiles.load().map { it.kind })
    }

    private fun tileView(root: View, name: String) = all(root).first { it.contentDescription == name && it.isShown && it is ViewGroup && all(it).any { c -> c.contentDescription == "Убрать" } }
    private fun sevenTiles() = CkTiles.DEFAULT + listOf(CkTiles.Tile("app", "a.one", "", "star", "Один"), CkTiles.Tile("app", "a.two", "", "star", "Два"))

    @Test fun tileMovesAcrossPagesFromTheSheet() {
        CkTiles.save(sevenTiles())
        val (a, root) = open("D", "home")
        K.tilePage = 1; a.ck.build(); settle(); relayout(root); settle()
        assertEquals(5, a.ck.tilesPerPage())
        a.ck.overlays.tileSheet(5); settle(); relayout(root); settle()        // «Один», first on page 2
        all(root).first { it.contentDescription == "Переместить влево" }.performClick(); settle(); relayout(root); settle()
        assertEquals("Один", CkTiles.load()[4].name)
        assertEquals("page 1 shown", 0, K.tilePage)
        a.ck.overlays.closeAll(); relayout(root); settle()
        assertTrue(texts(root).contains("Один"))
        all(root).first { it.contentDescription == "Один" }.let { assertTrue(it.isShown) }
        // and back to the right
        a.ck.overlays.tileSheet(4); settle(); relayout(root); settle()
        all(root).first { it.contentDescription == "Переместить вправо" }.performClick(); settle()
        assertEquals("Один", CkTiles.load()[5].name); assertEquals(1, K.tilePage)
        shot(root, "tile-sheet-move")
    }

    @Test fun dragToTheEdgeFlipsThePage() {
        for (st in listOf("D", "C")) {
            CkTiles.save(sevenTiles()); K.tilePage = 0
            val (a, root) = open(st, "home")
            K.tilePage = 1; a.ck.tilePageForTest(1); settle(); relayout(root)
            a.ck.editing = true; a.ck.build(); settle(); relayout(root); settle()
            val cap = a.ck.tilesPerPage()
            val name = CkTiles.load()[cap].name
            val v = tileView(root, name)
            val r = rect(v)
            val x0 = r.exactCenterX(); val y0 = r.exactCenterY()
            fun ev(act: Int, x: Float) = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), act, x, y0, 0)
            v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x0))
            var x = x0
            while (x > 20f) { x -= 40f; v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, x)) }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(Cockpit.FLIP_MS + 100)); settle(); relayout(root); settle()
            assertEquals("$st: page flipped", 0, K.tilePage)
            assertEquals("$st: dropped as the last tile of page 1", name, CkTiles.load()[cap - 1].name)
            assertTrue("$st: visible on page 1", all(root).any { it.contentDescription == name && it.isShown })
            // a plain drop on page 2 now stays on page 2 (global index, not page-local)
            K.tilePage = 1; a.ck.tilePageForTest(1); settle(); relayout(root); settle()
            val n2 = CkTiles.load()[cap].name
            val v2 = tileView(root, n2); val r2 = rect(v2)
            fun ev2(act: Int, x: Float) = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), act, x, r2.exactCenterY(), 0)
            v2.dispatchTouchEvent(ev2(android.view.MotionEvent.ACTION_DOWN, r2.exactCenterX()))
            v2.dispatchTouchEvent(ev2(android.view.MotionEvent.ACTION_MOVE, r2.exactCenterX() + 30))
            v2.dispatchTouchEvent(ev2(android.view.MotionEvent.ACTION_UP, r2.exactCenterX() + 30)); settle()
            assertTrue("$st: still on page 2", CkTiles.load().indexOfFirst { it.name == n2 } >= cap)
            a.ck.editing = false; a.finish()
        }
    }
}
