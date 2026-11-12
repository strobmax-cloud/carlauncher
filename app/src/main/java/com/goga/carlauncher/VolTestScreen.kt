package com.goga.carlauncher

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goga.carlauncher.Ui.u

/**
 * v1.7.1 «Тест громкости»: one row per method (numbered as in [Volume.METHODS]) with big «−» / «+» buttons and the live
 * readout (Android music level + the unit's own level from com.syu.ms). «Выбрать» remembers the method for the volume
 * buttons; «Авто» goes back to the automatic choice.
 */
class VolTestScreen(a: MainActivity) : Screen(a) {
    private lateinit var readTv: TextView
    private lateinit var lastTv: TextView
    private lateinit var list: LinearLayout
    private val tick = object : Runnable { override fun run() { update(); AppRepo.main.postDelayed(this, 500) } }

    override val view: View by lazy { build() }

    private fun big(t: String, bg: Int, desc: String, f: () -> Unit) = Ui.text(a, 30, Color.WHITE, Ui.BOLD).apply {
        text = t; gravity = Gravity.CENTER; contentDescription = desc; isClickable = true
        background = Ui.pressable(bg, u(22), Ui.PRESSED); setOnClickListener { f() }
    }

    private fun build(): View {
        val sv = ScrollView(a).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        val body = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(28)) }
        sv.addView(body)
        val top = Ui.hbox(a)
        val tcol = Ui.vbox(a)
        tcol.addView(Ui.text(a, 34, Ui.TEXT, Ui.BOLD).apply { text = "Тест громкости" })
        tcol.addView(Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 3).apply {
            text = "Включите музыку. Нажимайте «+» / «−» в каждой строке и слушайте. Какой способ меняет звук — нажмите «Выбрать». Напишите мне номер, который сработал."
        }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        top.addView(tcol, Ui.lin(0, Ui.WRAP, 1f))
        top.addView(Ui.pill(a, "Готово", Ui.SURFACE2) { a.openSettingsKeep() }.apply { minHeight = u(72) }, Ui.lin(Ui.WRAP, u(72)).apply { leftMargin = u(12) })
        body.addView(top)
        val rd = Ui.vbox(a).apply { setPadding(u(20), u(14), u(20), u(14)) }
        Ui.card(rd, blur = false, radius = 18)
        readTv = Ui.text(a, 24, Ui.TEXT, Ui.MEDIUM, 2)
        lastTv = Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 2)
        rd.addView(readTv); rd.addView(lastTv, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(8) })
        body.addView(rd, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(14) })
        list = Ui.vbox(a)
        body.addView(list, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        fill()
        return sv
    }

    private fun fill() {
        list.removeAllViews()
        val cur = Prefs.volMethod
        list.addView(row("auto", "0", "Авто (по умолчанию)", "Сначала TEYES/FYT (1), если магнитола не отвечает — Android (3 → 4 → 7)", cur == "auto"), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        for (m in Volume.METHODS) list.addView(row(m.id, m.n.toString(), m.title, m.desc, cur == m.id), Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
    }

    private fun row(id: String, n: String, title: String, desc: String, sel: Boolean): View {
        val r = Ui.hbox(a).apply { setPadding(u(16), u(12), u(14), u(12)); minimumHeight = u(110) }
        Ui.card(r, blur = false, radius = 20)
        if (sel) r.background = Ui.round(Ui.alpha(Ui.accent, 0.22f), u(20))
        r.addView(Ui.text(a, 34, if (sel) Ui.accent else Ui.TEXT, Ui.BOLD).apply { text = n; gravity = Gravity.CENTER; background = Ui.oval(Ui.SURFACE2) }, Ui.lin(u(72), u(72)).apply { rightMargin = u(16) })
        val col = Ui.vbox(a)
        col.addView(Ui.text(a, 23, Ui.TEXT, Ui.MEDIUM).apply { text = title + if (sel) "  ✓ выбран" else "" })
        col.addView(Ui.text(a, 16, Ui.TEXT2, Ui.SANS, 2).apply { text = desc }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4) })
        r.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        r.addView(big("−", Ui.SURFACE2, "Способ $n тише") { press(id, false) }, Ui.lin(u(96), u(80)).apply { leftMargin = u(10) })
        r.addView(big("+", Ui.SURFACE2, "Способ $n громче") { press(id, true) }, Ui.lin(u(96), u(80)).apply { leftMargin = u(10) })
        r.addView(Ui.pill(a, if (sel) "Выбран" else "Выбрать", if (sel) Ui.accent else Ui.SURFACE2) { Prefs.volMethod = id; fill() }.apply { minHeight = u(80) },
            Ui.lin(u(150), u(80)).apply { leftMargin = u(10) })
        return r
    }

    private fun press(id: String, up: Boolean) {
        val used = Volume.step(a, up, id)
        lastTv.text = "Нажато: способ " + (Volume.method(used)?.let { "${it.n} — ${it.title}" } ?: "авто → $used") + (if (up) " · громче" else " · тише")
        AppRepo.main.postDelayed({ update() }, 250)
    }

    private fun update() { if (::readTv.isInitialized) readTv.text = Volume.readout(a) }

    override fun onShow() { Syu.bind(a); Syu.listener = { update() }; update(); AppRepo.main.removeCallbacks(tick); AppRepo.main.post(tick) }
    override fun onHide() { AppRepo.main.removeCallbacks(tick); Syu.listener = null }
}
