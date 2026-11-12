package com.goga.carlauncher

import android.content.Context
import android.graphics.Color
import android.os.StatFs
import android.view.Gravity
import android.widget.LinearLayout
import com.goga.carlauncher.Ui.u
import java.io.File

/** v1.7.0 free / total space of the unit memory and every USB / SD volume (StatFs; read-only). */
object Storage {
    class Info(val name: String, val path: String, val total: Long, val free: Long, val removable: Boolean) {
        val freeFraction: Float get() = if (total > 0) free.toFloat() / total else 0f
        val low: Boolean get() = total > 0 && freeFraction < LOW
    }
    const val LOW = 0.10f

    fun stat(dir: File): Pair<Long, Long>? = try { val s = StatFs(dir.path); s.totalBytes to s.availableBytes } catch (_: Throwable) { null }

    fun all(c: Context): List<Info> = try {
        Media.volumes(c).mapNotNull { v -> stat(v.dir)?.takeIf { it.first > 0 }?.let { Info(v.name, v.dir.path, it.first, it.second, v.removable) } }
            .distinctBy { it.total to it.free }
    } catch (_: Throwable) { emptyList() }

    fun gb(b: Long): String {
        val g = b / 1_073_741_824.0
        return if (g >= 10) "%.0f ГБ".format(g) else if (g >= 1) "%.1f ГБ".format(g) else "%.0f МБ".format(b / 1_048_576.0)
    }

    /** "USB-флешка (A1B2) 6 %" for the home badge, or null if all volumes have ≥ 10 % free. */
    fun lowWarning(c: Context): String? = all(c).filter { it.low }.takeIf { it.isNotEmpty() }
        ?.joinToString { it.name + " " + Math.round(it.freeFraction * 100) + " %" }

    /** Rows: name, usage bar, "свободно X из Y". */
    fun view(c: Context, list: List<Info> = all(c), fg: Int = Ui.TEXT): LinearLayout {
        val col = Ui.vbox(c)
        if (list.isEmpty()) col.addView(Ui.text(c, 17, Ui.TEXT2).apply { text = "Накопители не найдены" })
        for (i in list) {
            val r = Ui.vbox(c).apply { setPadding(u(4), u(6), u(4), u(8)) }
            val top = Ui.hbox(c)
            top.addView(Ui.text(c, 18, fg, Ui.MEDIUM).apply { text = i.name }, Ui.lin(0, Ui.WRAP, 1f))
            top.addView(Ui.text(c, 16, if (i.low) 0xFFFF453A.toInt() else Ui.TEXT2, Ui.MEDIUM).apply {
                text = "свободно ${gb(i.free)} из ${gb(i.total)}" + if (i.low) "  ⚠" else ""; gravity = Gravity.END })
            r.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))
            val used = 1f - i.freeFraction
            r.addView(BarView(c, if (i.low) 0xFFFF453A.toInt() else if (used > 0.8f) 0xFFFF9F0A.toInt() else Ui.accent, Ui.alpha(Color.WHITE, 0.14f)).apply { fraction = used },
                Ui.lin(Ui.MATCH, u(8)).apply { topMargin = u(8) })
            col.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP))
        }
        return col
    }

    /** One line of cells (name · free / total + bar) — for the Флешка / Записи headers. */
    fun compact(c: Context, list: List<Info>): LinearLayout {
        val row = Ui.hbox(c)
        if (list.isEmpty()) row.addView(Ui.text(c, 16, Ui.TEXT2).apply { text = "Накопители не найдены" })
        list.forEachIndexed { n, i ->
            val cell = Ui.vbox(c)
            cell.addView(Ui.text(c, 15, if (i.low) 0xFFFF453A.toInt() else Ui.TEXT2, Ui.MEDIUM).apply {
                text = (if (i.low) "⚠ " else "") + i.name + " · свободно " + gb(i.free) + " из " + gb(i.total) })
            cell.addView(BarView(c, if (i.low) 0xFFFF453A.toInt() else Ui.accent, Ui.alpha(Color.WHITE, 0.14f)).apply { fraction = 1f - i.freeFraction },
                Ui.lin(Ui.MATCH, u(6)).apply { topMargin = u(6) })
            row.addView(cell, Ui.lin(0, Ui.WRAP, 1f).apply { if (n > 0) leftMargin = u(20) })
        }
        return row
    }
}
