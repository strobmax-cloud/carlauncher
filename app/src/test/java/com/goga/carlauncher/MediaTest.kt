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

}
