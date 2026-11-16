package com.goga.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * 1) renders every layout / theme to PNG, 2) clicks every clickable view on every screen and layout
 * (with and without the target apps installed), 3) config change / recreate, 4) onTrimMemory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class RenderTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** Prefs is a process-wide singleton; give every test fresh, empty preferences. */
    @org.junit.Before fun freshPrefs() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        Prefs.dayMode = "night"   // deterministic: "auto" would depend on the clock
        Prefs.roadMode = "night"; Prefs.plate = "777 ABC 01"   // v1.6.3: deterministic road + example plate in the renders
        MapTileView.trim()
    }

    private fun fakeApp(pkg: String, label: String, home: Boolean = false) {
        val spm = shadowOf(ctx.packageManager)
        val ai = ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label; flags = if (pkg.startsWith("com.syu")) ApplicationInfo.FLAG_SYSTEM else 0 }
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val cn = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        if (home) spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) })
    }

    private fun installApps() {
        listOf("ru.yandex.yandexnavi" to "Яндекс Навигатор", "com.syu.radio" to "Радио", "com.syu.bt" to "Bluetooth",
            "com.syu.dvr" to "DVR", "ru.yandex.music" to "Яндекс Музыка", "org.schabi.newpipe" to "NewPipe",
            "com.ghisler.android.TotalCommander" to "Total Commander", "com.android.settings" to "Настройки",
            "ru.dublgis.dgismobile" to "2ГИС", "com.ovz.carscanner" to "Car Scanner", "com.google.android.youtube" to "YouTube").forEach { fakeApp(it.first, it.second) }
        fakeApp("com.syu.launcher", "Штатный экран", home = true)
    }

    private fun prefillTiles() {
        val src = File(System.getProperty("user.dir"), "../tools/assets/tiles")
        val dst = MapTileView.tileDir(ctx).apply { mkdirs() }
        src.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.copyTo(File(dst, it.name), true) }
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val out = File(System.getProperty("user.dir"), "../preview/robo-$name.png")
        out.parentFile!!.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("saved ${out.canonicalPath} ${v.width}x${v.height}")
    }

    private fun settle(n: Int = 6) { repeat(n) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle() } }

    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
    }

    private fun home(act: ClassicActivity): HomeScreen {
        val scr = ClassicActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(act) as Array<*>
        return scr[0] as HomeScreen
    }

    private fun call(home: HomeScreen, name: String, type: Class<*>, arg: Any) =
        HomeScreen::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(home, arg)

    private fun gps(act: ClassicActivity) {
        val l = Location("gps").apply { latitude = MapTileView.DEFAULT_LAT; longitude = MapTileView.DEFAULT_LON; speed = 62 / 3.6f; bearing = 48f; accuracy = 4f; time = System.currentTimeMillis() }
        call(home(act), "onFix", Location::class.java, l)
    }

    private fun playing(act: ClassicActivity, root: View) {
        val home = home(act)
        fun f(n: String) = HomeScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.get(home)
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sample-cover.png"))
        call(home, "setIdle", Boolean::class.javaPrimitiveType!!, false)
        call(home, "showArt", Bitmap::class.java, ArtLoader.scale(cover, 520))
        (f("srcTv") as android.widget.TextView).text = "ЯНДЕКС МУЗЫКА"
        (f("titleTv") as android.widget.TextView).text = "Ночная трасса"
        (f("artistTv") as android.widget.TextView).text = "Демо-исполнитель · Альбом 2026"
        (f("playIv") as android.widget.ImageView).setImageResource(R.drawable.ic_pause)
        (f("progBox") as View).visibility = View.VISIBLE
        (f("posTv") as android.widget.TextView).text = "1:24"
        (f("durTv") as android.widget.TextView).text = "−2:31"
        (f("progBar") as BarView).fraction = 0.36f
        gps(act)
        settle(); relayout(root); settle(3)
        // let gauge needles finish easing (each draw moves 35 % of the way)
        val scratch = Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))
        repeat(14) { root.draw(scratch) }
    }

    private fun start(): Pair<ActivityController<ClassicActivity>, ClassicActivity> {
        val ctrl = Robolectric.buildActivity(ClassicActivity::class.java).setup()
        val act = ctrl.get()
        settle(); relayout(act.window.decorView); settle()
        return ctrl to act
    }

    @Test fun renderLayoutsAndThemes() {
        installApps(); prefillTiles()
        val (ctrl, act) = start()
        val root = act.window.decorView
        Prefs.theme = "glass"
        for (l in Layouts.ALL) {
            Prefs.layout = l.id
            act.rebuild(); settle(); relayout(root); settle(); relayout(root)
            assertEquals(l.id, home(act).layoutId)
            shot(root, "layout-${l.id}-idle")
            playing(act, root)
            shot(root, "layout-${l.id}")
        }
        // every theme in one layout each, so colours × arrangements are covered
        val pairs = listOf("aurora" to "cockpit", "bento" to "widgets", "synth" to "arc", "city" to "media", "bento" to "tiles", "synth" to "map",
            "synth" to "sport", "aurora" to "smart", "city" to "premium", "bento" to "minimal", "city" to "grand")
        for ((t, l) in pairs) {
            Prefs.theme = t; Prefs.layout = l
            act.rebuild(); settle(); relayout(root); settle(); playing(act, root)
            shot(root, "theme-$t-$l")
        }
        Prefs.theme = "glass"
        // v1.6.1: day look of every classic layout
        Prefs.dayMode = "day"
        for (l in Layouts.ALL.filter { LightPass.applies(it.id) }) {
            Prefs.layout = l.id; act.rebuild(); settle(); relayout(root); settle(); playing(act, root); shot(root, "layout-${l.id}-light")
        }
        Prefs.dayMode = "night"
        // v1.6 dashboards in day and night, plus accent variants
        for (id in listOf("cluster", "chrono", "radial", "showcase")) for (m in listOf("day", "night")) {
            Prefs.dayMode = m; Prefs.layout = id; act.rebuild(); settle(); relayout(root); settle(); playing(act, root); shot(root, "layout-$id-$m")
        }
        for (acc in listOf("blue", "gold")) { Prefs.dashAccent = acc; Prefs.dayMode = "night"; Prefs.layout = "radial"; act.rebuild(); settle(); relayout(root); settle(); playing(act, root); shot(root, "layout-radial-$acc") }
        Prefs.dashAccent = "gold"; Prefs.dayMode = "day"; Prefs.layout = "chrono"; act.rebuild(); settle(); relayout(root); settle(); playing(act, root); shot(root, "layout-chrono-gold-day")
        Prefs.dashAccent = ""; Prefs.dayMode = "night"
        Prefs.theme = "glass"; Prefs.layout = "cockpit"; act.rebuild(); settle(); relayout(root)
        for ((i, n) in listOf(1 to "apps", 2 to "dvr", 3 to "settings")) { act.show(i); settle(); relayout(root); settle(); shot(root, n) }
        act.show(0)
        ctrl.pause().stop().destroy()
    }

    /** Clicks (and long-clicks) every clickable view in the given screen; dialogs are dismissed. */
    private fun clickAll(act: ClassicActivity, screen: Int, long: Boolean): Int {
        val root = act.window.decorView
        act.show(screen); settle(2); relayout(root)
        val content = (root as ViewGroup)
        val list = ArrayList<View>()
        fun walk(v: View) {
            if (v.isShown && (v.isClickable || (long && v.isLongClickable))) list.add(v)
            if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(content)
        var n = 0
        for (v in list) {
            if (act.current != screen) { act.show(screen); settle(1) }
            if (v.isClickable) { v.performClick(); n++ }
            if (long && v.isLongClickable) { v.performLongClick(); n++ }
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getLatestDialog()?.let { if (it.isShowing) it.dismiss() }
        }
        settle(2)
        return n
    }

    private fun exerciseEverything(tag: String) {
        val (ctrl, act) = start()
        var clicks = 0
        val startedBefore = shadowOf(act).nextStartedActivity.let { 0 }
        for (l in Layouts.ALL) {
            Prefs.layout = l.id; act.rebuild(); settle(2)
            clicks += clickAll(act, 0, long = true)
            for (rail in 0..3) { act.show(rail); settle(1) }
        }
        Prefs.layout = "cockpit"; act.rebuild(); settle(2)
        clicks += clickAll(act, 1, long = false)
        clicks += clickAll(act, 2, long = false)
        // settings: every row, toggle, theme card, layout card, wallpaper card (rebuilds the activity UI many times)
        clicks += clickAll(act, 3, long = false)
        // all layout/theme switches through the Settings thumbnails
        for (l in Layouts.ALL) { Prefs.layout = l.id; act.rebuild(); settle(1); act.show(0); settle(1) }
        for (t in Themes.ALL) { Prefs.theme = t.id; act.rebuild(); settle(1) }
        var started = 0
        while (shadowOf(act).nextStartedActivity != null) started++
        println("[$tag] clicks=$clicks startedActivities=$started (before=$startedBefore)")
        assertTrue(clicks > 100)
        ctrl.pause().stop().destroy()
    }

    @Test fun clickEverythingWithApps() { installApps(); prefillTiles(); exerciseEverything("apps installed") }

    @Test fun clickEverythingWithoutApps() { exerciseEverything("no apps installed") }

    @Test fun configChangeAndRecreate() {
        installApps(); 
        for (l in Layouts.ALL) {
            Prefs.layout = l.id
            val (ctrl, act) = start()
            val cfg = Configuration(act.resources.configuration).apply { screenWidthDp = 1024; screenHeightDp = 600; orientation = Configuration.ORIENTATION_LANDSCAPE }
            ctrl.configurationChange(cfg); settle(2)
            ctrl.recreate(); settle(2)
            val a2 = ctrl.get()
            assertNotNull(a2.window.decorView)
            assertEquals(l.id, home(a2).layoutId)
            ctrl.pause().stop().destroy()
        }
    }

    @Test fun trimMemoryReleasesBitmaps() {
        installApps(); prefillTiles(); 
        Prefs.layout = "map"; Prefs.showOsmMap = true
        val (ctrl, act) = start()
        val root = act.window.decorView
        settle(4); relayout(root)
        root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)))   // triggers tile loads
        settle(8)
        assertNotNull("wallpaper decoded", Wallpaper.full)
        assertTrue("map tiles cached", MapTileView.memBytes() > 0)
        act.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN); settle(1)
        assertEquals(0, MapTileView.memBytes())
        assertEquals(0, AppRepo.iconCount())
        assertNotNull("wallpaper kept on UI_HIDDEN", Wallpaper.full)
        act.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE); settle(1)
        assertNull(Wallpaper.full); assertNull(Wallpaper.blur)
        ctrl.pause().stop(); settle(1)
        ctrl.start().resume(); settle(8)
        assertNotNull("wallpaper reloaded on start", Wallpaper.full)
        ctrl.pause().stop().destroy()
    }

    /** v1.4 brand layouts: every app entry launches its app, media buttons + "all apps" + nav are wired. */
    @Test fun brandLayoutsWiring() {
        installApps(); prefillTiles()
        val (ctrl, act) = start()
        val root = act.window.decorView
        for (l in Layouts.ALL.filter { it.brand }) {
            Prefs.layout = l.id; act.rebuild(); settle(2); relayout(root)
            while (shadowOf(act).nextStartedActivity != null) {}
            val clickable = ArrayList<View>()
            fun walk(v: View) { if (v.isShown && v.isClickable) clickable.add(v); if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k)) }
            walk(root)
            val pkgs = HashSet<String>()
            var toApps = 0
            for (v in clickable) {
                if (act.current != 0) { act.show(0); settle(1) }
                v.performClick(); shadowOf(Looper.getMainLooper()).idle()
                ShadowDialog.getLatestDialog()?.let { if (it.isShowing) it.dismiss() }
                if (act.current == 1) toApps++
                var i = shadowOf(act).nextStartedActivity
                while (i != null) { (i.`package` ?: i.component?.packageName)?.let { pkgs.add(it) }; i = shadowOf(act).nextStartedActivity }
            }
            // «Радиал» has six fixed buttons (Навигатор, Радио, Музыка | Телефон, YouTube, Приложения)
            val expected = Tiles.ALL.indices.filter { Prefs.tileVisible(it) && (l.id != "radial" || it in setOf(0, 1, 2, 4, 5)) }.mapNotNull { Tiles.resolve(act, it).pkg }.filter { it != Tiles.INTERNAL_MEDIA }.toSet()
            println("[${l.id}] clickable=${clickable.size} launched=${pkgs.size}/${expected.size} allApps=$toApps")
            assertTrue("${l.id}: all tiles launch", pkgs.containsAll(expected))
            assertTrue("${l.id}: has 'all apps' entry", toApps >= 1)
            act.show(0); settle(1)
            val hm = home(act)
            fun f(n: String) = HomeScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.get(hm) as View
            assertTrue("${l.id}: media card attached", f("mediaCard").isAttachedToWindow)
            assertTrue("${l.id}: play button attached", f("playIv").isAttachedToWindow)
            act.show(0); settle(1)
        }
        ctrl.pause().stop().destroy()
    }

    @Test fun rollbackFindsStockLauncher() {
        installApps()
        val (ctrl, act) = start()
        act.show(3); settle(2); relayout(act.window.decorView)
        val texts = ArrayList<String>()
        fun walk(v: View) { if (v is android.widget.TextView) texts.add(v.text.toString()); if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k)) }
        walk(act.window.decorView)
        assertTrue(texts.any { it == "Вернуть штатный экран".uppercase() })
        assertTrue("stock launcher listed", texts.any { it.contains("com.syu.launcher") })
        ctrl.pause().stop().destroy()
        @Suppress("UNUSED_VARIABLE") val unused: Class<*> = Activity::class.java
    }
}
