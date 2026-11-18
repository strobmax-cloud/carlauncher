package com.goga.carlauncher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** v1.5: trip toggle + reflow, navigator card instead of the map, split / mini player, gallery, rotation, phone upload, QR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V15Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp); Prefs.dayMode = "night"
        Gallery.dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label } })
        val cn = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun installApps() {
        listOf("ru.yandex.yandexnavi" to "Яндекс Навигатор", "ru.dublgis.dgismobile" to "2ГИС", "ru.yandex.music" to "Яндекс Музыка",
            "com.google.android.youtube" to "YouTube", "com.syu.radio" to "Радио", "com.syu.bt" to "Bluetooth").forEach { fakeApp(it.first, it.second) }
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View, out: ArrayList<View> = ArrayList()): ArrayList<View> { if (v.isShown) out.add(v); if (v is ViewGroup) for (k in 0 until v.childCount) all(v.getChildAt(k), out); return out }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun save(b: Bitmap, name: String) {
        val f = File(System.getProperty("user.dir"), "../preview/robo-$name.png"); f.parentFile!!.mkdirs()
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; println("saved ${f.canonicalPath}")
    }
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(0xFF2050A0.toInt()); drawCircle(w / 2f, h / 2f, h / 3f, android.graphics.Paint().apply { color = Color.YELLOW }) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    @Test fun defaults() {
        assertFalse("OSM map off by default", Prefs.showOsmMap)
        assertEquals("cover", Prefs.bgMode)
        assertEquals("off", Prefs.rotate)
        // v1.4 «обложка как фон» = off migrates to «Мои обои»
        ctx.getSharedPreferences("cl", 0).edit().putBoolean("coverwp", false).commit()
        assertEquals("wall", Prefs.bgMode); assertFalse(Prefs.coverWallpaper)
    }

    @Test fun galleryImportDownscalesAndDeletes() {
        val id = Gallery.addBytes(ctx, jpeg(3000, 2000))
        assertNotNull(id)
        val f = Gallery.fileFor(ctx, id!!)!!
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        println("stored ${o.outWidth}x${o.outHeight} ${f.length()} B")
        assertTrue(o.outWidth <= 1280 && o.outHeight <= 720 && o.outWidth >= 1200)
        val portrait = Gallery.addBytes(ctx, jpeg(900, 1600))!!
        android.graphics.BitmapFactory.decodeFile(Gallery.fileFor(ctx, portrait)!!.path, o)
        assertTrue("portrait cropped to 16:9 without upscaling", o.outWidth <= 900 && Math.abs(o.outWidth * 9 - o.outHeight * 16) < 40)
        assertNull("not an image", Gallery.addBytes(ctx, "hello".toByteArray()))
        Prefs.wallpaper = id
        assertTrue(Gallery.delete(ctx, id))
        assertEquals("theme", Prefs.wallpaper)
        assertNull(Gallery.fileFor(ctx, "g:../../shared_prefs/cl.xml"))
        // v1.4 custom wallpaper migrates into the gallery
        File(ctx.filesDir, "wallpaper.jpg").writeBytes(jpeg(1280, 720)); Prefs.wallpaper = "custom"
        Gallery.migrate(ctx)
        assertTrue(Prefs.wallpaper.startsWith("g:")); assertNotNull(Gallery.fileFor(ctx, Prefs.wallpaper))
    }

    @Test fun rotationRules() {
        val h = 3_600_000L
        val day1 = java.util.Calendar.getInstance().apply { set(2026, 10, 12, 23, 30) }.timeInMillis
        assertFalse(Gallery.isDue("off", day1, 0, true))
        assertTrue(Gallery.isDue("launch", day1, day1, true)); assertFalse(Gallery.isDue("launch", day1 + 5 * h, day1, false))
        assertFalse(Gallery.isDue("hour", day1 + h - 1000, day1, false)); assertTrue(Gallery.isDue("hour", day1 + h, day1, false))
        assertFalse(Gallery.isDue("day", day1 + 10 * 60_000, day1, false))
        assertTrue("next calendar day", Gallery.isDue("day", day1 + 40 * 60_000, day1, false))
        // pool: own pictures in order, wraps around
        val a = Gallery.addBytes(ctx, jpeg(1400, 800))!!; Thread.sleep(3); val b = Gallery.addBytes(ctx, jpeg(1400, 800))!!
        assertEquals(listOf(a, b), Gallery.pool(ctx))
        Prefs.rotate = "hour"; Prefs.wallpaper = a; Prefs.rotatedAt = 0
        assertTrue(Gallery.maybeRotate(ctx, false, day1)); assertEquals(b, Prefs.wallpaper)
        assertFalse("not yet", Gallery.maybeRotate(ctx, false, day1 + 60_000))
        assertTrue(Gallery.maybeRotate(ctx, false, day1 + h)); assertEquals(a, Prefs.wallpaper)
        // fewer than two own pictures → built-ins join
        Gallery.delete(ctx, b)
        assertTrue(Gallery.pool(ctx).size == Wallpaper.BUILTIN.size + 1)
    }

    @Test fun uploadServerRoundTrip() {
        var added: String? = null
        val srv = UploadServer(ctx) { added = it }
        assertTrue(srv.start())
        try {
            val base = "http://127.0.0.1:${srv.port}"
            val page = URL("$base/").readText()
            assertTrue(page.contains("Обои для магнитолы") && page.contains("multiple"))
            val c = URL("$base/upload").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            val body = jpeg(2400, 1600); c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            assertEquals(200, c.responseCode)
            val json = c.inputStream.bufferedReader().readText()
            settle(1)
            assertNotNull("callback on main thread", added)
            assertTrue(json.contains(added!!))
            assertTrue(URL("$base/list").readText().contains(added!!))
            val img = URL("$base/img?id=" + java.net.URLEncoder.encode(added, "UTF-8")).openConnection() as HttpURLConnection
            assertEquals(200, img.responseCode); assertEquals("image/jpeg", img.contentType)
            val bad = URL("$base/upload").openConnection() as HttpURLConnection
            bad.requestMethod = "POST"; bad.doOutput = true; bad.outputStream.use { it.write("not an image".toByteArray()) }
            assertEquals(415, bad.responseCode)
            val del = URL("$base/delete?id=" + java.net.URLEncoder.encode(added, "UTF-8")).openConnection() as HttpURLConnection
            del.requestMethod = "POST"; assertEquals(200, del.responseCode)
            assertTrue(Gallery.ids(ctx).isEmpty())
            println("addresses on this machine: ${UploadServer.addresses()}")
        } finally { srv.stop() }
        assertFalse(srv.running)
    }

    @Test fun qrRendersAndIsReadable() {
        for (url in listOf("http://192.168.43.57:8080", "http://172.20.10.3:8080", "http://10.0.0.123:8088")) {
            val m = Qr.encode(url)!!
            assertTrue(m.size == 25 || m.size == 29)
        }
        assertEquals("format bits for mask 0 (M)", 0b101010000010010, Qr.formatBits(0))
        val v = QrView(ctx, "http://192.168.43.57:8080")
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 400, 400)
        val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)); save(b, "qr")
        assertNull(Qr.encode("x".repeat(200)))
        // every version 1..6 (multi-block interleaving in 4..6) — decoded by OpenCV in tools/check_qr.py
        listOf(8, 20, 35, 50, 70, 100).forEachIndexed { k, n ->
            val txt = "http://192.168.1.1:8080/" + "abcdefghij".repeat(10).take(n)
            val q = QrView(ctx, txt); q.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 400, 400)
            val bb = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888); q.draw(Canvas(bb)); save(bb, "qr-v$k")
            File(System.getProperty("user.dir"), "../preview/robo-qr-v$k.txt").writeText(txt)
        }
    }

}
