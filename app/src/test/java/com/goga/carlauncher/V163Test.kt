package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

/** v1.6.3: road time of day (independent of the UI), Kazakhstan plate, YouTube card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V163Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp); Prefs.dayMode = "night"
        Radio.on = false; Radio.at = 0; BtMeta.playing = false; BtMeta.at = 0; MediaHub.pin(null)   // singletons survive between test classes
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.width, root.height)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun home(a: ClassicActivity) = (ClassicActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(a) as Array<*>)[0] as HomeScreen
    private fun play(a: ClassicActivity) {
        val h = home(a)
        fun call(n: String, t: Class<*>, arg: Any) = HomeScreen::class.java.getDeclaredMethod(n, t).apply { isAccessible = true }.invoke(h, arg)
        fun f(n: String) = HomeScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.get(h)
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
        call("setIdle", Boolean::class.javaPrimitiveType!!, false)
        call("showArt", Bitmap::class.java, ArtLoader.scale(cover, 520))
        (f("srcTv") as android.widget.TextView).text = "ЯНДЕКС МУЗЫКА"
        (f("titleTv") as android.widget.TextView).text = "Ночная трасса"
        (f("artistTv") as android.widget.TextView).text = "Демо-исполнитель · Альбом 2026"
        (f("progBox") as View).visibility = View.VISIBLE
        (f("progBar") as BarView).fraction = 0.36f
    }
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v163-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun limePixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
            val c = b.getPixel(x, y); val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            if (r > 120 && g > 120 && bl < 90 && kotlin.math.abs(r - g) < 50) n++
        }
        return n
    }

    private fun open(layout: String, ui: String, road: String): Pair<ClassicActivity, View> {
        Prefs.dayMode = ui; Prefs.layout = layout; Prefs.roadMode = road
        val a = Robolectric.buildActivity(ClassicActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); play(a); settle(); relayout(root); settle(2)
        return a to root
    }

    @Test fun roadTimeOfDayIsIndependentFromUi() {
        Prefs.plate = "777 ABC 01"
        for (id in listOf("showcase", "cluster", "smart", "minimal")) {
            val (_, r1) = open(id, "night", "day"); val day = shot(r1, "$id-darkui-dayroad")
            val (_, r2) = open(id, "night", "night"); val night = shot(r2, "$id-darkui-nightroad")
            shot(open(id, "day", "day").second, "$id-lightui-dayroad")
            // the UI stays dark in both (top-left corner = background), only the road brightens
            fun lum(b: android.graphics.Bitmap, x: Int, y: Int): Int { val c = b.getPixel(x, y); return ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3 }
            assertTrue("$id: UI corner stays dark on a day road", lum(day, day.width - 5, day.height - 5) < 90)
            // v1.7.1: sample the road block itself (the car is bigger now and covers more of the screen centre)
            val rv = all(r1).first { it is RoadView && it.isShown }; val p0 = IntArray(2); rv.getLocationInWindow(p0)
            var dsum = 0L; var nsum = 0L
            for (y in p0[1] + rv.height / 8 until p0[1] + rv.height * 7 / 8 step 4) for (x in p0[0] + rv.width / 10 until p0[0] + rv.width * 9 / 10 step 4) { dsum += lum(day, x, y); nsum += lum(night, x, y) }
            assertTrue("$id: day road is brighter than night road ($dsum vs $nsum)", dsum > nsum * 1.05)
        }
    }

    @Test fun sunriseSunsetAstana() {
        val tz = java.util.TimeZone.getTimeZone("GMT+05:00")
        fun at(m: Int, d: Int) = java.util.Calendar.getInstance(tz).apply { set(2026, m, d, 12, 0) }
        val nov = RoadArt.sunTimes(at(10, 12))!!; val jun = RoadArt.sunTimes(at(5, 21))!!
        assertTrue("Nov sunrise ${nov.first}", nov.first in 6.5 * 60..8.5 * 60); assertTrue("Nov sunset ${nov.second}", nov.second in 15.0 * 60..17.0 * 60)
        assertTrue("Jun sunrise ${jun.first}", jun.first in 2.5 * 60..4.5 * 60); assertTrue("Jun sunset ${jun.second}", jun.second in 19.5 * 60..21.0 * 60)
        Prefs.roadMode = "auto"
        assertTrue(RoadArt.isDay(at(10, 12).apply { set(java.util.Calendar.HOUR_OF_DAY, 13) }))
        assertTrue(!RoadArt.isDay(at(10, 12).apply { set(java.util.Calendar.HOUR_OF_DAY, 19) }))
        Prefs.roadMode = "day"; assertTrue(RoadArt.isDay(at(10, 12).apply { set(java.util.Calendar.HOUR_OF_DAY, 23) }))
    }

    @Test fun plateFormatting() {
        org.junit.Assert.assertEquals("777 ABC 01", Plate.normalize("777abc01"))
        org.junit.Assert.assertEquals("123 KZA 01", Plate.normalize(" 123 kza-01 "))
        org.junit.Assert.assertEquals("123 AKE 01", Plate.normalize("123аке01"))   // Cyrillic look-alikes → Latin
        org.junit.Assert.assertEquals("", Plate.normalize("  "))
        org.junit.Assert.assertEquals("777 ABC" to "01", Plate.split("777 ABC 01"))
        Prefs.plate = "001aaa02"; org.junit.Assert.assertEquals("001 AAA 02", Prefs.plate)
        // plate render: white plate with dark characters and a blue flag field
        val b = RoadArt.plateBitmap("777 ABC 01")
        val out = File(System.getProperty("user.dir"), "../preview/robo-v163-plate-flat.png"); FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val flag = b.getPixel(40, 40); assertTrue("blue flag", (flag and 255) > 150 && (flag shr 16 and 255) < 80)
        assertTrue("white plate", b.getPixel(b.width - 30, 30) == android.graphics.Color.WHITE)
    }

    /** 1.7.0: YouTube was removed from the right card (always music now); the setting is gone. */
    @Test fun youtubeCard() {
        Prefs.rightCard = "youtube"
        org.junit.Assert.assertEquals("music", Prefs.rightCard)
    }

    @Test fun spectrumBars() {
        Prefs.musicFx = "bars"
        for (id in listOf("showcase", "media", "radial", "cockpit", "cluster", "premium")) {
            val (_, root) = open(id, "night", "night")
            MusicFx.on = true
            for (b in 0 until MusicFx.BARS) MusicFx.shown[b] = (0.25f + 0.65f * kotlin.math.abs(kotlin.math.sin(b * 0.38f + 0.6f)) * (1f - b / 40f)).coerceIn(0.05f, 1f)
            MusicFx.push(); settle(1)
            assertTrue("$id: bars attached to the cover", MusicFx.live.isNotEmpty())
            shot(root, "bars-$id")
        }
        // engine: no RECORD_AUDIO in tests → calm idle animation, then decays to zero and stops when paused
        val a = Robolectric.buildActivity(ClassicActivity::class.java).setup().get(); settle()
        val g = a.glow!!
        g.settingsChanged(); g.setActive(true); g.setPlaying(true); settle(3)
        org.junit.Assert.assertEquals("barsIdle", g.mode)
        assertTrue("idle bars move", MusicFx.shown.any { it > 0.02f })
        g.setPlaying(false); org.junit.Assert.assertEquals("off", g.mode)
        repeat(45) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40)) }
        assertTrue("bars faded out", !MusicFx.on)
        Prefs.musicFx = "off"
    }
}
