package com.goga.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import org.junit.After
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

/** «Флешка / Медиа»: listing, player choice, VLC / MX resume round trip, «Продолжить просмотр», tile 9. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class MediaTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var stick: File

    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        ctx.getSharedPreferences("resume", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(ctx).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        stick = File(ctx.cacheDir, "usb/1A2B-3C4D").apply { deleteRecursively(); mkdirs() }
        fun mk(p: String, kb: Int = 1) = File(stick, p).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(kb * 1024)) }
        mk("Фильмы/Интерстеллар.2014.1080p.mkv", 900); mk("Фильмы/Дюна.mp4", 700); mk("Фильмы/notes.txt")
        mk("Сериалы/Мандалорец S01E01.mp4", 400); mk("Сериалы/Мандалорец S01E02.mp4", 400); mk("Сериалы/Мандалорец S01E03.mp4", 400)
        mk("Музыка/Кино - Группа крови.mp3", 300); mk("Музыка/Сплин - Выхода нет.flac", 500); mk("Музыка/cover.jpg")
        mk("Дорога на дачу.mp4", 200); mk(".hidden.mp4"); mk("LOST.DIR/x.mp4")
        Media.fixedVolumes = listOf(Media.Vol("USB-флешка", stick, true), Media.Vol("Память магнитолы", File(ctx.cacheDir, "usb"), false))
    }
    @After fun done() { Media.fixedVolumes = null }

    private fun fakePlayer(pkg: String, label: String, activity: String, mime: String) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label } })
        val main = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(main)
        spm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val cn = ComponentName(pkg, activity)
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("content"); addDataType(mime) })
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1280, 720)
    }
    private fun start(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); settle(); relayout(c.get().window.decorView); settle(); return c.get()
    }
    private fun all(v: View, out: ArrayList<View> = ArrayList()): ArrayList<View> { if (v.isShown) out.add(v); if (v is ViewGroup) for (k in 0 until v.childCount) all(v.getChildAt(k), out); return out }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun save(act: Activity, name: String) {
        val root = act.window.decorView; relayout(root)
        val b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
        val f = File(System.getProperty("user.dir"), "../preview/robo-$name.png"); f.parentFile!!.mkdirs()
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; println("saved ${f.canonicalPath}")
    }

    @Test fun listsFoldersThenMediaOfOneKind() {
        val v = Media.list(stick, "video").map { (if (it.dir) "D:" else "F:") + it.file.name }
        assertEquals(listOf("D:Музыка", "D:Сериалы", "D:Фильмы", "F:Дорога на дачу.mp4"), v)     // LOST.DIR / hidden skipped
        val films = Media.list(File(stick, "Фильмы"), "video").map { it.file.name }
        assertEquals(listOf("Дюна.mp4", "Интерстеллар.2014.1080p.mkv"), films)                   // .txt filtered
        assertEquals(listOf("Кино - Группа крови.mp3", "Сплин - Выхода нет.flac"), Media.list(File(stick, "Музыка"), "music").map { it.file.name })
        assertEquals("video/x-matroska", Media.mime("a.MKV")); assertEquals("audio/flac", Media.mime("b.flac"))
    }

    @Test fun positionRules() {
        val p = "/x/a.mp4"
        Media.savePosition(ctx, p, 3_000, 600_000); assertFalse(Media.position(ctx, p)!!.resumable)          // < 5 s → from the start
        Media.savePosition(ctx, p, 125_000, 600_000); assertTrue(Media.position(ctx, p)!!.resumable)
        assertEquals(125_000f / 600_000f, Media.position(ctx, p)!!.fraction, 0.0001f)
        Media.savePosition(ctx, p, 595_000, 600_000); assertTrue(Media.position(ctx, p)!!.finished); assertFalse(Media.position(ctx, p)!!.resumable)
        repeat(Media.MAX_POS + 20) { Media.savePosition(ctx, "/f$it.mp4", 10_000, 20_000, now = it.toLong() + 10) }
        assertTrue(ctx.getSharedPreferences("resume", 0).all.size <= Media.MAX_POS)
    }

    @Test fun vlcResumeRoundTrip() {
        fakePlayer(Media.VLC, "VLC", Media.VLC_PLAYER, "video/*")
        val act = start()
        val f = File(stick, "Фильмы/Дюна.mp4")
        assertEquals(Media.VLC, Media.playerFor(act, "video"))       // auto → VLC
        Media.open(act, f, resume = true)
        var req = shadowOf(act).nextStartedActivityForResult
        assertEquals(Media.REQ_PLAY, req.requestCode)
        assertEquals(ComponentName(Media.VLC, Media.VLC_PLAYER), req.intent.component)
        assertTrue(req.intent.getBooleanExtra("from_start", false))
        assertFalse(req.intent.hasExtra("position"))
        assertTrue(req.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(req.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)            // NEW_TASK would cancel the result
        shadowOf(act).receiveResult(req.intent, Activity.RESULT_OK,
            Intent(Media.VLC_RESULT).putExtra("extra_position", 1_234_000L).putExtra("extra_duration", 9_300_000L).putExtra("extra_uri", req.intent.data.toString()))
        val pos = Media.position(act, f.path)!!
        assertEquals(1_234_000L, pos.pos); assertEquals(9_300_000L, pos.dur)
        assertEquals(f.path, Prefs.lastVideo)
        Media.open(act, f, resume = true)
        req = shadowOf(act).nextStartedActivityForResult
        assertEquals(1_234_000L, req.intent.getLongExtra("position", -1))
        Media.open(act, f, resume = false)
        req = shadowOf(act).nextStartedActivityForResult
        assertFalse(req.intent.hasExtra("position"))
        shadowOf(act).receiveResult(req.intent, Activity.RESULT_CANCELED, null)
        assertEquals(1_234_000L, Media.position(act, f.path)!!.pos)
    }

    @Test fun mxPlayerAndChooserBestEffort() {
        fakePlayer(Media.MX, "MX Player", "com.mxtech.videoplayer.ad.ActivityScreen", "video/*")
        val act = start()
        Prefs.videoPlayer = Media.MX
        val f = File(stick, "Сериалы/Мандалорец S01E02.mp4")
        Media.savePosition(act, f.path, 60_000, 2_400_000)
        Media.open(act, f, resume = true)
        val req = shadowOf(act).nextStartedActivityForResult
        assertEquals(Media.MX, req.intent.`package`); assertTrue(req.intent.getBooleanExtra("return_result", false))
        assertEquals(60_000, req.intent.getIntExtra("position", -1))
        shadowOf(act).receiveResult(req.intent, Activity.RESULT_OK, Intent("com.mxtech.intent.result.VIEW").putExtra("position", 700_000).putExtra("duration", 2_400_000))
        assertEquals(700_000L, Media.position(act, f.path)!!.pos)
        Prefs.videoPlayer = "ask"
        Media.open(act, f, resume = true)
        val ch = shadowOf(act).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CHOOSER, ch.intent.action); assertEquals(Media.REQ_PLAY, ch.requestCode)
    }

    @Test fun screenContinueRowProgressAndTile() {
        fakePlayer(Media.VLC, "VLC", Media.VLC_PLAYER, "video/*")
        val films = File(stick, "Фильмы")
        Media.savePosition(ctx, File(films, "Интерстеллар.2014.1080p.mkv").path, 3_912_000, 10_140_000)
        Media.savePosition(ctx, File(films, "Дюна.mp4").path, 9_200_000, 9_300_000)       // watched
        Prefs.lastVideo = File(films, "Интерстеллар.2014.1080p.mkv").path
        assertFalse(Prefs.tileVisible(8))
        val act = start()
        act.show(MainActivity.MEDIA); settle(4); relayout(act.window.decorView); settle(2)
        var t = texts(act.window.decorView)
        assertTrue(t.toString(), t.contains("ПРОДОЛЖИТЬ ПРОСМОТР"))
        assertTrue(t.contains("Интерстеллар.2014.1080p"))
        assertTrue(t.contains("Фильмы")); assertTrue(t.contains("Дорога на дачу"))
        save(act, "media-root")
        val list = all(act.window.decorView).filterIsInstance<ListView>().first()
        val idx = (0 until list.adapter.count).first { (list.adapter.getItem(it) as? Media.Entry)?.file?.name == "Фильмы" }
        list.performItemClick(null, idx, 0); settle(4); relayout(act.window.decorView); settle(2)
        t = texts(act.window.decorView)
        assertTrue(t.toString(), t.any { it.contains("1:05:12 из 2:49:00") })
        assertTrue(t.any { it.contains("просмотрено") })
        val bars = all(act.window.decorView).filterIsInstance<ProgressLine>().map { it.fraction }
        assertTrue(bars.toString(), bars.any { it > 0.38f && it < 0.39f })
        save(act, "media-folder")
        list.performItemClick(null, 0, 0)
        val req = shadowOf(act).nextStartedActivityForResult
        assertEquals(3_912_000L, req.intent.getLongExtra("position", -1))
        shadowOf(act).receiveResult(req.intent, Activity.RESULT_OK, Intent(Media.VLC_RESULT).putExtra("extra_position", 4_000_000L).putExtra("extra_duration", 10_140_000L))
        settle(2)
        assertTrue(texts(act.window.decorView).any { it.contains("с 1:06:40") })
        Prefs.mediaKind = "music"; act.rebuild(); act.show(MainActivity.MEDIA); settle(4)
        val l2 = all(act.window.decorView).filterIsInstance<ListView>().first()
        val mi = (0 until l2.adapter.count).first { (l2.adapter.getItem(it) as? Media.Entry)?.file?.name == "Музыка" }
        l2.performItemClick(null, mi, 0); settle(4); relayout(act.window.decorView); settle(2)
        assertTrue(texts(act.window.decorView).contains("Кино - Группа крови"))
        save(act, "media-music")
        Prefs.mediaKind = "video"; Prefs.setTileVisible(8, true); Prefs.layout = "cockpit"; act.rebuild(); act.show(0); settle(4); relayout(act.window.decorView); settle()
        val tile = all(act.window.decorView).filterIsInstance<TextView>().first { it.text == "Флешка" && (it.parent as View).hasOnClickListeners() }
        (tile.parent as View).performClick(); settle()
        assertEquals(MainActivity.MEDIA, act.current)
    }
}
