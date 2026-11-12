package com.goga.carlauncher

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.goga.carlauncher.Ui.u

object Pickers {
    private fun row(ctx: Context): LinearLayout = Ui.hbox(ctx).apply {
        setPadding(u(24), u(10), u(24), u(10)); minimumHeight = u(88)
        background = Ui.pressable(0, 0)
        addView(ImageView(ctx), Ui.lin(u(60), u(60)))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, 24, Ui.TEXT, Ui.MEDIUM))
        col.addView(Ui.text(ctx, 17, Ui.TEXT2).apply { setPadding(0, u(4), 0, 0) })
        addView(col, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(20) })
    }

    private fun dialog(ctx: Context, title: String, list: View): AlertDialog.Builder =
        AlertDialog.Builder(ctx, R.style.Dialog).setTitle(title).setView(list)

    private fun showSized(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setWindowAnimations(0)
        d.show()
        d.window?.setLayout(u(820), u(640))
        return d
    }

    /** App picker used to reassign a home tile. */
    fun pickApp(ctx: Context, tile: Int, onDone: () -> Unit) {
        AppRepo.loadAsync(ctx) { apps ->
            val lv = ListView(ctx).apply { divider = null; isVerticalScrollBarEnabled = true }
            var dlg: AlertDialog? = null
            lv.adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(p: Int) = apps[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                    val r = (cv as? LinearLayout) ?: row(ctx)
                    val a = apps[p]
                    AppRepo.bindIcon(ctx, a.pkg, a.cls, r.getChildAt(0) as ImageView)
                    val col = r.getChildAt(1) as LinearLayout
                    (col.getChildAt(0) as TextView).text = a.label
                    (col.getChildAt(1) as TextView).text = a.pkg
                    return r
                }
            }
            lv.setOnItemClickListener { _, _, p, _ ->
                Prefs.setTileCustom(tile, apps[p].pkg); dlg?.dismiss(); onDone()
            }
            dlg = showSized(dialog(ctx, "Плитка «${Tiles.ALL[tile].title}»: выберите приложение", lv)
                .setNeutralButton("По умолчанию") { _, _ -> Prefs.setTileCustom(tile, null); onDone() }
                .setNegativeButton("Отмена", null))
        }
    }

    /** Lists installed packages (FYT/system first) so the user can find exact package names. */
    fun packages(ctx: Context) {
        val pm = ctx.packageManager
        val all = pm.getInstalledApplications(0).map {
            Triple(it.loadLabel(pm).toString(), it.packageName, (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }.sortedWith(compareBy({ !it.second.startsWith("com.syu") && !it.second.startsWith("com.fyt") }, { !it.third }, { it.second }))
        val lv = ListView(ctx).apply { divider = null }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = all.size
            override fun getItem(p: Int) = all[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = (cv as? LinearLayout) ?: row(ctx)
                val (label, pkg, sys) = all[p]
                AppRepo.bindIcon(ctx, pkg, null, r.getChildAt(0) as ImageView)
                val col = r.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = label
                (col.getChildAt(1) as TextView).text = pkg + if (sys) "  · системное" else ""
                return r
            }
        }
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        lv.setOnItemClickListener { _, _, p, _ ->
            cm.setPrimaryClip(ClipData.newPlainText("pkg", all[p].second))
            Toast.makeText(ctx, "Скопировано: ${all[p].second}", Toast.LENGTH_SHORT).show()
        }
        showSized(dialog(ctx, "Пакеты (${all.size}) — нажмите, чтобы скопировать", lv)
            .setNeutralButton("Копировать всё") { _, _ ->
                cm.setPrimaryClip(ClipData.newPlainText("pkgs", all.joinToString("\n") { "${it.second}\t${it.first}" }))
                Toast.makeText(ctx, "Список скопирован", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null))
    }

    @Suppress("unused") private val g = Gravity.CENTER
}
