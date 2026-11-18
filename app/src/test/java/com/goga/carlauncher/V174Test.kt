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
import android.widget.ImageView

/** v1.7.4: full-screen cover, clean video picker with previews, the «Фон» tab, videos really used as background. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V174Test {
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
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-174-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun cover() = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
    private fun open(style: String, screen: String, big: Boolean = false, music: Boolean = true): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        if (music) { BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо" }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        if (music) {
            Cockpit::class.java.getDeclaredMethod("setArt", Bitmap::class.java).apply { isAccessible = true }.invoke(a.ck, cover())
            settle(6); relayout(root); settle()
        }
        return a to root
    }

    // ---------------- 1. cover over the whole screen ----------------
    @Test fun coverFillsTheWholeScreen() {
        val b = BgView.coverBitmap(cover(), false, 100, 1280, 720)
        assertEquals(1280, b.width); assertEquals(720, b.height)
        for ((x, y) in listOf(5 to 360, 1275 to 360, 640 to 200, 640 to 500)) {
            val c = b.getPixel(x, y)
            assertTrue("($x,$y) not empty: ${Integer.toHexString(c)}", android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c) > 30)
        }
        BgView.coverBitmap(cover(), true, 80, 1280, 720)   // blurred works too
        for (st in listOf("D", "C")) for (sc in listOf("home", "music")) {
            K.bg = "cover"
            val (a, root) = open(st, sc)
            assertEquals("cover", a.ck.bg.mode())
            shot(root, "cover-$st-$sc")
            a.finish()
        }
    }

    // ---------------- 2. clean video picker ----------------
    @Test fun videoPickerShowsOnlyVideoFoldersWithPreviews() {
        val stick = File(ctx.cacheDir, "stick4").apply { deleteRecursively(); mkdirs() }
        for (sys in listOf("Android/data/x", ".thumbnails", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN", "obb")) { File(stick, sys).mkdirs(); File(stick, "$sys/a.mp4").writeBytes(ByteArray(10)) }
        File(stick, "Пусто").mkdirs(); File(stick, "Документы").mkdirs(); File(stick, "Документы/a.pdf").writeBytes(ByteArray(10))
        File(stick, "Фильмы/дорога").mkdirs(); File(stick, "Фильмы/дорога/road-ночь.mp4").writeBytes(ByteArray(10))
        File(stick, "клип.mp4").writeBytes(ByteArray(10)); File(stick, ".скрытый.mp4").writeBytes(ByteArray(10))
        Videos.clearMemo()
        assertEquals(listOf("Фильмы"), Videos.videoFolders(stick).map { it.name })
        assertEquals(listOf("клип.mp4"), Videos.videoFiles(stick).map { it.name })
        assertTrue(Videos.hasVideos(File(stick, "Фильмы")))
        assertEquals("not a real video → no preview, no crash", null, VThumb.make(File(stick, "клип.mp4").path))
        shadowOf(ctx).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val (a, root) = open("D", "home", music = false)
        a.ck.overlays.folderPicker(stick.path); repeat(10) { settle(1) }; relayout(root); settle()
        val t = texts(root)
        assertTrue("folders: $t", t.contains("Фильмы"))
        for (bad in listOf("Android", "LOST.DIR", "System Volume Information", ".thumbnails", "Пусто", "Документы", "obb")) assertFalse("$bad hidden: $t", t.contains(bad))
        val tile = all(root).first { it.contentDescription == "Клип" }
        assertTrue("preview image in the tile", all(tile).any { it is ImageView })
        shot(root, "picker")
        tile.performClick(); settle()
        assertEquals("video", K.bg); assertTrue(K.video.endsWith("клип.mp4"))
    }

    // ---------------- 3. the «Фон» tab ----------------
    @Test fun fonTabIsMinimal() {
        for (st in listOf("D", "C")) for (big in listOf(false, true)) {
            val (a, root) = open(st, "home", big)
            val tab = all(root).filter { it.contentDescription == "Фон" && it.isShown }
            assertTrue("$st $big: «Фон» tab", tab.isNotEmpty())
            assertFalse(texts(root).contains("Природа"))
            a.ck.show(2); settle(); relayout(root); settle()
            assertTrue(a.ck.fon)
            val t = texts(root)
            assertTrue("$st $big now playing line: $t", t.contains("Ночная трасса") && t.contains("Демо"))
            assertTrue("no cover on «Фон»", all(root).filterIsInstance<ImageView>().none { it.isShown && it.width >= 120 && it.height >= 120 })
            assertTrue("controls: $t", t.containsAll(listOf("Фото", "Видео", "По погоде", "Дождь", "Снег", "Выкл", "Выбрать")))
            assertTrue(t.containsAll(listOf("Главная", "Музыка", "Фон")))
            if (!big) shot(root, "fon-$st")
            // controls hide after a while, a tap brings them back
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(Cockpit.FON_HIDE_MS + 800)); settle(); relayout(root)
            assertFalse("hidden: ${texts(root)}", texts(root).contains("По погоде"))
            if (!big) shot(root, "fon-$st-clean")
            a.ck.fonTouch(); settle(); relayout(root)
            assertTrue(texts(root).contains("По погоде"))
            all(root).filterIsInstance<TextView>().first { it.text == "Дождь" && it.isShown }.performClick(); settle(); relayout(root)
            assertFalse(K.fxAuto); assertEquals("rain", K.fxKind); assertTrue(K.particles)
            all(root).filterIsInstance<TextView>().first { it.text == "Выкл" && it.isShown }.performClick(); settle()
            assertFalse(K.particles)
            K.particles = true; K.fxAuto = true
            a.finish()
        }
    }

    // ---------------- 4. videos really become the background ----------------
    @Test fun videoBackgroundOnFonAndOtherScreens() {
        val dir = File(ctx.cacheDir, "vids").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "road-город.mp4").apply { writeBytes(ByteArray(64)) }
        K.videoDir = dir.path; Videos.scan(ctx); K.video = f.path
        K.fonKind = "video"; K.bg = "theme"
        val (a, root) = open("D", "home")
        assertTrue("home = theme, no video", all(root).none { it is VideoBg })
        a.ck.show(2); settle(); relayout(root); settle()
        assertEquals("video", a.ck.bg.mode())
        val v = all(root).filterIsInstance<VideoBg>()
        assertEquals("video view on «Фон»", 1, v.size); assertEquals(f.path, v[0].file?.path)
        a.ck.show(1); settle(); relayout(root)
        assertTrue("Музыка with a theme → no video", all(root).none { it is VideoBg })
        K.bg = "video"; a.ck.applyBackground(); a.ck.build(); settle(); relayout(root)
        assertEquals(1, all(root).filterIsInstance<VideoBg>().size)
        // «Фото»/«Видео» switch on «Фон» and the photo pick there leaves the other screens alone
        a.ck.show(2); settle(); relayout(root); settle(); a.ck.fonTouch(); settle(); relayout(root)
        all(root).filterIsInstance<TextView>().first { it.text == "Фото" && it.isShown }.performClick(); settle()
        assertEquals("scene", K.fonKind); assertEquals("video", K.bg)
        assertTrue(all(root).none { it is VideoBg })
    }

    // ---------------- 5. RadioDroid: Я.Музыка = Yandex, Радио = FM / интернет chooser ----------------
    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label } })
        val main = android.content.ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(main)
        spm.addIntentFilterForActivity(main, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }
    private fun started(a: MainActivity): String? {
        val i = shadowOf(a).nextStartedActivity ?: shadowOf(ctx).nextStartedActivity
        return i?.component?.packageName ?: i?.`package`
    }
    @Test fun yandexButtonAndRadioChooser() {
        fakeApp(MediaHub.RADIODROID, "RadioDroid"); fakeApp(MediaHub.YANDEX, "Яндекс Музыка")
        assertFalse(MediaHub.isRadio(ctx, MediaHub.RADIODROID))
        for (st in listOf("D", "C")) {
            K.radioChoice = "ask"
            val (a, root) = open(st, "music", music = false)
            while (started(a) != null) {}
            all(root).first { it.contentDescription == (if (st == "D") "Я.Музыка" else "Яндекс") && it.isShown }.performClick(); settle()
            assertEquals(MediaHub.YANDEX, started(a))
            all(root).first { it.contentDescription == "Радио" && it.isShown }.performClick(); settle(); relayout(root)
            assertTrue(texts(root).toString(), texts(root).contains("FM — радио магнитолы")); assertTrue(texts(root).contains("Интернет-радио — RadioDroid"))
            if (st == "D") shot(root, "radio-chooser")
            all(root).first { it.contentDescription == "Интернет-радио" && it.isShown }.performClick(); settle()
            assertEquals(MediaHub.RADIODROID, started(a)); assertEquals("ask", K.radioChoice)
            // remember FM → next tap does not ask; long press (= ask) shows the chooser again
            a.ck.radioChoose(true); settle(); relayout(root)
            all(root).filterIsInstance<W.Toggle>().last { it.isShown }.performClick(); settle()
            all(root).first { it.contentDescription == "FM" && it.isShown }.performClick(); settle(); relayout(root)
            assertEquals("fm", K.radioChoice)
            all(root).first { it.contentDescription == "Радио" && it.isShown }.performClick(); settle(); relayout(root)
            assertFalse(texts(root).contains("Интернет-радио — RadioDroid"))
            a.finish()
        }
    }
    @Test fun noRadioDroidMeansNoChooser() {
        val (a, root) = open("D", "music", music = false)
        all(root).first { it.contentDescription == "Радио" && it.isShown }.performClick(); settle(); relayout(root)
        assertFalse(texts(root).contains("FM — радио магнитолы"))
        a.finish()
    }
}
