package com.goga.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.widget.Toast

/**
 * v1.7.0 — head-unit apps found by package OR by their visible name (TEYES firmwares use their own package names):
 * rear camera (Cam REC — records / shows the rear camera), DVR (TEYES DVR / «HD видеорегистратор»), Bluetooth.
 */
object CamApps {
    val CAM_PKGS = listOf("com.syu.camera360", "com.syu.camera", "com.syu.carcamera", "com.spd.camrec", "com.teyes.camrec")
    val CAM_LABELS = listOf("cam rec", "camrec", "камера заднего", "rear cam", "камера", "camera 360")
    val DVR_PKGS = listOf("com.syu.dvr", "com.teyes.dvr")
    val DVR_LABELS = listOf("dvr", "hd видеорегистратор", "видеорегистратор", "регистратор", "dashcam")
    val BT_PKGS = listOf("com.syu.bt", "com.syu.btphone")
    val BT_LABELS = listOf("bluetooth", "блютуз", "телефон")
    val BTMUSIC_LABELS = listOf("bt музыка", "bt music", "bluetooth музыка", "bluetooth music", "музыка bt")

    /** Launcher apps (label, pkg), cached by AppRepo. */
    private fun apps(c: Context): List<AppRepo.App> = AppRepo.apps ?: AppRepo.load(c).also { AppRepo.apps = it }

    /** First installed of [pkgs], else first app whose label equals / starts with / contains one of [labels] (in order). */
    fun find(c: Context, pkgs: List<String>, labels: List<String>, exclude: Set<String> = emptySet()): String? {
        pkgs.firstOrNull { it !in exclude && Tiles.installed(c, it) }?.let { return it }
        val l = try { apps(c) } catch (_: Throwable) { emptyList() }
        for (want in labels) {
            l.firstOrNull { it.pkg !in exclude && it.label.equals(want, true) }?.let { return it.pkg }
        }
        for (want in labels) {
            l.firstOrNull { it.pkg !in exclude && it.label.lowercase().contains(want) }?.let { return it.pkg }
        }
        return null
    }

    fun camera(c: Context): String? = Prefs.camApp.takeIf { it.isNotEmpty() && Tiles.installed(c, it) } ?: find(c, CAM_PKGS, CAM_LABELS)
    fun dvr(c: Context): String? = Prefs.dvrApp.takeIf { it.isNotEmpty() && Tiles.installed(c, it) } ?: find(c, DVR_PKGS, DVR_LABELS)
    fun bt(c: Context): String? = find(c, BT_PKGS, BT_LABELS)
    fun btMusic(c: Context): String? = find(c, emptyList(), BTMUSIC_LABELS) ?: bt(c)

    fun openCamera(a: Activity) {
        val p = camera(a)
        if (p == null) Toast.makeText(a, "Приложение камеры не найдено — выберите его в Настройках → «Камера»", Toast.LENGTH_LONG).show()
        else AppRepo.launch(a, p)
    }
    fun openDvr(a: Activity) {
        val p = dvr(a)
        if (p == null) Toast.makeText(a, "Приложение регистратора не найдено — выберите его в Настройках → «Камера»", Toast.LENGTH_LONG).show()
        else AppRepo.launch(a, p)
    }

    /** Camera2 devices visible to ordinary apps: "id · facing". AV-in / AHD reverse cameras of FYT/TEYES units usually are NOT listed. */
    fun cameras(c: Context): List<Pair<String, String>> = try {
        val cm = c.getSystemService(CameraManager::class.java)
        cm.cameraIdList.map { id ->
            val f = try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) } catch (_: Throwable) { null }
            id to when (f) { CameraCharacteristics.LENS_FACING_BACK -> "задняя"; CameraCharacteristics.LENS_FACING_FRONT -> "передняя"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "внешняя (USB/AV)"; else -> "неизвестно" }
        }
    } catch (_: Throwable) { emptyList() }
}

/**
 * Experimental live picture (Settings → «Живая картинка камеры», off by default). Opens the chosen Camera2 device only
 * while the home screen is visible and closes it in onHide. If the device is busy / missing it shows nothing and the tile
 * just opens the camera app. WARNING shown in Settings: grabbing a camera may pause another app's recording (Cam REC).
 */
class LiveCamView(c: Context) : TextureView(c), TextureView.SurfaceTextureListener {
    private val h = Handler(Looper.getMainLooper())
    private var dev: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var wanted = false
    var onState: ((Boolean) -> Unit)? = null
    init { surfaceTextureListener = this }

    fun startPreview() { wanted = true; if (isAvailable) open() }
    fun stopPreview() { wanted = false; close() }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (dev != null || !wanted) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { onState?.invoke(false); return }
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        val id = Prefs.camId.takeIf { it.isNotEmpty() } ?: try { cm.cameraIdList.firstOrNull() } catch (_: Throwable) { null }
        if (id == null) { onState?.invoke(false); return }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!wanted) { d.close(); return }
                    dev = d
                    val st = surfaceTexture ?: return
                    st.setDefaultBufferSize(1280, 720)
                    val s = Surface(st)
                    try {
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                try {
                                    val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(s) }.build()
                                    cs.setRepeatingRequest(rq, null, h); onState?.invoke(true)
                                } catch (_: Throwable) { onState?.invoke(false) }
                            }
                            override fun onConfigureFailed(cs: CameraCaptureSession) { onState?.invoke(false) }
                        }, h)
                    } catch (_: Throwable) { onState?.invoke(false) }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); dev = null; onState?.invoke(false) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); dev = null; onState?.invoke(false) }
            }, h)
        } catch (_: Throwable) { onState?.invoke(false) }
    }

    private fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { dev?.close() } catch (_: Throwable) {}
        session = null; dev = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, hh: Int) { if (wanted) open() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, hh: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { close(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    override fun onDetachedFromWindow() { stopPreview(); super.onDetachedFromWindow() }
}
