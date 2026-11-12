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
    private fun home(a: MainActivity) = (MainActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(a) as Array<*>)[0] as HomeScreen
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v170-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun play(a: MainActivity, src: String = "ЯНДЕКС МУЗЫКА") {
        val h = home(a)
        fun call(n: String, t: Class<*>, arg: Any) = HomeScreen::class.java.getDeclaredMethod(n, t).apply { isAccessible = true }.invoke(h, arg)
        fun f(n: String) = HomeScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.get(h)
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
        call("setIdle", Boolean::class.javaPrimitiveType!!, false)
        call("showArt", Bitmap::class.java, ArtLoader.scale(cover, 520))
        (f("srcTv") as TextView).text = src
        (f("titleTv") as TextView).text = "Ночная трасса"
        (f("artistTv") as TextView).text = "Демо-исполнитель · Альбом 2026"
        (f("progBox") as View).visibility = View.VISIBLE
        (f("progBar") as BarView).fraction = 0.36f
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
    private fun open(layout: String, w: Int = 1280, h: Int = 720): Pair<MainActivity, View> {
        Prefs.layout = layout
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        settle(); relayout(root, w, h); settle(); relayout(root, w, h); settle(); play(a); settle(2); relayout(root, w, h); settle(2)
        return a to root
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

    @Test fun railItemsAndDashcamNames() {
        assertEquals(listOf("home", "apps", "media", "dvr", "settings", "layout", "phone", "wifi"), Rail.items())
        Rail.add("camera"); Rail.add("app:ru.yandex.music"); Rail.move("camera", -1); Rail.remove("media")
        assertEquals(listOf("home", "apps", "dvr", "settings", "layout", "phone", "camera", "wifi", "app:ru.yandex.music"), Rail.items())
        assertEquals(listOf("home", "wifi"), Rail.parse("home, nonsense,wifi,home"))
        assertTrue(Dvr.looksLikeDashcam("ch0_20261112_083000.mp4") && Dvr.looksLikeDashcam("20261112_083000.ts") && Dvr.looksLikeDashcam("NOR_0012.MP4"))
        assertTrue(!Dvr.looksLikeDashcam("Отпуск.mp4") && !Dvr.looksLikeDashcam("ch0_1.jpg"))
        assertEquals("1.5 ГБ", Storage.gb(1_610_612_736L).replace(',', '.')); assertEquals("58 ГБ", Storage.gb(62_277_025_792L))
        val low = Storage.Info("USB", "/x", 100, 5, true); assertTrue(low.low && !Storage.Info("USB", "/x", 100, 50, true).low)
        assertEquals(0xFFB8E01F.toInt(), Accents.color("lime")); assertEquals(null, Accents.color(""))
    }

    // ---------------- UI ----------------
    @Test fun singleNavigatorEverywhere() {
        teyesApps(); Prefs.navDefault = "ru.dublgis.dgismobile"
        for (l in Layouts.ALL) {
            val (_, root) = open(l.id)
            val texts = all(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            assertTrue("${l.id}: no «Навигатор + музыка»", texts.none { it.contains("Навигатор + музыка", true) || it.contains("НАВИГАТОР + МУЗЫКА") })
            assertTrue("${l.id}: Я.Навигатор is not an extra button", texts.none { it == "Я.Навигатор" || it == "Я.НАВИГАТОР" })
            if (l.id in setOf("minimal", "map")) assertTrue("${l.id}: clean nav card", all(root).any { it is NavCardView && it.isShown })
            assertTrue("${l.id}: no drawn fake map", all(root).none { it is NavArtView })
        }
    }

    @Test fun volumeAlwaysOnScreen() {
        teyesApps()
        for (side in listOf("left", "right", "bottom")) for (vs in listOf("both", "left", "right")) {
            Prefs.railSide = side; Prefs.volSide = vs
            for ((w, h) in listOf(1280 to 720, 1280 to 680, 1024 to 600)) for (l in listOf("premium", "cockpit", "minimal", "showcase")) {
                val (_, root) = open(l, w, h)
                val vols = all(root).filter { it.contentDescription == "Громче" || it.contentDescription == "Тише" }
                val want = if (side == "bottom") (if (vs == "both") 4 else 2) else (if (vs == "both") 4 else 2)
                assertEquals("$side/$vs $l ${w}x$h volume buttons", want, vols.size)
                vols.forEach { assertTrue("$side/$vs $l ${w}x$h: volume fully on screen", onScreen(root, it)) }
                if (side == "left" && vs == "both" && l == "premium" && h == 680) shot(root, "premium-statusbar-680")
            }
        }
        // the premium list (photo 1) scrolls instead of being cut off
        Prefs.railSide = "left"; Prefs.volSide = "both"
        val (_, root) = open("premium")
        assertTrue(all(root).any { it is android.widget.ScrollView && (it.getChildAt(0) as? ViewGroup)?.childCount ?: 0 >= 8 })
    }

    @Test fun renders() {
        teyesApps(); Prefs.navDefault = "ru.dublgis.dgismobile"
        Prefs.railItems = Rail.join(Rail.items() + listOf("bt", "camera", "nav", "music"))
        for ((side, name) in listOf("left" to "rail-left", "right" to "rail-right", "bottom" to "dock-bottom")) {
            Prefs.railSide = side
            val (_, root) = open("showcase"); shot(root, name)
        }
        Prefs.railSide = "left"; Prefs.railItems = Rail.DEFAULT
        for (l in listOf("minimal", "map", "premium", "cluster", "cockpit")) { val (_, root) = open(l); shot(root, "layout-$l") }
        for (acc in listOf("lime", "red", "purple", "cyan", "orange", "yellow")) { Prefs.accent = acc; val (_, root) = open("cluster"); shot(root, "accent-$acc") }
        Prefs.accent = ""
        // phone over Bluetooth without a session
        val (a, root) = open("showcase")
        BtMeta.title = "Трек с телефона"; BtMeta.artist = "Исполнитель"; BtMeta.album = null; BtMeta.playing = true; BtMeta.at = android.os.SystemClock.elapsedRealtime()
        HomeScreen::class.java.getDeclaredMethod("showPhone").apply { isAccessible = true }.invoke(home(a))
        settle(1); relayout(root); settle(1); shot(root, "phone-bt")
        BtMeta.at = 0
    }

    @Test fun settingsKeepScroll() {
        teyesApps()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); val root = a.window.decorView
        settle(); relayout(root, 1280, 720); settle()
        a.openSettings(); settle(); relayout(root); settle()
        val sv = all(root).filterIsInstance<android.widget.ScrollView>().first { it.isShown && (it.getChildAt(0)?.height ?: 0) > 3000 }
        sv.scrollTo(0, 2600); settle(1)
        assertEquals(2600, SettingsScreen.savedScroll)
        // a choice that rebuilds the whole launcher (accent) and one that rebuilds only the page (volume method)
        all(root).first { it.contentDescription == "Акцент Лайм" }.performClick(); settle(); relayout(root); settle()
        val sv2 = all(root).filterIsInstance<android.widget.ScrollView>().first { it.isShown && (it.getChildAt(0)?.height ?: 0) > 3000 }
        assertEquals("scroll kept after a change", 2600, sv2.scrollY)
        // render the settings pages with the new sections
        val body = sv2.getChildAt(0)
        val full = Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888); body.draw(Canvas(full))
        FileOutputStream(File(System.getProperty("user.dir"), "../preview/robo-v170-settings-full.png")).use { full.compress(Bitmap.CompressFormat.PNG, 90, it) }
        shot(root, "settings")
        // opening settings again from the menu starts at the top
        a.show(MainActivity.HOME); a.openSettings(); settle(); assertEquals(0, SettingsScreen.savedScroll)
    }

    @Test fun storageScreens() {
        teyesApps()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); val root = a.window.decorView
        settle(); relayout(root, 1280, 720); settle()
        a.show(MainActivity.DVR); settle(6); relayout(root); settle(2); shot(root, "dvr")
        a.show(MainActivity.MEDIA); settle(6); relayout(root); settle(2); shot(root, "media")
        a.show(MainActivity.APPS); settle(6); relayout(root); settle(2); shot(root, "apps")
    }

    /** Home badge when a volume has < 10 % free (real StatFs in Robolectric is roomy → forced text for the render). */
    @Test fun lowStorageBadge() {
        assertTrue(Storage.Info("USB", "/x", 100, 5, true).low)
        assertTrue(!Storage.Info("USB", "/x", 100, 50, true).low)
        teyesApps()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); val root = a.window.decorView
        settle(); relayout(root, 1280, 720); settle(); play(a)
        MainActivity::class.java.getDeclaredField("lowText").apply { isAccessible = true }.set(a, "USB 6 %")
        MainActivity::class.java.getDeclaredMethod("updateBadge").apply { isAccessible = true }.invoke(a)
        settle(1); relayout(root); settle(1)
        assertTrue("badge shown", all(root).any { it.isShown && it is TextView && it.text.toString().startsWith("⚠ Мало места") })
        shot(root, "low-storage-badge")
        a.show(MainActivity.DVR); settle(2)
        assertTrue("badge only on home", all(root).none { it.isShown && it is TextView && it.text.toString().startsWith("⚠ Мало места") })
        // usage bars with sample volumes (Robolectric has no USB/SD): internal, USB with DVR (low), SD
        val G = 1024L * 1024 * 1024
        val demo = listOf(Storage.Info("Память магнитолы", "/storage/emulated/0", 25 * G, 11 * G, false),
            Storage.Info("USB-флешка", "/storage/USB1", 32 * G, 2 * G, true), Storage.Info("SD-карта", "/storage/SD", 64 * G, 40 * G, true))
        val dvr = (MainActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(a) as Array<*>)[MainActivity.DVR] as DvrScreen
        val box = DvrScreen::class.java.getDeclaredField("storageBox").apply { isAccessible = true }.get(dvr) as android.widget.LinearLayout
        settle(4); box.removeAllViews(); box.addView(Storage.compact(a, demo), Ui.lin(Ui.MATCH, Ui.WRAP))
        settle(1); relayout(root); settle(1); shot(root, "dvr-storage")
        val v = Storage.view(a, demo)
        v.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, 900, v.measuredHeight)
        val b = Bitmap.createBitmap(900, v.measuredHeight, Bitmap.Config.ARGB_8888); Canvas(b).apply { drawColor(0xFF15161B.toInt()); v.draw(this) }
        FileOutputStream(File(System.getProperty("user.dir"), "../preview/robo-v170-storage-settings.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Bug 1.7.0: Я.Музыка paused + radio started → radio is the source, ⏮ ⏭ go to the radio (FYT OEM service), never the paused session. */
    @Test fun radioBeatsPausedSession() {
        val S = MediaHub.Kind.SESSION; val R = MediaHub.Kind.RADIO; val P = MediaHub.Kind.PHONE
        // paused Я.Музыка (started at 100) vs radio started later (200) → radio
        assertEquals(1, MediaHub.pick(listOf(MediaHub.Cand(S, false, 100), MediaHub.Cand(R, true, 200))))
        // paused session that was played AFTER the radio still loses to an active radio? no: radio switched off by the start → inactive
        assertEquals(0, MediaHub.pick(listOf(MediaHub.Cand(S, false, 300), MediaHub.Cand(R, false, 200))))
        // playing session started after radio → session
        assertEquals(0, MediaHub.pick(listOf(MediaHub.Cand(S, true, 300), MediaHub.Cand(R, true, 200))))
        // phone started playing last → phone
        assertEquals(2, MediaHub.pick(listOf(MediaHub.Cand(S, true, 300), MediaHub.Cand(R, false, 200), MediaHub.Cand(P, true, 400))))
        assertEquals(-1, MediaHub.pick(emptyList()))
        // MTC/FYT reports
        assertTrue(Radio.parse(android.content.Intent("com.microntek.radio.report").putExtra("type", "content").putExtra("freq", 101400000)))
        assertEquals("FM 101.4", Radio.freq); assertTrue(Radio.on)
        assertTrue(Radio.parse(android.content.Intent("com.microntek.radio.report").putExtra("type", "power").putExtra("state", false)))
        assertTrue(!Radio.on)
        // launching the radio from the launcher makes it the active source; next station → OEM service
        teyesApps(); fakeApp("com.syu.radio", "Радио")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); val root = a.window.decorView
        settle(); relayout(root, 1280, 720); settle()
        assertTrue(AppRepo.launch(a, "com.syu.radio")); settle(2)
        assertEquals(MediaHub.Kind.RADIO, MediaHub.kind)
        val app = shadowOf(ctx)
        while (app.nextStartedService != null) {}
        MediaHub.command(a, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        val svc = app.nextStartedService
        assertEquals("com.syu.radio.nextservice", svc?.action); assertEquals("com.syu.broadcast", svc?.component?.packageName)
        MediaHub.command(a, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertEquals("com.syu.radio.prevservice", app.nextStartedService?.action)
        Radio.freq = "FM 101.4"; MediaHub.pinRadio("com.syu.radio"); settle(2); a.show(MainActivity.HOME); settle(2); relayout(root); settle(1)
        assertTrue("radio card", all(root).any { it.isShown && it is TextView && it.text.toString() == "FM 101.4" })
        shot(root, "radio-card")
        // picking a session by hand switches the radio off
        MediaHub.pin("ru.yandex.music"); assertTrue(!Radio.on)
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
