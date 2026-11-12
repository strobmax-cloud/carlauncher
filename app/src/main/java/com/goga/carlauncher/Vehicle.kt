package com.goga.carlauncher

/**
 * Vehicle data plumbing for a future OBD-II module (USB ELM327 through a CH340 / PL2303 / FTDI serial adapter).
 * Nothing here talks to hardware yet: today only GPS speed flows through [SpeedSink]; every OBD value is null and
 * no gauge shows it. A later `ObdSource : VehicleSource` reads PIDs (0C rpm, 05 coolant, 42 / ATRV voltage,
 * 2F fuel level, 0D speed) on its own thread and calls [Vehicle.publish]; widgets that want them implement
 * [VehicleSink] and register in [Vehicle.sinks]. Layouts must keep working (and look complete) with all values null.
 */
data class VehicleState(
    val speedKmh: Int? = null,      // OBD 0D (preferred over GPS when present)
    val rpm: Int? = null,           // 0C
    val coolantC: Int? = null,      // 05
    val volts: Float? = null,       // 42 or ELM "ATRV"
    val fuelPct: Int? = null,       // 2F
    val source: String = "none"     // "none" | "obd"
)

interface VehicleSink { fun onVehicle(s: VehicleState) }

/** A provider of vehicle data. Must be cheap when idle and never block the main thread. */
interface VehicleSource {
    val name: String
    /** Begin polling; push updates with [Vehicle.publish] (≤ 2 Hz is plenty for gauges). */
    fun start(ctx: android.content.Context)
    fun stop()
}

object Vehicle {
    @Volatile var state = VehicleState(); private set
    val sinks = java.util.concurrent.CopyOnWriteArrayList<VehicleSink>()
    /** null = no OBD (current builds). */
    var source: VehicleSource? = null
        set(v) { field?.stop(); field = v }

    fun publish(s: VehicleState) {
        state = s
        AppRepo.main.post { sinks.forEach { it.onVehicle(s) } }
    }
    fun hasObd() = state.source == "obd"
}
