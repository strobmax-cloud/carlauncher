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
    @Test fun nordicRemovedAndMigrated() {
        assertTrue(Layouts.ALL.none { it.id == "nordic" })
        ctx.getSharedPreferences("cl", 0).edit().putString("layout", "nordic").putBoolean("nordicLight", true).commit()
        assertEquals(Layouts.SCREENS.id, Prefs.layout)   // v1.7.1: retired → stored "cockpit", shown only with «Классические раскладки»
        assertEquals("cockpit", ctx.getSharedPreferences("cl", 0).getString("layout", null))
        assertFalse(ctx.getSharedPreferences("cl", 0).contains("nordicLight"))
        assertEquals("cockpit", Layouts.byId("nordic").id)
    }

}
