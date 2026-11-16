package com.goga.carlauncher

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.StrictMode
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.goga.carlauncher.Ui.u
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class DvrScreen(a: ClassicActivity) : Screen(a) {
    private val ru = Locale("ru")
    private val dayKeyFmt = SimpleDateFormat("yyyyMMdd", ru)
    private val dayFmt = SimpleDateFormat("d MMMM, EEEE", ru)
    private val chipFmt = SimpleDateFormat("d MMM", ru)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", ru)

    private var clips: List<Dvr.Clip> = emptyList()
    private var roots: List<File> = emptyList()
    private var dayFilter: String? = null
    private var items: List<Any> = emptyList()
    private var scanning = false
    private var scanned = false
    private var gen = 0

    private lateinit var statusTv: TextView
    private lateinit var chips: LinearLayout
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var permBtn: View

    private val thumbs = LruCache<String, Bitmap>(80)
    private val thumbExec = Executors.newSingleThreadExecutor()

    class Header(val label: String, val count: Int, val bytes: Long)

    override val view: View by lazy { build() }

    private fun build(): View {
        val root = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), 0) }
        val top = Ui.hbox(a)
        val tcol = Ui.vbox(a)
        tcol.addView(Ui.text(a, 34, Ui.TEXT, Ui.BOLD).apply { text = "Записи регистратора" })
        statusTv = Ui.text(a, 19, Ui.TEXT2)
        tcol.addView(statusTv, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        top.addView(tcol, Ui.lin(0, Ui.WRAP, 1f))
        // v1.7.0: open the DVR app itself (TEYES X5 records to ITS OWN microSD — those clips are only in the DVR app) + own folder
        top.addView(Ui.pill(a, "Открыть регистратор", Ui.accent) { CamApps.openDvr(a) }.apply { minHeight = u(64); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(19).toFloat()) },
            Ui.lin(Ui.WRAP, u(64)).apply { rightMargin = u(12) })
        top.addView(Ui.pill(a, "Папка…", Ui.SURFACE2) { pickFolder() }.apply { minHeight = u(64); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(19).toFloat()) },
            Ui.lin(Ui.WRAP, u(64)).apply { rightMargin = u(12) })
        top.addView(Ui.iconButton(a, R.drawable.ic_refresh, 76, 34, Ui.SURFACE, Ui.TEXT) { scan() })
        root.addView(top)
        storageBox = Ui.vbox(a).apply { setPadding(u(18), u(12), u(18), u(12)) }
        Ui.card(storageBox, blur = false, radius = 18)
        root.addView(storageBox, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })

        chips = Ui.hbox(a)
        val hs = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) }
        root.addView(hs, Ui.lin(Ui.MATCH, u(72)).apply { topMargin = u(16) })

        val frame = android.widget.FrameLayout(a)
        list = ListView(a).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0)
            overScrollMode = View.OVER_SCROLL_NEVER; isFastScrollEnabled = true
            setPadding(0, u(8), 0, u(20)); clipToPadding = false
            adapter = this@DvrScreen.adapter
            setOnItemClickListener { _, _, p, _ -> (items[p] as? Dvr.Clip)?.let { open(it.file) } }
        }
        frame.addView(list)
        empty = Ui.vbox(a).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        empty.addView(Ui.icon(a, R.drawable.ic_videocam, Ui.TEXT3), Ui.lin(u(96), u(96)))
        emptyTv = Ui.text(a, 22, Ui.TEXT2, Ui.SANS, 0).apply { gravity = Gravity.CENTER }
        empty.addView(emptyTv, Ui.lin(u(800), Ui.WRAP).apply { topMargin = u(16) })
        permBtn = Ui.pill(a, "Разрешить доступ к файлам", Ui.accent) { a.requestPerms() }
        empty.addView(permBtn, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(20) })
        frame.addView(empty)
        root.addView(frame, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(8) })
        return root
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Header) 0 else 1
        override fun isEnabled(p: Int) = items[p] !is Header

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val it = items[p]
            if (it is Header) {
                val tv = (cv as? TextView) ?: Ui.text(a, 22, Ui.TEXT, Ui.BOLD).apply {
                    setPadding(u(8), u(22), 0, u(10)); layoutParams = AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
                }
                tv.text = "${it.label}   ·   ${it.count} файл.   ·   ${size(it.bytes)}"
                return tv
            }
            val c = it as Dvr.Clip
            val row = (cv as? LinearLayout) ?: Ui.hbox(a).apply {
                Ui.card(this, blur = false, press = true)
                setPadding(u(12), u(12), u(24), u(12))
                layoutParams = AbsListView.LayoutParams(Ui.MATCH, u(132))
                addView(ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(Ui.SURFACE2, u(14)); Ui.clipRound(this, u(14))
                }, Ui.lin(u(192), u(108)))
                val col = Ui.vbox(a)
                col.addView(Ui.text(a, 30, Ui.TEXT, Ui.BOLD))
                col.addView(Ui.text(a, 18, Ui.TEXT2).apply { setPadding(0, u(8), 0, 0) })
                addView(col, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(24) })
                addView(Ui.text(a, 20, Ui.TEXT2, Ui.MEDIUM).apply { gravity = Gravity.END })
                addView(Ui.icon(a, R.drawable.ic_play, Ui.accent), Ui.lin(u(40), u(40)).apply { leftMargin = u(20) })
            }
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = timeFmt.format(c.time)
            (col.getChildAt(1) as TextView).text = "${c.file.name}  ·  ${c.file.parentFile?.name ?: ""}"
            (row.getChildAt(2) as TextView).text = size(c.size)
            bindThumb(row.getChildAt(0) as ImageView, c.file)
            return row
        }
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(ru, "%.1f ГБ", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(ru, "%.0f МБ", b / 1048576.0)
        else -> String.format(ru, "%.0f КБ", b / 1024.0)
    }

    private fun bindThumb(iv: ImageView, f: File) {
        val key = f.absolutePath
        iv.tag = key
        val cached = thumbs.get(key)
        iv.setImageBitmap(cached)
        if (cached != null) return
        thumbExec.execute {
            if (iv.tag != key) return@execute
            val bmp = try {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(key, MediaStore.Images.Thumbnails.MINI_KIND)?.let { b ->
                    val w = u(192).coerceAtLeast(64); val h = w * b.height / maxOf(1, b.width)
                    Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
                }
            } catch (e: Throwable) { null } ?: return@execute
            AppRepo.main.post { thumbs.put(key, bmp); if (iv.tag == key) iv.setImageBitmap(bmp) }
        }
    }

    private fun dayLabel(t: Long): String {
        val cal = Calendar.getInstance()
        val today = dayKeyFmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yest = dayKeyFmt.format(cal.time)
        return when (dayKeyFmt.format(t)) { today -> "Сегодня"; yest -> "Вчера"; else -> dayFmt.format(t) }
    }

    private fun rebuildItems() {
        val out = ArrayList<Any>()
        val byDay = LinkedHashMap<String, MutableList<Dvr.Clip>>()
        for (c in clips) byDay.getOrPut(dayKeyFmt.format(c.time)) { ArrayList() }.add(c)
        for ((k, l) in byDay) {
            if (dayFilter != null && dayFilter != k) continue
            out.add(Header(dayLabel(l[0].time), l.size, l.sumOf { it.size }))
            out.addAll(l)
        }
        items = out
        adapter.notifyDataSetChanged()
        list.setSelection(0)

        chips.removeAllViews()
        fun chip(label: String, key: String?) {
            val sel = key == dayFilter
            chips.addView(Ui.pill(a, label, if (sel) Ui.accent else Ui.SURFACE, if (sel) android.graphics.Color.WHITE else Ui.TEXT) {
                dayFilter = key; rebuildItems()
            }, Ui.lin(Ui.WRAP, u(64)).apply { rightMargin = u(12) })
        }
        if (byDay.isNotEmpty()) chip("Все дни", null)
        for ((k, l) in byDay) {
            val lbl = dayLabel(l[0].time).let { if (it.contains(",")) chipFmt.format(l[0].time) else it }
            chip(lbl, k)
        }

        val hasPerm = a.has(Manifest.permission.READ_EXTERNAL_STORAGE)
        empty.visibility = if (items.isEmpty() && !scanning) View.VISIBLE else View.GONE
        permBtn.visibility = if (hasPerm) View.GONE else View.VISIBLE
        emptyTv.text = if (!hasPerm) "Нет доступа к файлам — разрешите, чтобы найти записи."
        else "Записи не найдены.\nTEYES DVR (X5) пишет на свою microSD-карту в самом регистраторе — смотрите их кнопкой «Открыть регистратор». " +
                "Cam REC (задняя камера) пишет на флешку, если она выбрана в Cam REC. Можно указать папку вручную — «Папка…».\nИскал в: " +
                roots.joinToString(", ") { it.path }.ifEmpty { "—" }
    }

    fun scan() {
        if (scanning) return
        scanning = true
        permAtScan = a.has(Manifest.permission.READ_EXTERNAL_STORAGE)
        val g = ++gen
        statusTv.text = "Поиск записей…"
        showStorage()
        empty.visibility = View.GONE
        val app = a.applicationContext
        Thread {
            val (c, r) = try { Dvr.scan(app) } catch (e: Throwable) { Pair(emptyList<Dvr.Clip>(), emptyList<File>()) }
            AppRepo.main.post {
                if (g != gen) return@post
                scanning = false; scanned = true
                clips = c; roots = r
                if (dayFilter != null && c.none { dayKeyFmt.format(it.time) == dayFilter }) dayFilter = null
                val total = c.sumOf { it.size }
                statusTv.text = if (c.isEmpty()) "Ничего не найдено" else "${c.size} записей · ${size(total)} · нажмите, чтобы открыть"
                rebuildItems()
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun open(f: File) {
        val mime = Dvr.mime(f.name)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(f), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { a.startActivity(i); return } catch (_: Exception) {}
        // Fallback: some old head-unit players only accept file:// URIs
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            android.widget.Toast.makeText(a, "Нет приложения для просмотра видео", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var permAtScan = false
    private lateinit var storageBox: LinearLayout

    private fun showStorage() {
        val app = a.applicationContext
        AppRepo.exec.execute {
            val l = Storage.all(app)
            AppRepo.main.post { storageBox.removeAllViews(); storageBox.addView(Storage.compact(a, l), Ui.lin(Ui.MATCH, Ui.WRAP)) }
        }
    }

    /** Manual folder for recordings: browse the volumes, «Выбрать эту папку». */
    private fun pickFolder(dir: File? = null) {
        val vols = Media.volumes(a)
        val subs = if (dir == null) vols.map { it.dir to it.name } else (dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()).map { it to it.name }
        val names = ((if (dir != null) listOf("⬆  Назад") else emptyList()) + subs.map { "📁  " + it.second }).toTypedArray()
        val b = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(dir?.path ?: "Где записи регистратора?")
            .setItems(names) { _, w ->
                val k = if (dir != null) w - 1 else w
                if (k < 0) pickFolder(dir!!.parentFile?.takeIf { p -> vols.any { p.path.startsWith(it.dir.path) } }) else pickFolder(subs[k].first)
            }
            .setNegativeButton("Отмена", null)
        if (dir != null) b.setPositiveButton("Выбрать эту папку") { _, _ -> Prefs.dvrFolder = dir.path; scan() }
        if (Prefs.dvrFolder.isNotEmpty()) b.setNeutralButton("Сбросить папку") { _, _ -> Prefs.dvrFolder = ""; scan() }
        val d = b.create(); d.window?.setWindowAnimations(0); d.show()
    }
    override fun onShow() { if (!scanned || permAtScan != a.has(Manifest.permission.READ_EXTERNAL_STORAGE)) scan() }
}
