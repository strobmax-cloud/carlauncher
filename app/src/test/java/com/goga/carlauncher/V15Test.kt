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
    private fun start(): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); settle(); relayout(c.get().window.decorView); settle()
        return c to c.get()
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

    /** Trip stats are gone for good (1.6); no map views by default, navigator buttons take the space; OSM on: map comes back. */
    @Test fun tripOffReflowAndNavCard() {
        installApps()
        val (ctrl, act) = start()
        val root = act.window.decorView
        val tripWords = setOf("ПОЕЗДКА", "В ПУТИ", "МАКС.", "МАКС", "КМ")
        for (l in Layouts.ALL) {
            Prefs.layout = l.id; act.rebuild(); settle(2); relayout(root); settle(1)
            val t = texts(root)
            assertTrue("${l.id}: no trip captions", t.none { it in tripWords })
            assertTrue("${l.id}: no map tiles", all(root).none { it is MapTileView })
            if (l.id in setOf("cockpit", "widgets", "smart", "sport", "premium", "grand"))
                assertTrue("${l.id}: has one «Навигатор»", t.any { it.startsWith("Навигатор", true) } && t.none { it.equals("Навигатор + музыка", true) })
            if (l.id in setOf("map", "minimal")) assertTrue("${l.id}: route card instead of the map", all(root).any { it is NavCardView })
            // nothing may stick out of the content area, and every shown nav button must have a real size
            for (v in all(root)) if (v is TextView && v.isShown && v.text.toString().startsWith("Навигатор")) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                assertTrue("${l.id}: nav button inside screen", loc[0] >= 0 && loc[1] >= 0 && loc[0] + v.width <= 1280 && loc[1] + v.height <= 720)
                assertTrue("${l.id}: nav button visible size", v.width > 40 && v.height > 10)
            }
        }
        // cockpit: the nav row fills the place of the trip cards (bottom edge = media column bottom)
        Prefs.layout = "cockpit"; act.rebuild(); settle(2); relayout(root)
        Prefs.showOsmMap = true
        for (id in listOf("map", "minimal")) {
            Prefs.layout = id; act.rebuild(); settle(2); relayout(root)
            assertTrue("$id: OSM map back when enabled", all(root).any { it is MapTileView })
        }
        ctrl.pause().stop().destroy()
    }

    @Test fun navButtonsAndSplit() {
        installApps()
        Prefs.layout = "cockpit"
        val (ctrl, act) = start()
        val root = act.window.decorView
        while (shadowOf(act).nextStartedActivity != null) {}
        fun clickNav() { all(root).filterIsInstance<TextView>().first { it.isShown && it.text.toString().startsWith("Навигатор · ") }.let { (it.parent as View).performClick() }; shadowOf(Looper.getMainLooper()).idle() }
        fun drain(): List<Intent> { val l = ArrayList<Intent>(); var i = shadowOf(act).nextStartedActivity; while (i != null) { l.add(i); i = shadowOf(act).nextStartedActivity }; return l }
        // 1.7.0: exactly one «Навигатор» entry (default navigator), no extra «Я.Навигатор»/«Навигатор + музыка» tiles
        val navTexts = texts(root).filter { it.startsWith("Навигатор") || it == "Я.Навигатор" || it == "2ГИС" }
        println("nav entries: $navTexts")
        assertTrue("no «Навигатор + музыка»", navTexts.none { it.equals("Навигатор + музыка", true) })
        Prefs.navDefault = "ru.dublgis.dgismobile"; act.rebuild(); settle(2); relayout(root); drain()
        Prefs.navMode = "mini"; clickNav()
        assertTrue("default navigator launched", "ru.dublgis.dgismobile" in drain().map { it.component?.packageName })
        Prefs.navMode = "split"; clickNav()
        val started = drain()
        val pk = started.map { it.component?.packageName }
        println("split started: $pk")
        assertTrue("navigator launched", "ru.dublgis.dgismobile" in pk)
        assertTrue("music launched", "ru.yandex.music" in pk)
        val navI = started.first { it.component?.packageName == "ru.dublgis.dgismobile" }
        assertTrue("navigator asked to go adjacent", navI.flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT != 0)
        Prefs.navMode = "mini"
        // tile «Навигатор» uses the same path; default navigator wins for the generic card
        assertEquals("ru.dublgis.dgismobile", Nav.defaultNav(act))
        Prefs.splitMusic = "com.google.android.youtube"; assertEquals("com.google.android.youtube", Nav.musicApp(act, "ru.yandex.music"))
        Prefs.splitMusic = ""; assertEquals("ru.yandex.music", Nav.musicApp(act, "ru.yandex.music"))
        ctrl.pause().stop().destroy()
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

    /** Upload window content + the mini player over a navigator mock, rendered for the preview images. */
    @Test fun renderUploadAndMiniPlayer() {
        installApps()
        Prefs.layout = "cockpit"
        val (ctrl, act) = start()
        repeat(3) { Gallery.addBytes(ctx, jpeg(1600, 900)) }
        val content = UploadDialog(act) {}.buildContent(8080, listOf("192.168.43.57", "10.10.0.2"))
        val frame = android.widget.FrameLayout(act).apply { background = Ui.round(0xFF1C1C1E.toInt(), 28); setPadding(0, 0, 0, 0) }
        frame.addView(content)
        frame.measure(View.MeasureSpec.makeMeasureSpec(1120, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(660, View.MeasureSpec.EXACTLY)); frame.layout(0, 0, 1120, 660)
        settle(1)
        val b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); act.window.decorView.draw(cv); cv.drawColor(0xB0000000.toInt())
        cv.save(); cv.translate(80f, 30f); frame.draw(cv); cv.restore()
        save(b, "upload")

        // mini player: service with overlay permission + media access
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", ComponentName(ctx, MediaListener::class.java).flattenToString())
        assertTrue(MiniPlayer.allowed(ctx))
        val sc = Robolectric.buildService(MiniPlayer::class.java).create().startCommand(0, 1)
        val mp = sc.get()
        assertNotNull(MiniPlayer.instance)
        val root = MiniPlayer::class.java.getDeclaredField("root").apply { isAccessible = true }.get(mp) as View
        fun f(n: String) = MiniPlayer::class.java.getDeclaredField(n).apply { isAccessible = true }.get(mp)
        (f("title") as TextView).text = "Ночная трасса"; (f("artist") as TextView).text = "Демо-исполнитель"
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
        MiniPlayer::class.java.getDeclaredMethod("setArt", Bitmap::class.java).apply { isAccessible = true }.invoke(mp, ArtLoader.scale(cover, 150))
        (f("playIv") as android.widget.ImageView).setImageResource(R.drawable.ic_pause)
        root.visibility = View.VISIBLE
        val nav = NavArtView(act, Themes.current.accent)
        nav.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); nav.layout(0, 0, 1280, 720)
        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val m = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888); val mc = Canvas(m)
        nav.draw(mc)
        val lp = MiniPlayer::class.java.getDeclaredField("lp").apply { isAccessible = true }.get(mp) as android.view.WindowManager.LayoutParams
        println("mini player at ${lp.x},${lp.y} size ${root.measuredWidth}x${root.measuredHeight}")
        assertTrue(root.measuredWidth in 300..700)
        mc.save(); mc.translate(lp.x.toFloat(), lp.y.toFloat()); root.draw(mc); mc.restore()
        save(m, "miniplayer")
        // collapse → round bubble, position kept within the screen
        MiniPlayer::class.java.getDeclaredMethod("setCollapsed", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(mp, true, true)
        assertTrue(Prefs.miniCollapsed)
        MiniPlayer.setLauncherInFront(true); assertEquals(View.GONE, root.visibility)
        sc.destroy(); assertNull(MiniPlayer.instance)
        ctrl.pause().stop().destroy()
    }

    /** Full settings page as one tall image (to check the new sections). */
    @Test fun renderSettingsFull() {
        installApps()
        repeat(2) { Gallery.addBytes(ctx, jpeg(1600, 900)) }
        val (ctrl, act) = start()
        act.show(3); settle(2); relayout(act.window.decorView)
        val sv = act.window.decorView.let { all(it).filterIsInstance<android.widget.ScrollView>().first { v -> v.isShown && texts(v).any { t -> t.contains("ВЕРНУТЬ ШТАТНЫЙ ЭКРАН") } } }
        val body = sv.getChildAt(0)
        val t = texts(body)
        for (s in listOf("Загрузить обои с телефона", "Фон при музыке", "Размытая обложка", "Мои обои", "Смена обоев", "Раз в день", "Навигатор по умолчанию",
                "Мини-плеер поверх навигатора", "Показывать карту OSM", "Выбрать главный экран", "Сменить телефон", "Обновить с телефона", "Проверить обновления",
                "GitHub / адрес обновлений", "Подсветка под музыку", "Столбики", "Госномер", "Дорога", "Плитка «Камера»", "Цвет акцента", "Где меню", "Кнопки громкости", "ВЕРНУТЬ ШТАТНЫЙ ЭКРАН")) assertTrue("settings has «$s»", t.any { it.contains(s) })
        assertFalse("no trip stats", t.any { it.contains("поездк") || it.contains("Cobalt") })
        body.measure(View.MeasureSpec.makeMeasureSpec(sv.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
        val b = Bitmap.createBitmap(body.measuredWidth, body.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(0xFF101014.toInt()); body.draw(this) }
        save(b, "settings-full")
        // «С телефона» opens the upload window, closing it stops the server
        all(body).filterIsInstance<TextView>().first { it.text == "Загрузить обои с телефона" }.let { (it.parent.parent as View).performClick() }
        settle(1)
        val d = ShadowDialog.getLatestDialog(); assertNotNull(d); assertTrue(d.isShowing)
        d.dismiss(); settle(1)
        ctrl.pause().stop().destroy()
    }
}
