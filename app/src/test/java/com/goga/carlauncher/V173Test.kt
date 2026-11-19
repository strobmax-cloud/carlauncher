package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** v1.7.3: video folder, glow strength, ◀ ▶ backgrounds, «Телефон» without opening the BT app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V173Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        val k = ctx.getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        Prefs.askedPerms = true; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.title = null; BtMeta.artist = null; BtMeta.playAt = 0
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null; Radio.muted = false
        MediaHub::class.java.getDeclaredField("kind").apply { isAccessible = true }.set(MediaHub, MediaHub.Kind.NONE)
        Robo.sync = true; FmCtl.reset()
        Wx.temp = -5.0; Wx.code = 0; Wx.at = System.currentTimeMillis()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-173-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun open(style: String, screen: String, big: Boolean = false, music: Boolean = true): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (music) { BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо" }
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

    // ---------------- 1. video folder ----------------
    @Test fun videoFoldersAnyCaseAndChosenByHand() {
        val stick = File(ctx.cacheDir, "stick").apply { deleteRecursively(); mkdirs() }
        File(stick, "carlauncher/BACKGROUNDS").mkdirs(); File(stick, "carlauncher/BACKGROUNDS/road-night.mp4").writeBytes(ByteArray(120_000))
        assertEquals(1, Videos.dirsOn(stick).flatMap { Videos.videosIn(it) }.distinctBy { it.name }.size)
        val mine = File(stick, "Мои видео/лето").apply { mkdirs() }; File(mine, "море.MP4").writeBytes(ByteArray(120_000)); File(mine, "notes.txt").writeText("x")
        K.videoDir = File(stick, "Мои видео").path
        val l = Videos.scan(ctx)
        assertTrue("sub-folder of the chosen folder: ${l.map { it.file.name }}", l.any { it.file.name == "море.MP4" })
        assertEquals("/storage/emulated/0/Movies", VideoTree.idToPath("primary:Movies"))
        assertEquals("/storage/1A2B-3C4D/CarLauncher", VideoTree.idToPath("1A2B-3C4D:CarLauncher"))
        assertEquals("/storage/1A2B-3C4D", VideoTree.idToPath("1A2B-3C4D:"))
    }

    @Test fun folderBrowserPicksAFolder() {
        val stick = File(ctx.cacheDir, "stick2").apply { deleteRecursively(); mkdirs() }
        val d = File(stick, "Видео").apply { mkdirs() }; File(d, "nature-лес.mp4").writeBytes(ByteArray(120_000)); File(stick, "Документы").mkdirs()
        shadowOf(ctx).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val (a, root) = open("D", "home", music = false)
        a.ck.overlays.gallery(true); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("Выбрать папку…")); assertTrue(texts(root).contains("Через Android…"))
        shot(root, "video-gallery")
        a.ck.overlays.folderPicker(null); settle(); relayout(root); settle()
        assertTrue("roots listed: ${texts(root)}", texts(root).any { it.contains("Память") || it.contains("Флешка") || it.contains("USB") || it.contains("Накопитель") } || texts(root).any { it.startsWith("Видео не найдено") })
        shot(root, "folders-roots")
        a.ck.overlays.folderPicker(stick.path); settle(); relayout(root); settle()
        assertTrue(texts(root).containsAll(listOf("Видео", "⬆ Назад")))
        assertFalse("v1.7.4: folders without videos are hidden", texts(root).contains("Документы"))
        a.ck.overlays.folderPicker(d.path); settle(); relayout(root); settle()
        repeat(10) { settle(1) }; relayout(root); settle()
        assertTrue("preview tile: ${texts(root)}", all(root).any { it.contentDescription == "Лес" })
        shot(root, "folders-pick")
        all(root).filterIsInstance<TextView>().first { it.text == "Выбрать эту папку" }.performClick()
        repeat(20) { settle(1) }; relayout(root); settle()
        assertEquals(d.path, K.videoDir); assertEquals("video", K.bg); assertTrue(K.video.endsWith("nature-лес.mp4"))
    }

    // ---------------- 2. glow ----------------
    @Test fun coverGlowStrengthSlider() {
        assertEquals(70, K.glow)
        val (a, root) = open("D", "music")
        val g = all(root).filterIsInstance<CoverGlow>().first()
        assertTrue(g.isShown); assertEquals(0.7f, g.strength(), 0.01f)
        assertTrue("halo made", CoverGlow::class.java.getDeclaredField("halo").apply { isAccessible = true }.get(g) != null)
        val bm = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888); g.draw(Canvas(bm))
        assertTrue("glow drawn: ${g.width}x${g.height} a=${android.graphics.Color.alpha(bm.getPixel(g.width / 2, g.height / 2))}", android.graphics.Color.alpha(bm.getPixel(g.width / 2, g.height / 2)) > 60)
        shot(root, "glow-D-music")
        K.glow = 100; assertEquals(1f, g.strength(), 0.01f)
        g.playing = false; assertTrue(g.strength() < 0.5f)
        a.ck.overlays.bgPanel(); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("Сила подсветки от трека"))
        a.ck.overlays.closeAll(); a.ck.overlays.settings(); settle(); relayout(root); settle()
        assertTrue("in settings: ${texts(root)}", texts(root).contains("Сила подсветки от трека"))
        // cover-mode background re-renders with the strength
        K.bg = "cover"; K.glow = 20
        val lo = BgView.coverTheme(0xFF3366AA.toInt()); K.glow = 100; val hi = BgView.coverTheme(0xFF3366AA.toInt())
        val s = FloatArray(3); val s2 = FloatArray(3); android.graphics.Color.colorToHSV(lo.c2, s); android.graphics.Color.colorToHSV(hi.c2, s2)
        assertTrue("stronger = more colour", s2[1] > s[1])
    }

    // ---------------- 3. ◀ ▶ backgrounds ----------------
    @Test fun arrowsCycleBackgroundsOnEveryScreen() {
        for (st in listOf("D", "C")) for (big in listOf(false, true)) for (sc in listOf("home", "music")) {
            K.bg = "scene"; K.scene = "auto"
            val (a, root) = open(st, sc, big)
            val nx = all(root).filter { it.contentDescription == "Следующий фон" && it.isShown }
            val pv = all(root).filter { it.contentDescription == "Предыдущий фон" && it.isShown }
            assertEquals("$st $big $sc", 1, nx.size); assertEquals(1, pv.size)
            assertTrue("big enough to tap", nx[0].width >= 50 && nx[0].height >= 50)
            // not over the clock / weather
            val p = IntArray(2); pv[0].getLocationInWindow(p); assertTrue("$st $big $sc left arrow x ${p[0]}", p[0] > 880 || sc == "music")
            nx[0].performClick(); settle(); relayout(root); settle()
            assertEquals(Scenes.ALL[0].id, K.scene)
            all(root).first { it.contentDescription == "Предыдущий фон" && it.isShown }.performClick(); settle()
            assertEquals("auto", K.scene)
            if (!big) shot(root, "arrows-$st-$sc")
            a.finish()
        }
        // D theme → next theme colour; cover → next kind
        K.bg = "theme"; K.theme = Ck.THEMES[0].id
        assertTrue(BgCycle.step(1, false, false).contains(Ck.THEMES[1].name)); assertEquals(Ck.THEMES[1].id, K.theme)
        K.bg = "cover"; BgCycle.step(1, false, false); assertEquals("scene", K.bg)
        K.bg = "theme"; BgCycle.step(1, true, false); assertEquals("C theme → next kind", "cover", K.bg)
        // «Фон» tab (v1.7.4, was «Природа»): arrows too
        K.bg = "scene"; K.scene = "auto"; K.fonKind = "scene"
        val (a, root) = open("D", "music")
        a.ck.show(2); settle(); relayout(root); settle(); a.ck.fonTouch(); settle()
        all(root).first { it.contentDescription == "Следующий фон" && it.isShown }.performClick(); settle()
        assertEquals(Scenes.ALL[0].id, K.scene)
        shot(root, "arrows-fon")
    }

    // ---------------- 4. «Телефон» ----------------
    private class FakeMain : android.os.Binder() {
        val sent = ArrayList<Pair<Int, List<Int>>>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) { data.enforceInterface("com.syu.ipc.IRemoteModule"); val c = data.readInt(); val ints = data.createIntArray(); sent.add(c to (ints?.toList() ?: emptyList())); reply?.writeNoException(); return true }
            return super.onTransact(code, data, reply, flags)
        }
    }
    @Test fun phoneSwitchesSourceWithoutOpeningAnything() {
        val (a, root) = open("D", "music", music = false)
        // no unit service, no BT session, no BT app → nothing opened, a hint
        assertEquals(BtAudio.Result.NONE, BtAudio.select(a))
        assertNull(shadowOf(a).nextStartedActivity)
        // with the unit's service: audio source → Bluetooth (APP_ID_BTAV 3), still no screen opened
        val fake = FakeMain()
        FmCtl::class.java.getDeclaredField("main").apply { isAccessible = true }.set(FmCtl, fake)
        all(root).first { it.contentDescription == "Телефон" }.performClick(); settle()
        assertTrue("sent: ${fake.sent}", fake.sent.contains(0 to listOf(3)))
        assertNull("nothing opened", shadowOf(a).nextStartedActivity)
        assertTrue(MediaHub.phoneActive())
        a.ck.updMedia(true); settle(); relayout(root); settle()
        shot(root, "phone")
        FmCtl.reset()
    }

    // ---------------- 5. «Поездка» ----------------
    @Test fun tripUpdatesLiveOnScreen() {
        K.trip = 0f
        val (a, root) = open("D", "home", music = false)
        val l0 = android.location.Location("gps").apply { latitude = 51.1280; longitude = 71.4300; time = 1_000L; speed = 15f; accuracy = 5f }
        Trip.onFix(l0)
        var lat = 51.1280
        for (k in 1..12) { lat += 0.0005; Trip.onFix(android.location.Location("gps").apply { latitude = lat; longitude = 71.4300; time = 1_000L + k * 4_000L; speed = 15f; accuracy = 5f }) }
        settle(); relayout(root)
        assertTrue("trip ${K.trip}", K.trip > 0.5f)
        val shown = texts(root)
        assertTrue("screen shows the new trip without a rebuild: $shown", shown.contains(Trip.km(K.trip) + " км"))
    }

    @Test fun tripStartsAgainAfterStopOrIgnition() {
        val h = 3_600_000L
        val t0 = 1_800_000_000_000L
        Trip.add(5f, t0); assertEquals(5f, K.trip, 0.01f)
        Trip.add(1f, t0 + 20 * 60_000L); assertEquals("20 min stop = same trip", 6f, K.trip, 0.01f)
        assertFalse(Trip.checkTrip(t0 + 40 * 60_000L))
        Trip.add(1f, t0 + 55 * 60_000L); assertEquals("35 min stop → new trip", 1f, K.trip, 0.01f)
        assertEquals("day keeps counting", 7f, K.day, 0.01f)
        // shown as 0 already when parked long, before moving
        assertTrue(Trip.checkTrip(t0 + 55 * 60_000L + 31 * 60_000L)); assertEquals(0f, K.trip, 0.01f)
        // ignition off → on (screen off ≥ 2 min) → the next metre starts a new trip, even after a short stop
        Trip.add(3f, t0 + 2 * h)
        Trip.screen(false, 10_000L); Trip.screen(true, 10_000L + 3 * 60_000L)
        assertEquals("kept until moving", 3f, K.trip, 0.01f)
        Trip.add(0.2f, t0 + 2 * h + 5 * 60_000L); assertEquals(0.2f, K.trip, 0.01f)
        // short screen-off (< 2 min) is not an ignition cycle
        Trip.screen(false, 500_000L); Trip.screen(true, 530_000L)
        Trip.add(0.3f, t0 + 2 * h + 6 * 60_000L); assertEquals(0.5f, K.trip, 0.01f)
        // a new boot = ignition was off
        K.tripBootAt = 1_000L
        Trip.checkBoot(nowWall = t0 + 3 * h, sinceBoot = 60_000L)
        Trip.add(0.1f, t0 + 2 * h + 10 * 60_000L); assertEquals(0.1f, K.trip, 0.01f)
        // the same boot seen again → nothing
        Trip.checkBoot(nowWall = t0 + 3 * h + 1_000L, sinceBoot = 61_000L); Trip.add(0.1f, t0 + 2 * h + 11 * 60_000L); assertEquals(0.2f, K.trip, 0.01f)
        // switched off → only by hand
        K.tripAuto = false; Trip.add(1f, t0 + 10 * h); assertEquals(1.2f, K.trip, 0.01f)
        Trip.reset("trip"); assertEquals(0f, K.trip, 0.01f)
    }
}
