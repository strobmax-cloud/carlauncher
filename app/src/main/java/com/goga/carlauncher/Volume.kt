package com.goga.carlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent

/**
 * v1.7.1 volume buttons.
 *
 * Why 1.7.0 did nothing on the TEYES: on FYT units the real volume lives in the vendor service com.syu.ms
 * (it drives the amplifier and keeps re-writing Android's STREAM_MUSIC to its own value), so AudioManager changes are
 * either ignored or immediately overwritten (stackoverflow.com/q/78731714, XDA). The stock volume bar of FYT/TEYES
 * talks to com.syu.ms through its public AIDL «toolkit» (com.syu.ipc), SOUND module 4, command C_VOL = 0 with the
 * payload −1 (up) / −2 (down) — documented in github.com/chrisuthe/7870-Projects (wiki 4 «The IPC framework»,
 * 10 «Beyond climate»; service exported and not permission-gated).
 *
 * Safety: only SOUND/C_VOL is ever sent (the same thing the stock on-screen volume buttons send). Nothing else of the
 * vendor service (MCU, CAN, steering-wheel learning, settings) is touched, steering-wheel keys are not intercepted.
 *
 * Methods (numbered the same in Настройки → «Тест громкости»):
 *  1 syu      — com.syu.ms SOUND C_VOL ±1 step (as the stock TEYES buttons)
 *  2 syuset   — com.syu.ms SOUND C_VOL = current ± 1 (absolute value; needs the read-back of U_VOL)
 *  3 stream   — AudioManager.adjustStreamVolume(STREAM_MUSIC, FLAG_SHOW_UI)
 *  4 suggested— AudioManager.adjustSuggestedStreamVolume(USE_DEFAULT_STREAM_TYPE)
 *  5 adjust   — AudioManager.adjustVolume (what is playing)
 *  6 set      — AudioManager.setStreamVolume(STREAM_MUSIC, current ± 1)
 *  7 key      — AudioManager.dispatchMediaKeyEvent(KEYCODE_VOLUME_UP/DOWN)
 *  8 bcast    — broadcasts other Chinese head units listen to (forfan.intent.action.VOLUMEUP/DN — Junsun/FYT clones)
 * "auto" = 1 if the vendor service is reachable and reacts, otherwise 3 → 4 → 7 (as in 1.7.0).
 */
object Volume {
    class Method(val id: String, val n: Int, val title: String, val desc: String)
    val METHODS = listOf(
        Method("syu", 1, "TEYES/FYT — как штатные кнопки", "Сервис магнитолы com.syu.ms, команда «громче/тише»"),
        Method("syuset", 2, "TEYES/FYT — точное значение", "Тот же сервис: «поставить громкость N ± 1»"),
        Method("stream", 3, "Android: громкость музыки", "adjustStreamVolume(STREAM_MUSIC) + системная шкала"),
        Method("suggested", 4, "Android: активный звук", "adjustSuggestedStreamVolume — что сейчас звучит"),
        Method("adjust", 5, "Android: общая громкость", "adjustVolume"),
        Method("set", 6, "Android: установить уровень", "setStreamVolume(музыка, текущий ± 1)"),
        Method("key", 7, "Клавиша громкости", "Нажатие VOLUME_UP / VOLUME_DOWN через AudioManager"),
        Method("bcast", 8, "Команда другим магнитолам", "forfan.intent.action.VOLUMEUP/VOLUMEDN (Junsun и клоны FYT)")
    )
    val IDS = setOf("auto") + METHODS.map { it.id }
    fun method(id: String) = METHODS.firstOrNull { it.id == id }

    var lastMethod = ""
        private set

    /** One step. Returns the id of the method actually used. */
    fun step(c: Context, up: Boolean, method: String = Prefs.volMethod): String {
        Syu.bind(c)
        lastMethod = when (method) {
            "auto" -> auto(c, up)
            else -> { run(c, method, up); method }
        }
        return lastMethod
    }

    private fun auto(c: Context, up: Boolean): String {
        if (Syu.usable()) { if (run(c, "syu", up)) return "syu" }
        val am = c.getSystemService(AudioManager::class.java) ?: return "none"
        val fixed = try { am.isVolumeFixed } catch (_: Throwable) { false }
        if (fixed) { run(c, "key", up); return "key" }
        val max = music(am).second; val before = music(am).first
        run(c, "stream", up)
        val after = music(am).first
        val atLimit = (up && before >= max) || (!up && before <= 0)
        return if (after != before || atLimit || before < 0) "stream" else { run(c, "suggested", up); "suggested" }
    }

    /** Android music level / max (−1 if unknown). */
    fun music(am: AudioManager?): Pair<Int, Int> = try {
        (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1) to (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0)
    } catch (_: Throwable) { -1 to 0 }

