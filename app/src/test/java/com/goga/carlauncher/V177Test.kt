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

/** v1.7.7: «−10» / «+10» seek buttons on the floating pill (SmartTube and any player). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V177Test {
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
    private fun relayout(root: View, w: Int = 1280, h: Int = 720) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-177-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** on-screen rect (window px) through the stage scale */
    private fun rect(v: View): Rect { val p = IntArray(2); v.getLocationInWindow(p); var s = 1f; var x: View? = v; while (x != null) { s *= x.scaleX; x = x.parent as? View }
        return Rect(p[0], p[1], p[0] + Math.round(v.width * s), p[1] + Math.round(v.height * s)) }
    private fun open(style: String, screen: String, big: Boolean = false): Pair<MainActivity, View> {
        K.style = style; K.big = big; K.startScreen = screen
        BtMeta.at = android.os.SystemClock.elapsedRealtime(); BtMeta.playing = true; BtMeta.title = "Ночная трасса"; BtMeta.artist = "Демо"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root); settle(); relayout(root); settle()
        return a to root
    }

    private val SEEK = android.media.session.PlaybackState.ACTION_SEEK_TO
    private val FF = android.media.session.PlaybackState.ACTION_FAST_FORWARD or android.media.session.PlaybackState.ACTION_REWIND
    private val PLAY = android.media.session.PlaybackState.STATE_PLAYING
    private val PAUSE = android.media.session.PlaybackState.STATE_PAUSED

    @Test fun choosesThePlayerInFront() {
        val st = "com.liskovsoft.smarttubetv.beta"
        val l = listOf(Triple("ru.yandex.music", PLAY, SEEK), Triple(st, PLAY, SEEK or FF))
        assertEquals("SmartTube in front", 1, Seek.choose(l, st))
        assertEquals("navigator in front → no buttons (music plays in the background)", -1, Seek.choose(l, "ru.yandex.yandexnavi"))
        assertEquals("front unknown → any playing", 0, Seek.choose(l, null))
        assertEquals("paused video in front still seekable", 0, Seek.choose(listOf(Triple(st, PAUSE, SEEK)), st))
        assertEquals("not seekable (live stream / ad) → none", -1, Seek.choose(listOf(Triple(st, PLAY, 0L)), st))
        assertTrue(Seek.isSmartTube(st) && Seek.isSmartTube("org.smarttube.stable") && Seek.isSmartTube("app.smarttube.beta"))
        assertTrue(Seek.isVideoApp("com.google.android.youtube")); assertFalse(Seek.isVideoApp("ru.yandex.yandexnavi"))
    }
    @Test fun positionIsExtrapolatedAndClamped() {
        val now = 100_000L
        val st = android.media.session.PlaybackState.Builder().setState(PLAY, 60_000L, 1f, now - 5_000L).setActions(SEEK).build()
        assertEquals(65_000L, Seek.position(st, now))
        assertEquals(75_000L, Seek.newPosition(st, 600_000L, 10_000L, now))
        assertEquals(55_000L, Seek.newPosition(st, 600_000L, -10_000L, now))
        assertEquals("not past the end", 66_000L - 1_000L, Seek.newPosition(st, 66_000L, 30_000L, now))
        val start = android.media.session.PlaybackState.Builder().setState(PAUSE, 4_000L, 1f, now - 5_000L).build()
        assertEquals("paused: no drift, not below 0", 0L, Seek.newPosition(start, 600_000L, -10_000L, now))
    }
    @Test fun settingsAndSafety() {
        assertTrue(K.seekOn); assertEquals(10, K.seekStep)
        val (a, root) = open("D", "home")
        a.ck.overlays.settings("sysnav"); settle(); relayout(root); settle()
        val t = texts(root).joinToString(" | ")
        assertTrue(t, t.contains("Кнопки перемотки ±10 с")); assertTrue(t, t.contains("Шаг перемотки"))
        a.finish()
        // the service still never sees keys and reads no window content
        val cfg = java.io.File(System.getProperty("user.dir"), "src/main/res/xml/nav_access.xml").readText()
        assertFalse(cfg.contains("FilterKeyEvents")); assertTrue(cfg.contains("canRetrieveWindowContent=\"false\""))
        assertTrue(NavAccess::class.java.declaredMethods.none { it.name == "onKeyEvent" })
    }
    @Test fun pillShowsSeekOnlyForThePlayerInFront() {
        K.floatOn = true; NavAccess.launcherFront(false)
        val svc = Robolectric.buildService(NavAccess::class.java).create().get()
        NavAccess::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(svc); settle()
        val pill = NavAccess::class.java.getDeclaredField("pill").apply { isAccessible = true }.get(svc) as View
        fun seekShown() = all(pill).any { it.isShown && (it.contentDescription ?: "").startsWith("Вперёд на") }
        assertFalse("nothing in front yet", seekShown())
        fun front(p: String) { val e = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); e.packageName = p; svc.onAccessibilityEvent(e); settle() }
        front("com.liskovsoft.smarttubetv.beta")   // no «Доступ к музыке» in the test → SmartTube still gets the buttons (media keys)
        assertTrue("SmartTube in front → −10 / +10", seekShown())
        pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); pill.layout(0, 0, pill.measuredWidth, pill.measuredHeight)
        shot(pill, "pill-seek")
        front("ru.yandex.yandexnavi")
        assertFalse("navigator in front → no seek buttons", seekShown())
        K.seekOn = false; front("com.liskovsoft.smarttubetv.beta")
        assertFalse("setting off", seekShown())
        svc.onDestroy()
    }

    /** v1.7.7: the old 1.7.0 screens are gone — nothing in the app can open them again */
    @Test fun oldScreensAreGone() {
        for (n in listOf("ClassicActivity", "HomeScreen", "SettingsScreen", "MiniPlayer", "FloatNav", "Rail", "QuickPanel")) {
            val found = try { Class.forName("com.goga.carlauncher.$n"); true } catch (_: ClassNotFoundException) { false }
            assertFalse("$n must be removed", found)
        }
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES)
        assertEquals("only one activity", listOf("com.goga.carlauncher.MainActivity"), pi.activities.map { it.name })
        assertEquals("singleTask launcher", android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, pi.activities[0].launchMode)
        assertFalse(pi.services.any { it.name.endsWith("MiniPlayer") || it.name.endsWith("FloatNav") })
        // «Домой» of the floating buttons → the new launcher
        NavKeys.home(ctx)
        val next = shadowOf(ctx).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)

        val (a, root) = open("D", "home")
        for (sec in listOf("about", "mem", "upd", "sysnav", "look", "tiles", "music", "car")) {
            a.ck.overlays.settings(sec); settle(); relayout(root); settle()
            assertFalse("no classic entry in $sec", texts(root).any { it.contains("Классический") || it.contains("Старый экран") })
        }
        a.ck.overlays.settings("mem"); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("Папки и файлы установки"))
        a.ck.overlays.folders(); settle(8); relayout(root); settle()
        assertTrue(texts(root).contains("Папки и файлы установки"))
        assertTrue(shadowOf(a).nextStartedActivity == null)
        shot(root, "folders")
        a.ck.overlays.closeAll()
        a.ck.overlays.settings("upd"); settle(); relayout(root); settle()
        assertTrue(texts(root).contains("С телефона по Wi‑Fi"))
        val d = UploadDialog(a) {}
        val v = d.buildContent(8080, listOf("192.168.43.10"))
        val fr = android.widget.FrameLayout(a); fr.addView(v)
        relayout(fr, 1120, 660); shot(fr, "upload")
        assertTrue(all(fr).filterIsInstance<TextView>().any { it.text.toString().contains("http://192.168.43.10:8080") })
    }
}
