package com.goga.carlauncher

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock
import java.util.Locale

/**
 * v1.7.2: the unit's FM tuner controlled from the launcher, without opening the radio app.
 * Same vendor service as the volume (com.syu.ms toolkit), RADIO module 1 and MAIN module 0 — exactly the commands the
 * stock radio app sends when you press its buttons (protocol: github.com/PimpinPumpkin/FytRadio, NOTES.md, verified on
 * UIS7862/7870): seek ±, one step ±, «make the radio the playing source». Nothing else: no settings, no MCU writes.
 * Direct «set frequency» is ignored by the MCU, so a preset is reached by stepping (FREQ_UP / FREQ_DOWN).
 */
object FmCtl {
    const val MOD_MAIN = 0; const val MOD_RADIO = 1
    const val C_NEXT_CHANNEL = 0; const val C_PREV_CHANNEL = 1
    const val C_FREQ_UP = 3; const val C_FREQ_DOWN = 4; const val C_SEEK_UP = 5; const val C_SEEK_DOWN = 6
    const val U_BAND = 0; const val U_FREQ = 1; const val U_PS = 14; const val U_EXTRA = 16
    const val C_APP_ID = 0; const val APP_ID_RADIO = 1; const val APP_ID_CAR_RADIO = 11
    private const val MODULE = "com.syu.ipc.IRemoteModule"
    private const val CALLBACK = "com.syu.ipc.IModuleCallback"

    @Volatile var radio: IBinder? = null; private set
    @Volatile var main: IBinder? = null; private set
    /** current FM frequency in kHz (0 = unknown) and the station name the tuner reports (RDS PS) */
    @Volatile var khz = 0; private set
    @Volatile var ps: String? = null; private set
    @Volatile private var fmUnit = 0          // kHz per MCU unit: 10 (dekahertz, UNISOC) or 1
    @Volatile private var stepKhz = 100
    @Volatile var band = 0; private set       // 0 FM, 1 AM
    var listener: (() -> Unit)? = null

    fun available() = radio != null

    private val cb = object : Binder(), IInterface {
        init { attachInterface(this, CALLBACK) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code == 1) {
                try {
                    data.enforceInterface(CALLBACK)
                    val u = data.readInt(); val i = data.createIntArray(); data.createFloatArray(); val s = data.createStringArray()
                    update(u, i, s)
                } catch (t: Throwable) { CrashLog.soft(t) }
                reply?.writeNoException(); return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /** public for tests */
    fun update(u: Int, i: IntArray?, s: Array<String?>?) {
        when (u) {
            U_FREQ -> {
                val raw = i?.firstOrNull() ?: return
                if (raw <= 0) return
                if (band == 0) { if (fmUnit == 0) fmUnit = if (raw < 20_000) 10 else 1; khz = raw * fmUnit } else khz = raw
                if (band == 0 && khz in 60_000..120_000) Radio.freq = "FM " + String.format(Locale.US, "%.1f", khz / 1000.0)
                ps = null
            }
            U_BAND -> { val b = i?.firstOrNull() ?: return; band = if (b == 1 || b == -2) 1 else 0 }
            U_EXTRA -> { if (i != null && i.size >= 3) { if (i[1] >= 50_000) fmUnit = 1 else if (i[1] >= 2_000) fmUnit = 10; if (band == 0 && i[2] > 0) stepKhz = (i[2] * maxOf(fmUnit, 1)).coerceIn(10, 200) } }
            U_PS -> ps = s?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            else -> return
        }
        AppRepo.main.post { listener?.invoke(); Radio.changed() }
    }

    /** called by [Syu] when the toolkit service connects */
    internal fun onToolkit(tk: IBinder) {
        try {
            radio = Syu.moduleOf(tk, MOD_RADIO)
            main = Syu.moduleOf(tk, MOD_MAIN)
            radio?.let { r -> for (u in intArrayOf(U_BAND, U_FREQ, U_PS, U_EXTRA)) reg(r, u) }
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
    fun reset() { radio = null; main = null; khz = 0; ps = null; fmUnit = 0; stepKhz = 100; band = 0 }

    private fun reg(m: IBinder, u: Int) {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try { d.writeInterfaceToken(MODULE); d.writeStrongBinder(cb); d.writeInt(u); d.writeInt(0); m.transact(3, d, r, 0); r.readException() }
        catch (_: Throwable) {} finally { d.recycle(); r.recycle() }
    }
    private fun cmd(m: IBinder?, c: Int, ints: IntArray? = null): Boolean {
        m ?: return false
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(MODULE); d.writeInt(c); d.writeIntArray(ints); d.writeFloatArray(null); d.writeStringArray(null)
            m.transact(1, d, r, 0); r.readException(); true
        } catch (_: Throwable) { false } finally { d.recycle(); r.recycle() }
    }

    /** radio becomes the playing source (like opening the radio app), the screen stays ours */
    fun activate(): Boolean {
        val m = main ?: return false
        cmd(m, C_APP_ID, intArrayOf(APP_ID_CAR_RADIO))
        return cmd(m, C_APP_ID, intArrayOf(APP_ID_RADIO))
    }
    fun seek(up: Boolean) = cmd(radio, if (up) C_SEEK_UP else C_SEEK_DOWN)
    fun step(up: Boolean) = cmd(radio, if (up) C_FREQ_UP else C_FREQ_DOWN)

    private var stepGen = 0
    /** tune to [targetKhz] by stepping from the current frequency (the MCU ignores a direct «set frequency») */
    fun tuneTo(targetKhz: Int): Boolean {
        if (radio == null) return false
        val from = if (khz > 0) khz else (Stations.mhz(Radio.freq)?.let { Math.round(it * 1000).toInt() } ?: 0)
        if (from <= 0) { return seek(true) }
        val steps = (Math.abs(targetKhz - from) + stepKhz / 2) / stepKhz
        if (steps == 0) return true
        val up = targetKhz > from
        val g = ++stepGen
        val n = steps.coerceAtMost(250)
        var k = 0
        val r = object : Runnable { override fun run() { if (g != stepGen || k++ >= n) return; step(up); AppRepo.main.postDelayed(this, 35) } }
        AppRepo.main.post(r)
        return true
    }
}

/** Presets on the screen: frequency + name, edited by holding a chip («сохранить текущую»). */
object Presets {
    class P(val khz: Int, val name: String)
    val DEFAULT = listOf(P(101_400, "Europa Plus"), P(102_600, "Русское Радио"), P(104_000, "Радио Астана"), P(105_700, "Love Radio"), P(106_500, "Хит FM"), P(107_300, "Шалқар"))
    fun list(): List<P> = try {
        K.radioPresets.takeIf { it.isNotEmpty() }?.split(';')?.mapNotNull { r -> val f = r.split('|'); f.getOrNull(0)?.toIntOrNull()?.let { P(it, f.getOrNull(1) ?: "") } }?.takeIf { it.isNotEmpty() } ?: DEFAULT
    } catch (_: Throwable) { DEFAULT }
    fun save(l: List<P>) { K.radioPresets = l.joinToString(";") { "${it.khz}|${it.name.replace(";", " ").replace("|", " ")}" } }
    fun set(i: Int, p: P) { val l = list().toMutableList(); if (i in l.indices) l[i] = p else l.add(p); save(l) }
    fun mhz(khz: Int) = String.format(Locale.US, "%.1f", khz / 1000.0)
}
