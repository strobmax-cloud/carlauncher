package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.PlaybackState
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
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

/** v1.7.0: media session choice, BT phone, single navigator, side menu / dock / volume, accents, settings scroll, GitHub updates. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V170Test {
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
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v170-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        val ai = android.content.pm.ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label }
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val cn = android.content.ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }
    private fun teyesApps() {
        listOf("ru.dublgis.dgismobile" to "2ГИС", "ru.yandex.yandexnavi" to "Яндекс Навигатор", "ru.yandex.music" to "Яндекс Музыка",
            "com.teyes.camrec.app" to "Cam REC", "com.teyes.dvr.app" to "DVR", "com.teyes.hddvr" to "HD видеорегистратор",
            "com.teyes.bt.app" to "Bluetooth", "com.teyes.fm" to "Радио", "com.google.android.youtube" to "YouTube",
            "com.liskovsoft.smarttubetv.beta" to "SmartTube").forEach { fakeApp(it.first, it.second) }
        AppRepo.invalidate()
    }
    private fun onScreen(root: View, v: View): Boolean {
        val p = IntArray(2); v.getLocationInWindow(p)
        return v.isShown && v.width > 0 && p[0] >= 0 && p[1] >= 0 && p[0] + v.width <= root.width && p[1] + v.height <= root.height
    }

    // ---------------- pure logic ----------------
    @Test fun sessionChoice() {
        val P = PlaybackState.STATE_PLAYING; val S = PlaybackState.STATE_PAUSED; val N = PlaybackState.STATE_NONE
        fun i(p: String, st: Int, title: Boolean = true, lp: Long = 0) = MediaHub.Info(p, st, title, lp)
        // Spotify (paused, no title) listed first, Yandex starts playing → Yandex
        assertEquals(1, MediaHub.choose(listOf(i("spotify", S, false), i("yandex", P, true, 5))))
        // two playing → the one that started last
        assertEquals(0, MediaHub.choose(listOf(i("bt", P, true, 20), i("yandex", P, true, 10))))
        // nothing plays → most recently played
        assertEquals(2, MediaHub.choose(listOf(i("spotify", S, false), i("radio", S, true, 3), i("yandex", S, true, 9))))
        // nothing ever played → one with a title, not the empty Spotify
        assertEquals(1, MediaHub.choose(listOf(i("spotify", N, false), i("yandex", S, true))))
        // pinned by hand wins while nothing else plays
        assertEquals(0, MediaHub.choose(listOf(i("radio", S), i("yandex", S, true, 9)), pinned = "radio"))
        assertEquals(1, MediaHub.choose(listOf(i("radio", S), i("yandex", P, true, 9)), pinned = "radio"))
        assertEquals(-1, MediaHub.choose(emptyList()))
    }

    @Test fun bluetoothPhoneBroadcasts() {
        val avrcp = android.content.Intent(BtMeta.AVRCP_TRACK)
            .putExtra(BtMeta.AVRCP_META, android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Трек с телефона")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Артист").build())
            .putExtra(BtMeta.AVRCP_PLAY, PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        assertTrue(BtMeta.parse(avrcp))
        assertEquals("Трек с телефона", BtMeta.title); assertTrue(BtMeta.playing && BtMeta.phone && BtMeta.fresh())
        assertTrue("phone shown when no unit session plays", MediaHub.phoneActive())
        val gen = android.content.Intent("com.android.music.metachanged").putExtra("track", "Песня").putExtra("artist", "Кто-то").putExtra("playing", false)
        assertTrue(BtMeta.parse(gen)); assertEquals("Песня", BtMeta.title); assertTrue(!BtMeta.playing)
        assertTrue(!BtMeta.parse(android.content.Intent("com.android.music.metachanged")))
        assertTrue(MediaHub.isPhone(ctx, "com.android.bluetooth") && MediaHub.isPhone(ctx, "com.syu.bt") && !MediaHub.isPhone(ctx, "ru.yandex.music"))
    }

    @Test fun githubUpdates() {
        assertEquals("https://api.github.com/repos/goga/carlauncher/releases/latest", Updater.githubApi("goga/carlauncher"))
        assertEquals("https://api.github.com/repos/goga/carlauncher/releases/latest", Updater.githubApi("https://github.com/goga/carlauncher.git"))
        assertEquals("https://api.github.com/repos/goga/car-launcher/releases/latest", Updater.githubApi("github.com/goga/car-launcher/releases"))
        assertEquals(null, Updater.githubApi("https://example.com/update.json"))
        assertTrue(Updater.compareNames("1.7.1", "1.7.0") > 0 && Updater.compareNames("v1.10", "1.9.9") > 0 && Updater.compareNames("1.7", "1.7.0") == 0)
        val sha = "a".repeat(64)
        val json = """{"tag_name":"v1.7.1","name":"1.7.1","body":"Исправления\nversionCode: 12",
            "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                      {"name":"CarLauncher-v1.7.1.apk","browser_download_url":"https://github.com/goga/carlauncher/releases/download/v1.7.1/CarLauncher-v1.7.1.apk","digest":"sha256:$sha"}]}"""
        val m = Updater.parseGithub(json, "1.7.0", 11)
        assertEquals(12L, m.versionCode); assertEquals("1.7.1", m.versionName); assertEquals(sha, m.sha256); assertTrue(m.apk.endsWith(".apk"))
        val noCode = Updater.parseGithub(json.replace("versionCode: 12", ""), "1.7.0", 11)
        assertEquals(12L, noCode.versionCode)
        val same = Updater.parseGithub(json.replace("versionCode: 12", "").replace("v1.7.1", "v1.7.0"), "1.7.0", 11)
        assertEquals(11L, same.versionCode)
    }

    /** Owner chose SmartTube: tile «YouTube» opens SmartTube when installed, YouTube otherwise; the setting overrides. */
    @Test fun smartTubeDefault() {
        fakeApp("com.google.android.youtube", "YouTube")
        assertEquals("com.google.android.youtube", YouTube.pkg(ctx))
        fakeApp("org.smarttube.stable", "SmartTube")
        assertEquals("org.smarttube.stable", YouTube.pkg(ctx))
        assertEquals("org.smarttube.stable", Tiles.resolve(ctx, 5).pkg)
        Prefs.ytApp = "com.google.android.youtube"; assertEquals("com.google.android.youtube", YouTube.pkg(ctx))
        Prefs.ytApp = ""
    }
}
