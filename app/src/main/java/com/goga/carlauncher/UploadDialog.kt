package com.goga.carlauncher

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.goga.carlauncher.Ui.u

/**
 * «Загрузить обои с телефона»: starts [UploadServer] on the unit's Wi-Fi address and shows the URL + QR code.
 * Pictures appear in the gallery strip immediately; the newest one becomes the wallpaper.
 * The server stops when the window is closed or after 10 minutes.
 */
class UploadDialog(private val a: android.app.Activity, private val onClosed: () -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private var server: UploadServer? = null
    private var dialog: Dialog? = null
    private lateinit var strip: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var timer: android.widget.TextView
    private var stopAt = 0L
    private var changed = false

    fun show() {
        val srv = UploadServer(a.applicationContext) { added -> onUpload(added) }
        server = srv
        val ok = srv.start()
        stopAt = System.currentTimeMillis() + UploadServer.TIMEOUT_MS
        val d = Dialog(a, R.style.Dialog)
        d.setContentView(buildContent(if (ok) srv.port else 0, UploadServer.addresses()))
        d.setOnDismissListener { stop(); if (changed) onClosed() }
        d.window?.setWindowAnimations(0)
        d.show()
        d.window?.setLayout(u(1120), u(660))
        dialog = d
        h.post(countdown)
    }

    private val countdown = object : Runnable {
        override fun run() {
            val left = stopAt - System.currentTimeMillis()
            if (left <= 0) { stop(); timer.text = "Сервер выключен (прошло 10 минут). Закройте и откройте окно снова."; return }
            val sec = left / 1000
            timer.text = "Приём открыт ещё %d:%02d".format(sec / 60, sec % 60)
            h.postDelayed(this, 1000)
        }
    }

    private fun stop() { h.removeCallbacks(countdown); server?.stop(); server = null }

    private fun onUpload(added: String?) {
        changed = true
        if (added != null) {
            Prefs.wallpaper = added
            onClosed()
            status.text = "Получено: ${server?.received ?: 0}"
        }
        fillStrip()
    }

    /** Separate from [show] so the preview test can render it. port = 0 → server failed. */
    fun buildContent(port: Int, addrs: List<String>): View {
        val root = Ui.vbox(a).apply { setPadding(u(28), u(24), u(28), u(20)) }
        val top = Ui.hbox(a).apply { gravity = Gravity.TOP }
        val url = addrs.firstOrNull()?.let { "http://$it:$port" }
        val qrBox = FrameLayout(a).apply { background = Ui.round(Color.WHITE, u(20)); setPadding(u(14), u(14), u(14), u(14)) }
        if (url != null && port > 0) qrBox.addView(QrView(a, url), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        else qrBox.addView(Ui.text(a, 20, 0xFF3A3A3C.toInt(), Ui.MEDIUM, 4).apply { text = "Нет сети Wi-Fi"; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        top.addView(qrBox, Ui.lin(u(330), u(330)))
        val col = Ui.vbox(a).apply { setPadding(u(32), u(4), 0, 0) }
        col.addView(Ui.text(a, 34, Ui.TEXT, Ui.BOLD).apply { text = "С телефона по Wi‑Fi" })
        fun step(n: String, t: String) {
            val r = Ui.hbox(a).apply { gravity = Gravity.TOP }
            r.addView(Ui.text(a, 18, Color.WHITE, Ui.BOLD).apply { text = n; gravity = Gravity.CENTER; background = Ui.oval(Ui.accent) }, Ui.lin(u(32), u(32)))
            r.addView(Ui.text(a, 20, Ui.alpha(Color.WHITE, 0.85f), Ui.SANS, 3).apply { text = t; setPadding(u(14), u(4), 0, 0) }, Ui.lin(0, Ui.WRAP, 1f))
            col.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(16) })
        }
        step("1", "Телефон и магнитола в одной сети Wi-Fi. Магнитола подключена к раздаче интернета с телефона — так тоже работает.")
        step("2", "Наведите камеру телефона на QR-код или откройте в браузере:")
        col.addView(Ui.text(a, 34, Ui.accent, Ui.BOLD).apply { text = url ?: "— подключите Wi-Fi —"; setTextIsSelectable(false) }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(10); leftMargin = u(46) })
        if (addrs.size > 1) col.addView(Ui.text(a, 15, Ui.TEXT2, Ui.SANS, 2).apply { text = "Если не открывается: " + addrs.drop(1).joinToString("  ·  ") { "http://$it:$port" } },
            Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4); leftMargin = u(46) })
        step("3", "На телефоне выберите файл CarLauncher-….apk — магнитола проверит, что это ваша сборка (та же подпись), и предложит установить.")
        status = Ui.text(a, 18, 0xFF30D158.toInt(), Ui.MEDIUM).apply { text = if (port > 0) "Жду файл…" else "Не удалось открыть порт — перезапустите окно" }
        col.addView(status, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(16); leftMargin = u(46) })
        timer = Ui.text(a, 15, Ui.TEXT2).apply { text = "Приём открыт 10:00 — потом сервер выключится сам" }
        col.addView(timer, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4); leftMargin = u(46) })
        top.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        root.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))

        val hdr = Ui.hbox(a)
        hdr.addView(Ui.text(a, 17, Ui.alpha(Color.WHITE, 0.75f), Ui.BOLD).apply { text = "" }, Ui.lin(0, Ui.WRAP, 1f))
        hdr.addView(Ui.pill(a, "Готово", Ui.accent) { dialog?.dismiss() }.apply { minHeight = u(56) })
        root.addView(hdr, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(18) })
        strip = Ui.hbox(a).apply { gravity = Gravity.TOP }
        // v1.7.7: no wallpaper gallery here any more (the new screen has its own «Фон»); photos sent from the phone are only stored
        fillStrip()
        return root
    }

    private fun fillStrip() {
        strip.removeAllViews()
        val ids = Gallery.ids(a).reversed()
        if (ids.isEmpty()) { strip.addView(Ui.text(a, 18, Ui.TEXT2).apply { text = "Пока пусто — загрузите фото с телефона" }, Ui.lin(Ui.WRAP, u(110))); return }
        for (id in ids) {
            val sel = Prefs.wallpaper == id
            val f = FrameLayout(a).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = u(14).toFloat(); if (sel) setStroke(u(4), Ui.accent) }
                setPadding(u(4), u(4), u(4), u(4)); isClickable = true
                setOnClickListener { Prefs.wallpaper = id; changed = true; onClosed(); fillStrip() }
            }
            val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(Wallpaper.thumb(a, id)); Ui.clipRound(this, u(10)) }
            f.addView(iv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            val del = Ui.text(a, 18, Color.WHITE, Ui.BOLD).apply { text = "✕"; gravity = Gravity.CENTER; background = Ui.pressableOval(0x99000000.toInt(), 0xCC000000.toInt()); isClickable = true
                setOnClickListener { Gallery.delete(a, id); changed = true; onClosed(); fillStrip() } }
            f.addView(del, FrameLayout.LayoutParams(u(40), u(40), Gravity.TOP or Gravity.END).apply { setMargins(0, u(6), u(6), 0) })
            strip.addView(f, Ui.lin(u(196), u(110)).apply { rightMargin = u(12) })
        }
    }
}