    fun run(c: Context, id: String, up: Boolean): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val ui = AudioManager.FLAG_SHOW_UI
        return try {
            when (id) {
                "syu" -> Syu.vol(if (up) Syu.VOL_UP else Syu.VOL_DOWN)
                "syuset" -> { val v = Syu.level; if (v < 0) Syu.vol(if (up) Syu.VOL_UP else Syu.VOL_DOWN) else Syu.vol((v + if (up) 1 else -1).coerceIn(0, Syu.MAX)) }
                "stream" -> { am!!.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, ui); true }
                "suggested" -> { am!!.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, ui); true }
                "adjust" -> { am!!.adjustVolume(dir, ui); true }
                "set" -> { val (v, max) = music(am); if (v < 0) false else { am!!.setStreamVolume(AudioManager.STREAM_MUSIC, (v + if (up) 1 else -1).coerceIn(0, max), ui); true } }
                "key" -> { MediaHub.key(c, if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN); true }
                "bcast" -> { c.sendBroadcast(Intent(if (up) "forfan.intent.action.VOLUMEUP" else "forfan.intent.action.VOLUMEDN")); true }
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    /** «Музыка Android 7 / 15 · Магнитола 19» for the test screen. */
    fun readout(c: Context): String {
        val (v, max) = music(c.getSystemService(AudioManager::class.java))
        val a = if (v >= 0) "Android (музыка): $v из $max" else "Android: —"
        val s = when {
            Syu.level >= 0 -> "Магнитола (com.syu.ms): ${Syu.level}"
            Syu.connected -> "Магнитола: сервис подключён, уровень пока не пришёл"
            Syu.missing -> "Сервис магнитолы com.syu.ms не найден"
            else -> "Магнитола: подключаюсь…"
        }
        return "$a\n$s"
    }
}

/**
 * Minimal hand-written client of the FYT vendor toolkit (com.syu.ms, AIDL com.syu.ipc). Only module 4 (SOUND):
 * C_VOL (0) write, U_VOL (2) read-back. Binder descriptors / transaction ids from github.com/chrisuthe/7870-Projects.
 */
object Syu {
    const val PKG = "com.syu.ms"
    const val ACTION = "com.syu.ms.toolkit"
    private const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val MODULE = "com.syu.ipc.IRemoteModule"
    private const val CALLBACK = "com.syu.ipc.IModuleCallback"
    const val SOUND = 4; const val C_VOL = 0; const val U_VOL = 2
    const val VOL_UP = -1; const val VOL_DOWN = -2
    const val MAX = 40

    @Volatile var connected = false; private set
    @Volatile var missing = false; private set
    /** Current unit volume reported by the service (−1 = unknown). */
    @Volatile var level = -1; internal set
    @Volatile var levelAt = 0L; private set
    /** Sent at / did the service ever react to our command. */
    @Volatile private var sentAt = 0L
    @Volatile private var silentSends = 0
    private var sound: IBinder? = null
    private var bound = false
    var listener: (() -> Unit)? = null

    /** Usable for "auto": connected and has not ignored several commands in a row. */
    fun usable() = connected && sound != null && (level >= 0 || silentSends < 3)

    private val cb = object : Binder(), IInterface {
        init { attachInterface(this, CALLBACK) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code == 1) {
                data.enforceInterface(CALLBACK)
                val c = data.readInt(); val i = data.createIntArray()
                if (c == U_VOL && i != null && i.isNotEmpty()) {
                    level = i[0]; levelAt = SystemClock.elapsedRealtime(); silentSends = 0
                    AppRepo.main.post { listener?.invoke() }
                }
                reply?.writeNoException(); return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            try {
                val m = b?.let { module(it, SOUND) }
                sound = m; connected = m != null
                if (m != null) register(m)
            } catch (_: Throwable) { connected = false }
            AppRepo.main.post { listener?.invoke() }
        }
        override fun onServiceDisconnected(n: ComponentName?) { connected = false; sound = null; bound = false }
    }

    /** Binds once (cheap; no-op if the unit has no com.syu.ms). */
    fun bind(c: Context) {
        if (bound || missing) return
        val app = c.applicationContext
        val i = Intent(ACTION).setPackage(PKG)
        bound = try { app.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
        if (!bound) {
            // some firmwares rename the package: any app exporting the same action
            val alt = try { app.packageManager.queryIntentServices(Intent(ACTION), 0).firstOrNull()?.serviceInfo } catch (_: Throwable) { null }
            if (alt != null) bound = try { app.bindService(Intent(ACTION).setClassName(alt.packageName, alt.name), conn, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
        }
        if (!bound) missing = true
    }

    private fun module(toolkit: IBinder, id: Int): IBinder? {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(TOOLKIT); d.writeInt(id)
            toolkit.transact(1, d, r, 0); r.readException()
            return r.readStrongBinder()
        } finally { d.recycle(); r.recycle() }
    }

    private fun register(m: IBinder) {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(MODULE); d.writeStrongBinder(cb); d.writeInt(U_VOL); d.writeInt(1)
            m.transact(3, d, r, 0); r.readException()
        } finally { d.recycle(); r.recycle() }
    }

    /** SOUND / C_VOL with one value (−1 up, −2 down, or an absolute level). The only command this app sends. */
    fun vol(v: Int): Boolean {
        val m = sound ?: return false
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(MODULE); d.writeInt(C_VOL); d.writeIntArray(intArrayOf(v)); d.writeFloatArray(null); d.writeStringArray(null)
            m.transact(1, d, r, 0); r.readException()
            // no U_VOL echo within a second for several presses → "auto" falls back to the Android methods
            val now = SystemClock.elapsedRealtime()
            if (levelAt < sentAt && now - sentAt < 1500) silentSends++
            sentAt = now
            true
        } catch (_: Throwable) { false } finally { d.recycle(); r.recycle() }
    }
}
