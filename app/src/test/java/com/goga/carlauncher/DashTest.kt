package com.goga.carlauncher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** v1.6: dashboards (day/night, no trip/OBD/map), music glow fallback, vehicle-data plumbing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class DashTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp); Prefs.dayMode = "night"
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1280, 720)
    }
    private fun all(v: View, out: ArrayList<View> = ArrayList()): ArrayList<View> { if (v.isShown) out.add(v); if (v is ViewGroup) for (k in 0 until v.childCount) all(v.getChildAt(k), out); return out }

    @Test fun dashboardsFitAndStayHonest() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); settle(); val act = c.get(); val root = act.window.decorView
        val banned = listOf("ПОЕЗДКА", "В ПУТИ", "МАКС", "ОБ/МИН", "RPM", "ТОПЛИВО", "°C")
        for (id in listOf("cluster", "chrono", "radial", "showcase")) for (m in listOf("day", "night")) {
            Prefs.layout = id; Prefs.dayMode = m; act.rebuild(); settle(2); relayout(root); settle(1)
            assertEquals(m == "day", Dash.light(act))
            val vs = all(root)
            val t = vs.filterIsInstance<TextView>().map { it.text.toString().uppercase() }
            assertTrue("$id: no trip/OBD captions $t", t.none { s -> banned.any { s.contains(it) } })
            assertTrue("$id: no map", vs.none { it is MapTileView })
            if (id != "chrono") assertTrue("$id/$m: speed shows — without GPS", t.any { it == "—" })   // chrono paints it on the dial
            for (v in vs) if (v is TextView && v.text.isNotEmpty() && v.width > 0) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                assertTrue("$id: '${v.text}' inside screen", loc[0] >= 0 && loc[1] >= 0 && loc[0] + v.width <= 1281 && loc[1] + v.height <= 721)
            }
        }
        c.pause().stop().destroy()
    }

    @Test fun glowFallsBackToBreathingAndStops() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); settle(); val act = c.get()
        val g = act.glow!!
        g.setActive(true); g.setPlaying(true); settle(1)
        assertEquals("off by default", "off", g.mode)
        Prefs.musicGlow = true; g.settingsChanged(); settle(2)
        assertEquals("no RECORD_AUDIO → breathing", "breath", g.mode)
        g.setPlaying(false); settle(1); assertEquals("stops without music", "off", g.mode)
        g.setPlaying(true); settle(1); assertEquals("breath", g.mode)
        c.pause(); settle(1); assertEquals("stops when paused", "off", g.mode)
        c.stop().destroy()
    }

    @Test fun vehicleDataIsEmptyWithoutObd() {
        assertNull(Vehicle.source); assertFalse(Vehicle.hasObd())
        assertNull(Vehicle.state.rpm); assertNull(Vehicle.state.coolantC); assertNull(Vehicle.state.volts); assertNull(Vehicle.state.fuelPct)
        var got: VehicleState? = null
        val sink = object : VehicleSink { override fun onVehicle(s: VehicleState) { got = s } }
        Vehicle.sinks.add(sink)
        Vehicle.publish(VehicleState(rpm = 2100, volts = 14.1f, source = "obd")); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2100, got?.rpm); assertTrue(Vehicle.hasObd())
        Vehicle.sinks.remove(sink); Vehicle.publish(VehicleState())
    }

    @Test fun phoneSwitchOpensPairedList() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); settle(); val act = c.get()
        // no FYT BT app → Android Bluetooth settings
        assertEquals("settings", PhoneSwitch.open(act))
        assertEquals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(act).nextStartedActivity.action)
        // FYT BT app with an exported device-list activity → that one
        val spm = shadowOf(ctx.packageManager)
        val ai = android.content.pm.ActivityInfo().apply { name = "com.syu.bt.act.ActBtDevList"; packageName = PhoneSwitch.FYT_BT; exported = true; enabled = true
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = PhoneSwitch.FYT_BT } }
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = PhoneSwitch.FYT_BT; activities = arrayOf(ai)
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = PhoneSwitch.FYT_BT } })
        assertEquals("fyt-devices", PhoneSwitch.open(act))
        assertEquals("com.syu.bt.act.ActBtDevList", shadowOf(act).nextStartedActivity.component?.className)
        // the rail button is there on the home screen
        val btn = all(act.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Сменить\nтелефон" }
        (btn.parent as View).performClick()
        assertEquals("com.syu.bt.act.ActBtDevList", shadowOf(act).nextStartedActivity.component?.className)
        c.pause().stop().destroy()
    }
}
