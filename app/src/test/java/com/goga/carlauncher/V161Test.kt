package com.goga.carlauncher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.drawable.VectorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V161Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp); Prefs.dayMode = "night"
        AppRepo.invalidate()
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label } })
        val cn = ComponentName(pkg, "$pkg.Main"); spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun start(): MainActivity { val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); settle(); relayout(a.window.decorView); settle(); return a }

    @Test fun nordicRemovedAndMigrated() {
        assertTrue(Layouts.ALL.none { it.id == "nordic" })
        ctx.getSharedPreferences("cl", 0).edit().putString("layout", "nordic").putBoolean("nordicLight", true).commit()
        assertEquals("cockpit", Prefs.layout)
        assertEquals("cockpit", ctx.getSharedPreferences("cl", 0).getString("layout", null))
        assertFalse(ctx.getSharedPreferences("cl", 0).contains("nordicLight"))
        assertEquals("cockpit", Layouts.byId("nordic").id)
    }

    @Test fun genericIconsGetBundledArt() {
        for ((p, l) in listOf("ru.yandex.yandexnavi" to "Навигатор", "ru.yandex.music" to "Музыка", "org.schabi.newpipe" to "NewPipe",
                "com.ghisler.android.TotalCommander" to "TC", "ru.dublgis.dgismobile" to "2ГИС", "com.apple.android.music" to "Apple Music")) fakeApp(p, l)
        for (id in listOf("cockpit", "smart", "minimal", "grand")) {
            Prefs.layout = id
            val a = start(); val root = a.window.decorView
            settle(6); relayout(root)
            val icons = all(root).filterIsInstance<ImageView>().filter { (it.tag as? String)?.contains("/") == true }
            assertTrue("$id: app icons present", icons.isNotEmpty())
            for (iv in icons) assertTrue("$id: ${iv.tag} uses bundled art, got ${iv.drawable}", iv.drawable is VectorDrawable)
        }
        assertNotEquals(0, Tiles.brand("ru.yandex.music")); assertNotEquals(0, Tiles.brand("com.google.android.youtube"))
    }

    @Test fun prevNextAndDayNightButtons() {
        Prefs.layout = "cockpit"
        val a = start(); val root = a.window.decorView
        val prev = all(root).first { it.contentDescription == "Предыдущая раскладка" }
        val next = all(root).first { it.contentDescription == "Следующая раскладка" }
        val dn = all(root).first { it.contentDescription == "День / ночь" }
        assertTrue(prev.isShown && next.isShown && dn.isShown)
        val loc = IntArray(2); dn.getLocationInWindow(loc); assertTrue("inside screen", loc[1] + dn.height <= 720 && loc[1] > 300)
        next.performClick(); settle(); assertEquals(Layouts.ALL[1].id, Prefs.layout)
        all(a.window.decorView).first { it.contentDescription == "Предыдущая раскладка" }.performClick(); settle(); assertEquals("cockpit", Prefs.layout)
        all(a.window.decorView).first { it.contentDescription == "Предыдущая раскладка" }.performClick(); settle(); assertEquals(Layouts.ALL.last().id, Prefs.layout)
        all(a.window.decorView).first { it.contentDescription == "День / ночь" }.performClick(); settle(); assertEquals("day", Prefs.dayMode)
        a.show(MainActivity.SETTINGS); settle()
        assertFalse(all(a.window.decorView).first { it.contentDescription == "Следующая раскладка" }.isShown)
    }

    @Test fun everyClassicLayoutHasDayLook() {
        Prefs.dayMode = "day"
        for (l in Layouts.ALL) {
            Prefs.layout = l.id
            val a = start(); val root = a.window.decorView
            val ink = all(root).filterIsInstance<TextView>().count { it.isShown && (it.currentTextColor and 0xFFFFFF) in setOf(LightPass.INK and 0xFFFFFF, LightPass.INK2 and 0xFFFFFF) || it.currentTextColor == Dash.fg(true) }
            assertTrue("${l.id}: dark type in day mode ($ink)", ink >= 2)
            if (LightPass.applies(l.id)) {
                val home = MainActivity::class.java.getDeclaredField("screens").apply { isAccessible = true }.get(a) as Array<*>
                assertTrue((home[0] as HomeScreen).light)
            }
        }
        Prefs.dayMode = "night"; Prefs.layout = "cockpit"
        val a = start()
        val white = all(a.window.decorView).filterIsInstance<TextView>().count { it.currentTextColor == Color.WHITE }
        assertTrue("night stays dark", white > 0)
    }
}
