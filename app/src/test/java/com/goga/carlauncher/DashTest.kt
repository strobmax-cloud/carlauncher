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

}
