package com.goga.carlauncher

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import com.goga.carlauncher.Ui.u

/**
 * v1.7.1 quick panel «Вид» (🎨 in the menu): big buttons for the background while music plays (Обои / Размытая /
 * Чёткая обложка), «Цвет из обложки», day / night and the layout. Every choice applies at once.
 */
object QuickPanel {
    fun show(a: MainActivity) {
        val box = Ui.vbox(a).apply { setPadding(u(28), u(20), u(28), u(12)) }
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setView(android.widget.ScrollView(a).apply { addView(box) })
            .create()
        fun title(t: String) = box.addView(Ui.text(a, 20, Ui.TEXT2, Ui.MEDIUM).apply { text = t }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(14); bottomMargin = u(10) })
        fun seg(opts: List<Pair<String, String>>, cur: String, cb: (String) -> Unit) {
            val row = Ui.hbox(a)
            for ((id, label) in opts) {
                val sel = id == cur
                row.addView(Ui.text(a, 21, if (sel) Color.WHITE else Ui.TEXT, Ui.MEDIUM, 2).apply {
                    text = label; gravity = Gravity.CENTER; contentDescription = label; isClickable = true; setPadding(u(14), 0, u(14), 0)
                    background = Ui.pressable(if (sel) Ui.accent else Ui.SURFACE2, u(22), Ui.PRESSED)
                    setOnClickListener { d.dismiss(); cb(id) }
                }, Ui.lin(0, u(80), 1f).apply { rightMargin = u(12) })
            }
            box.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP))
        }
        title("Фон, когда играет музыка")
        seg(listOf("wall" to "Мои обои", "cover" to "Размытая обложка", "sharp" to "Чёткая обложка"), Prefs.bgMode) {
            Prefs.bgMode = it; Wallpaper.refreshCoverMode()
        }
        title("Цвет интерфейса из обложки")
        seg(listOf("on" to "Из обложки", "off" to "Как в теме"), if (Prefs.coverAccent) "on" else "off") {
            Prefs.coverAccent = it == "on"; a.rebuild()
        }
        title("День / ночь")
        seg(listOf("day" to "☀ День", "night" to "☾ Ночь"), Prefs.dayMode) { if (it != Prefs.dayMode) { Prefs.dayMode = it; a.rebuild() } }
        title("Раскладка: " + Layouts.byId(Prefs.layout).name)
        seg(listOf("prev" to "‹ Предыдущая", "next" to "Следующая ›"), "") { a.stepLayout(if (it == "prev") -1 else 1) }
        box.addView(Ui.pill(a, "Закрыть", Ui.SURFACE) { d.dismiss() }.apply { minHeight = u(80) }, Ui.lin(Ui.MATCH, u(80)).apply { topMargin = u(22); rightMargin = u(12) })
        d.window?.setWindowAnimations(0); d.show()
    }
    @Suppress("unused") private fun unused(l: LinearLayout) {}
}
