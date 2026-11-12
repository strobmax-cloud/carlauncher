package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** v1.7.1: TEYES volume (com.syu.ms), volume test screen, floating volume, big buttons / menu size, cover colour + sharp cover, cleanup, nav buttons, big crisp SX4. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V171Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        Prefs.dayMode = "night"; Prefs.roadMode = "night"; Prefs.plate = "777 ABC 01"; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.playAt = 0
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View, w: Int = root.width, h: Int = root.height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun screens(a: MainActivity) = MainActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(a) as Array<*>
    private fun home(a: MainActivity) = screens(a)[0] as HomeScreen
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v171-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun save(b: Bitmap, name: String) = FileOutputStream(File(System.getProperty("user.dir"), "../preview/robo-v171-$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun play(a: MainActivity) {
        val h = home(a)
        fun call(n: String, t: Class<*>, arg: Any) = HomeScreen::class.java.getDeclaredMethod(n, t).apply { isAccessible = true }.invoke(h, arg)
        fun f(n: String) = HomeScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.get(h)
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
        call("setIdle", Boolean::class.javaPrimitiveType!!, false)
        call("showArt", Bitmap::class.java, ArtLoader.scale(cover, 520))
        (f("srcTv") as TextView).text = "ЯНДЕКС МУЗЫКА"
        (f("titleTv") as TextView).text = "Ночная трасса"
        (f("artistTv") as TextView).text = "Демо-исполнитель · Альбом 2026"
    }
    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        val ai = android.content.pm.ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label; sourceDir = "/nonexistent/$pkg.apk" }
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val cn = android.content.ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }
    private fun open(layout: String, w: Int = 1280, h: Int = 720, music: Boolean = true): Pair<MainActivity, View> {
        Prefs.layout = layout
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root, w, h); settle(); relayout(root, w, h); settle()
        if (music) { play(a); settle(4); relayout(root, w, h); settle(3) }
        return a to root
    }
    private fun loc(v: View) = IntArray(2).also { v.getLocationInWindow(it) }

    // ---------------- volume ----------------
    /** Fake com.syu.ms SOUND module: records what we send (wire format of IRemoteModule.cmd). */
    class FakeModule : Binder() {
        val sent = ArrayList<Pair<Int, IntArray?>>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) { data.enforceInterface("com.syu.ipc.IRemoteModule"); val c = data.readInt(); sent.add(c to data.createIntArray()); reply?.writeNoException(); return true }
            return super.onTransact(code, data, reply, flags)
        }
    }

    @Test fun teyesVolumeWireFormat() {
        assertEquals((1..8).toList(), Volume.METHODS.map { it.n })
        assertEquals(9, Volume.IDS.size)
        Prefs.volMethod = "syu"; assertEquals("syu", Prefs.volMethod)
        Prefs.volMethod = "nonsense"; assertEquals("auto", Prefs.volMethod)
        // not connected → auto falls back to Android without crashing
        assertTrue(Volume.step(ctx, true, "auto") in setOf("stream", "suggested", "key"))
        val m = FakeModule()
        Syu::class.java.getDeclaredField("sound").apply { isAccessible = true }.set(Syu, m)
        Syu::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(Syu, true)
        assertEquals("syu", Volume.step(ctx, true, "auto"))
        assertEquals("syu", Volume.step(ctx, false, "syu"))
        Syu.level = 17
        Volume.step(ctx, true, "syuset")
        assertEquals(listOf(0, 0, 0), m.sent.map { it.first })
        assertEquals(listOf(-1, -2, 18), m.sent.map { it.second!!.single() })
        assertTrue(Volume.readout(ctx).contains("Магнитола (com.syu.ms): 17"))
        // every Android method runs without throwing
        for (id in listOf("stream", "suggested", "adjust", "set", "key", "bcast")) Volume.run(ctx, id, true)
        Syu::class.java.getDeclaredField("sound").apply { isAccessible = true }.set(Syu, null)
        Syu::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(Syu, false)
        Syu.level = -1
    }

    @Test fun volumeTestScreen() {
        val (a, root) = open("cockpit", music = false)
        a.show(MainActivity.VOLTEST); settle(3); relayout(root); settle(2)
        val plus = all(root).filter { it.isShown && (it.contentDescription ?: "").toString().matches(Regex("Способ \\d громче")) }
        assertEquals(9, plus.size)   // 0 = Авто + 8 methods
        plus.forEach { assertTrue("big", it.height >= 70 && it.width >= 80) }
        all(root).first { it.isShown && it is TextView && it.text == "Выбрать" }.performClick(); settle(1)
        assertTrue(Prefs.volMethod != "auto")
        shot(root, "voltest")
        a.onBackPressed(); assertEquals(MainActivity.SETTINGS, a.current)
    }

    /** No empty strip: the content uses the whole width next to the menu; volume floats over it, both sides. */
    @Test fun floatingVolumeNoStrip() {
        for (side in listOf("left", "right", "bottom")) {
            Prefs.railSide = side; Prefs.volSide = "both"
            val (a, root) = open("minimal")
            val area = com.goga.carlauncher.Glass.root!!
            val vols = all(root).filter { it.contentDescription == "Громче" || it.contentDescription == "Тише" }
            assertEquals(4, vols.size)
            vols.forEach { v -> var p = v.parent; var inArea = false; while (p != null) { if (p === area) inArea = true; p = p.parent }; assertTrue("$side: over the content", inArea) }
            val railW = Math.round(MainActivity.RAIL_W * Prefs.railScale * Ui.s)
            if (side != "bottom") assertTrue("$side: content = window − menu (${area.width} vs ${root.width - railW})", kotlin.math.abs(area.width - (root.width - railW)) <= 2)
            else assertEquals(root.width, area.width)
            vols.forEach { val l = loc(it); assertTrue(l[0] >= 0 && l[0] + it.width <= root.width && l[1] + it.height <= root.height) }
            if (side == "left") shot(root, "floating-volume")
            a.finish()
        }
    }

    @Test fun bigMenuButtons() {
        for ((size, k) in listOf("normal" to 1f, "large" to 1.25f, "xlarge" to 1.45f)) {
            Prefs.railSize = size; assertEquals(k, Prefs.railScale)
        }
        Prefs.railSize = "nope"; assertEquals("large", Prefs.railSize)
        Prefs.railItems = "home,layout,settings"
        val (_, root) = open("cockpit")
        for (d in listOf("Предыдущая раскладка", "День / ночь", "Следующая раскладка", "Вид: фон и цвет")) {
            val v = all(root).first { it.contentDescription == d }
            assertTrue("$d ≥ 64 px (${v.width})", v.width >= 64 && v.height >= 64)
        }
        shot(root, "rail-large")
        Prefs.railSize = "xlarge"; val (_, r2) = open("cockpit"); shot(r2, "rail-xlarge")
        Prefs.railSize = "large"; Prefs.railSide = "bottom"; val (_, r3) = open("cockpit"); shot(r3, "dock-large")
    }

    @Test fun defaultMenuHasBackRecents() {
        assertEquals(listOf("home", "back", "recents", "apps", "media", "dvr", "settings", "layout", "phone", "wifi"), Rail.items())
        // existing 1.7.0 menus get them once, right after «Главная»
        Prefs.railItems = "home,apps,settings,wifi"
        val (_, root) = open("cockpit", music = false)
        assertEquals(listOf("home", "back", "recents", "apps", "settings", "wifi"), Rail.items())
        assertTrue(all(root).any { it.contentDescription == "Назад" } && all(root).any { it.contentDescription == "Недавние" })
        Prefs.railItems = "home,settings"; val (_, r2) = open("cockpit", music = false)
        assertEquals(listOf("home", "settings"), Rail.items())   // migration runs once only
        assertTrue(!NavAccess.enabled(ctx) && !NavAccess.act(1))
    }

    // ---------------- cover ----------------
    @Test fun coverColourAndSharpCover() {
        val red = IntArray(32 * 32) { 0xFFD02020.toInt() }
        val c = CoverColor.pick(red)!!
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
        assertTrue(hsv[0] < 15 || hsv[0] > 345); assertTrue(hsv[2] >= 0.85f)
        assertNull(CoverColor.pick(IntArray(1024) { 0xFF808080.toInt() }))
        assertNotNull(CoverColor.pick(IntArray(1024) { if (it % 3 == 0) 0xFF20A0F0.toInt() else 0xFF111111.toInt() }))
        // sharp cover background
        Prefs.bgMode = "sharp"
        val (_, root) = open("cockpit"); settle(6); relayout(root); settle(2)
        assertTrue(Wallpaper.full != null && Wallpaper.full!!.width > 600)
        shot(root, "sharp-cover")
        Prefs.bgMode = "cover"; val (_, r1) = open("cockpit"); settle(4); shot(r1, "blur-cover")
        // accent from the cover
        Prefs.bgMode = "sharp"; Prefs.coverAccent = true
        val (a2, r2) = open("showcase"); settle(8); relayout(r2); settle(4)
        val cc = Wallpaper.coverColor
        assertNotNull(cc); assertEquals(cc, Ui.accent)
        shot(r2, "cover-accent")
        QuickPanel.show(a2); settle(2)
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val dv = dlg.window!!.decorView; relayout(dv, 900, 700); settle(1)
        assertTrue(all(dv).any { it.contentDescription == "Чёткая обложка" && it.height >= 72 })
        save(Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888).also { dv.draw(Canvas(it)) }, "quick-panel")
    }

    // ---------------- cleanup ----------------
    @Test fun cleanupScreen() {
        assertEquals(Cleanup.Mark.KEEP, Cleanup.mark(ctx, "com.syu.steer", "Обучение руля", true))
        assertEquals(Cleanup.Mark.KEEP, Cleanup.mark(ctx, "com.android.vending", "Google Play", true))
        assertEquals(Cleanup.Mark.KEEP, Cleanup.mark(ctx, "com.x.cam", "Cam REC", true))
        assertEquals(Cleanup.Mark.CAN, Cleanup.mark(ctx, "com.teyes.vision", "Teyes Vision", true))
        assertEquals(Cleanup.Mark.CAN, Cleanup.mark(ctx, "com.android.chrome", "Chrome", true))
        assertEquals(Cleanup.Mark.CAN, Cleanup.mark(ctx, "com.x.radio", "Teyes Radio", true))
        assertEquals(Cleanup.Mark.USER, Cleanup.mark(ctx, "org.videolan.vlc", "VLC", false))
        assertEquals(Cleanup.Mark.SELF, Cleanup.mark(ctx, ctx.packageName, "Car Launcher", false))
        val G = 1_073_741_824L; val M = 1_048_576L
        val demo = Cleanup.sort(listOf(
            Cleanup.AppSize("ru.yandex.yandexnavi", "Яндекс Навигатор", 180 * M, 1400 * M, 320 * M, false, false, true, Cleanup.Mark.KEEP),
            Cleanup.AppSize("com.android.chrome", "Chrome", 260 * M, 380 * M, 410 * M, true, true, true, Cleanup.Mark.CAN),
            Cleanup.AppSize("com.spotify.music", "Spotify", 120 * M, 90 * M, 640 * M, true, true, true, Cleanup.Mark.CAN),
            Cleanup.AppSize("com.teyes.vision", "Teyes Vision", 210 * M, 20 * M, 5 * M, true, false, true, Cleanup.Mark.CAN),
            Cleanup.AppSize("org.smarttube.stable", "SmartTube", 60 * M, 150 * M, 230 * M, false, false, true, Cleanup.Mark.KEEP),
            Cleanup.AppSize("com.syu.steer", "Обучение руля", 8 * M, 1 * M, 0, true, false, true, Cleanup.Mark.KEEP)))
        assertEquals("Яндекс Навигатор", demo.first().label)
        val (a, root) = open("cockpit", music = false)
        a.show(MainActivity.CLEAN); settle(8); relayout(root); settle(2)
        val cs = screens(a)[MainActivity.CLEAN] as CleanScreen
        cs.showApps(demo)
        cs.showFolders(listOf(Cleanup.Folder("Download", "/x", 3 * G, Cleanup.hint("Download")), Cleanup.Folder("Android", "/x", 2 * G, Cleanup.hint("Android")),
            Cleanup.Folder("Movies", "/x", 900 * M, Cleanup.hint("Movies")), Cleanup.Folder("DCIM", "/x", 300 * M, Cleanup.hint("DCIM"))),
            listOf(File("/x/SmartTube-32.71-stable-universal.apk"), File("/x/CarLauncher-v1.7.0.apk")))
        settle(1); relayout(root); settle(1)
        val texts = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("не трогать") && texts.contains("можно убрать"))
        assertTrue("no uninstall for «не трогать»", texts.count { it == "Удалить" || it == "Удалить обновл." || it == "Отключить…" } == 3)
        shot(root, "cleanup")
        val sv = all(root).filterIsInstance<android.widget.ScrollView>().first { it.isShown }
        val body = sv.getChildAt(0)
        save(Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888).also { body.draw(Canvas(it)) }, "cleanup-full")
        // badge «Мало места» opens the cleanup screen
        a.show(MainActivity.HOME); settle(1)
        MainActivity::class.java.getDeclaredField("lowText").apply { isAccessible = true }.set(a, "Память магнитолы 4 %")
        MainActivity::class.java.getDeclaredMethod("updateBadge").apply { isAccessible = true }.invoke(a)
        all(root).first { it is TextView && it.text.toString().startsWith("⚠ Мало места") }.performClick(); settle(1)
        assertEquals(MainActivity.CLEAN, a.current)
    }

    // ---------------- SX4 ----------------
    @Test fun bigCrispCar() {
        for (l in listOf("minimal", "smart", "cluster", "showcase")) {
            val (_, root) = open(l); shot(root, "car-$l")
            val road = all(root).first { it is RoadView && it.isShown }
            assertTrue("$l road block is big (${road.width}×${road.height})", road.width >= 300 && road.height >= 300)
        }
        Prefs.roadMode = "day"; val (_, r) = open("showcase"); shot(r, "car-showcase-day")
        // the car scaled in halving steps lands exactly on the requested size
        val src = Bitmap.createBitmap(637, 730, Bitmap.Config.ARGB_8888)
        val s = RoadArt.scaledCar(src, 250, 287); assertEquals(250, s.width); assertEquals(287, s.height)
        // plate as vectors: close-up of the road view at 2× density
        val big = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        RoadArt.paint(ctx, Canvas(big), 900, 700, 0xFF0A84FF.toInt(), false, carH = 0.9f, maxCarW = 0.7f, horizon = 0.05f, ground = 0.95f, day = false, plate = "777 ABC 01")
        save(big, "car-closeup")
        save(RoadArt.plateBitmap("777 ABC 01"), "plate")
    }

    @Test fun settingsRender() {
        val (a, root) = open("cockpit", music = false)
        a.openSettings(); settle(3); relayout(root); settle(2)
        val sv = all(root).filterIsInstance<android.widget.ScrollView>().first { it.isShown && (it.getChildAt(0)?.height ?: 0) > 3000 }
        val body = sv.getChildAt(0)
        val full = Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888); body.draw(Canvas(full))
        FileOutputStream(File(System.getProperty("user.dir"), "../preview/robo-v171-settings-full.png")).use { full.compress(Bitmap.CompressFormat.PNG, 90, it) }
        // all segmented choices are ≥ 72 px tall
        val segs = all(root).filter { (it.contentDescription ?: "").toString().startsWith("Размер меню и кнопок: ") }
        assertEquals(3, segs.size); segs.forEach { assertTrue(it.height >= 70) }
        val texts = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        for (t in listOf("Тест громкости", "Очистка памяти", "Цвет из обложки", "Кнопки «Назад» и «Недавние»", "Плавающие кнопки Назад / Домой / Недавние", "Выбрать главный экран"))
            assertTrue(t, texts.contains(t))
        all(root).first { it.contentDescription == "Размер меню и кнопок: Очень крупный" }.performClick(); settle(2)
        assertEquals("xlarge", Prefs.railSize)
    }
}
