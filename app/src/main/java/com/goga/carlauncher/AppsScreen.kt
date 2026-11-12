package com.goga.carlauncher

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ui.u

class AppsScreen(a: MainActivity) : Screen(a) {
    private var all: List<AppRepo.App> = emptyList()
    private var shown: List<AppRepo.App> = emptyList()
    private lateinit var search: EditText
    private lateinit var countTv: TextView
    private lateinit var grid: GridView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? LinearLayout) ?: Ui.vbox(a).apply {
                gravity = Gravity.CENTER
                Ui.card(this, blur = false, press = true)
                layoutParams = AbsListView.LayoutParams(Ui.MATCH, u(164))
                addView(ImageView(a), Ui.lin(u(76), u(76)))
                addView(Ui.text(a, 19, Ui.TEXT, Ui.MEDIUM, 2).apply { gravity = Gravity.CENTER },
                    Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10); leftMargin = u(8); rightMargin = u(8) })
            }
            val app = shown[p]
            AppRepo.bindIcon(a, app.pkg, app.cls, cell.getChildAt(0) as ImageView)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }

    override val view: View by lazy { build() }

    private fun build(): View {
        val root = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), 0) }
        val top = Ui.hbox(a)
        val sbox = Ui.hbox(a).apply { setPadding(u(24), 0, u(24), 0) }
        Ui.card(sbox, blur = false, radius = 36)
        sbox.addView(Ui.icon(a, R.drawable.ic_search, Ui.TEXT2), Ui.lin(u(32), u(32)))
        search = EditText(a).apply {
            hint = "Поиск приложений"
            setHintTextColor(Ui.TEXT3); setTextColor(Ui.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(26).toFloat())
            background = null; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        sbox.addView(search, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(14) })
        sbox.addView(Ui.text(a, 22, Ui.TEXT2, Ui.MEDIUM).apply {
            text = "✕"; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { search.setText(""); hideKb() }
        }, Ui.lin(u(56), u(72)))
        top.addView(sbox, Ui.lin(0, u(72), 1f))
        countTv = Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 2).apply { gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { showHidden = !showHidden; filter() } }
        top.addView(countTv, Ui.lin(u(150), Ui.WRAP))
        root.addView(top, Ui.lin(Ui.MATCH, u(72)))

        grid = GridView(a).apply {
            numColumns = 6
            verticalSpacing = u(14); horizontalSpacing = u(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            setPadding(0, u(16), 0, u(20)); clipToPadding = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = this@AppsScreen.adapter
            setOnItemClickListener { _, _, p, _ -> hideKb(); AppRepo.launch(a, shown[p].pkg, shown[p].cls) }
            setOnItemLongClickListener { _, _, p, _ -> appMenu(shown[p]); true }
        }
        root.addView(grid, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(4) })
        return root
    }

    /** v1.7.0 long press: hide from this list / app info / add to the side menu. */
    private fun appMenu(app: AppRepo.App) {
        val hidden = app.pkg in Prefs.hiddenApps
        val opts = arrayOf(if (hidden) "Вернуть в список" else "Скрыть из списка", "Добавить в боковое меню", "О приложении (Android)")
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(app.label)
            .setItems(opts) { _, w -> when (w) {
                0 -> { Prefs.hiddenApps = if (hidden) Prefs.hiddenApps - app.pkg else Prefs.hiddenApps + app.pkg; filter() }
                1 -> { Rail.add(Rail.APP + app.pkg); a.rebuild() }
                else -> AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
            } }.setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }
    private var showHidden = false

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val hid = Prefs.hiddenApps
        val base = if (showHidden || q.isNotEmpty()) all else all.filter { it.pkg !in hid }
        shown = if (q.isEmpty()) base else base.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        countTv.text = "${shown.size} прил." + if (hid.isNotEmpty() && !showHidden && q.isEmpty()) "\n+${hid.size} скрыто" else ""
        adapter.notifyDataSetChanged()
    }

    private fun hideKb() {
        a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun reload() = AppRepo.loadAsync(a) { all = it; filter() }

    override fun onShow() { if (all.isEmpty() || AppRepo.apps == null) reload() }
    override fun onHide() { hideKb() }
    override fun onPackagesChanged() { all = emptyList(); if (a.current == 1) reload() }
}
