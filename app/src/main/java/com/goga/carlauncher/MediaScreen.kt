package com.goga.carlauncher

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.goga.carlauncher.Ui.u
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Thin progress line (watched part of a file). */
class ProgressLine(c: Context) : View(c) {
    var fraction = 0f; set(v) { if (field != v) { field = v; invalidate() } }
    var color = Ui.accent
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); r.set(0f, 0f, width.toFloat(), h)
        p.color = 0x33FFFFFF; cv.drawRoundRect(r, h / 2, h / 2, p)
        if (fraction > 0f) { r.right = maxOf(h, width * fraction); p.color = color; cv.drawRoundRect(r, h / 2, h / 2, p) }
    }
}

/**
 * «Флешка / Медиа»: browse USB / SD / internal storage for video and music, one folder at a time (no deep scans).
 * Opens files in the preferred player; VLC gets / returns the playback position so every file resumes where it stopped.
 */
class MediaScreen(a: MainActivity) : Screen(a) {
    private val ru = Locale("ru")
    private var vols: List<Media.Vol> = emptyList()
    private var vol: Media.Vol? = null
    private var dir: File? = null
    private var entries: List<Media.Entry> = emptyList()
    private var items: List<Any> = emptyList()
    private var loading = false
    private var gen = 0
    private val kind get() = Prefs.mediaKind

    private lateinit var statusTv: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var pathRow: LinearLayout
    private lateinit var pathTv: TextView
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var permBtn: View

    private val thumbs = LruCache<String, Bitmap>(60)
    private val exec = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newSingleThreadExecutor()

    /** «Продолжить просмотр / прослушивание» row */
    class Continue(val file: File, val pos: Media.Pos)
    object Up

    override val view: View by lazy { build() }

    private fun build(): View {
        val root = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), 0) }
        val top = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val tcol = Ui.vbox(a)
        tcol.addView(Ui.text(a, 34, Ui.TEXT, Ui.BOLD).apply { text = "Флешка / Медиа" })
        statusTv = Ui.text(a, 19, Ui.TEXT2)
        tcol.addView(statusTv, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        top.addView(tcol, Ui.lin(0, Ui.WRAP, 1f))
        tabs = Ui.hbox(a).apply { background = Ui.round(Ui.SURFACE, u(22)); setPadding(u(4), u(4), u(4), u(4)) }
        top.addView(tabs, Ui.lin(Ui.WRAP, u(72)).apply { rightMargin = u(16) })
        top.addView(Ui.iconButton(a, R.drawable.ic_refresh, 72, 32, Ui.SURFACE, Ui.TEXT) { rescan() })
        root.addView(top)

        chips = Ui.hbox(a)
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) },
            Ui.lin(Ui.MATCH, u(64)).apply { topMargin = u(14) })

        // v1.7.0 free / total space of every storage
        storageBox = Ui.vbox(a).apply { setPadding(u(18), u(10), u(18), u(10)) }
        Ui.card(storageBox, blur = false, radius = 18)
        root.addView(storageBox, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })

        pathRow = Ui.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
        pathTv = Ui.text(a, 18, Ui.TEXT3).apply { ellipsize = android.text.TextUtils.TruncateAt.START }
        pathRow.addView(pathTv, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(6) })
        root.addView(pathRow, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })

        val frame = FrameLayout(a)
        list = ListView(a).apply {
            divider = android.graphics.drawable.ColorDrawable(0); dividerHeight = u(10); selector = android.graphics.drawable.ColorDrawable(0)
            overScrollMode = View.OVER_SCROLL_NEVER; isFastScrollEnabled = true
            setPadding(0, u(6), 0, u(20)); clipToPadding = false
            adapter = this@MediaScreen.adapter
            setOnItemClickListener { _, _, p, _ -> click(items[p]) }
            setOnItemLongClickListener { _, _, p, _ ->
                // long press on a file = play from the start
                val e = items[p] as? Media.Entry; if (e != null && !e.dir) { Media.open(a, e.file, resume = false); true } else false
            }
        }
        frame.addView(list)
        empty = Ui.vbox(a).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        empty.addView(Ui.icon(a, R.drawable.ic_usb, Ui.TEXT3), Ui.lin(u(96), u(96)))
        emptyTv = Ui.text(a, 22, Ui.TEXT2, Ui.SANS, 0).apply { gravity = Gravity.CENTER }
        empty.addView(emptyTv, Ui.lin(u(820), Ui.WRAP).apply { topMargin = u(16) })
        permBtn = Ui.pill(a, "Разрешить доступ к файлам", Ui.accent) { a.requestPerms() }
        empty.addView(permBtn, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(20) })
        frame.addView(empty)
        root.addView(frame, Ui.lin(Ui.MATCH, 0, 1f).apply { topMargin = u(4) })
        buildTabs()
        return root
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        for ((k, label) in listOf("video" to "Видео", "music" to "Музыка")) {
            val sel = kind == k
            tabs.addView(Ui.pill(a, label, if (sel) Ui.accent else 0, if (sel) Color.WHITE else Ui.TEXT2) {
                if (Prefs.mediaKind != k) { Prefs.mediaKind = k; buildTabs(); load(dir) }
            }, Ui.lin(u(150), Ui.MATCH))
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (v in vols) {
            val sel = v === vol
            chips.addView(Ui.pill(a, (if (v.removable) "⏏  " else "") + v.name, if (sel) Ui.accent else Ui.SURFACE, if (sel) Color.WHITE else Ui.TEXT) {
                vol = v; load(v.dir)
            }, Ui.lin(Ui.WRAP, u(60)).apply { rightMargin = u(12) })
        }
    }

    private fun click(it: Any) {
        when (it) {
            is Up -> dir?.parentFile?.let { p -> load(p) }
            is Continue -> Media.open(a, it.file, resume = true)
            is Media.Entry -> if (it.dir) load(it.file) else Media.open(a, it.file, resume = true)
        }
    }

    private lateinit var storageBox: LinearLayout

    fun rescan() {
        val g = ++gen
        loading = true
        statusTv.text = "Поиск накопителей…"
        val app = a.applicationContext
        exec.execute {
            val v = try { Media.volumes(app) } catch (e: Throwable) { emptyList() }
            val st = Storage.all(app)
            AppRepo.main.post {
                storageBox.removeAllViews(); storageBox.addView(Storage.compact(a, st), Ui.lin(Ui.MATCH, Ui.WRAP))
                if (g != gen) return@post
                vols = v
                val keep = vol?.let { o -> v.firstOrNull { it.dir == o.dir } }
                vol = keep ?: v.firstOrNull()
                buildChips()
                val d = dir?.takeIf { keep != null && it.path.startsWith(keep.dir.path) && it.isDirectory } ?: vol?.dir
                load(d)
            }
        }
    }

    private fun load(d: File?) {
        val g = ++gen
        dir = d
        loading = true
        if (d == null) { entries = emptyList(); loading = false; rebuildItems(); return }
        val k = kind
        statusTv.text = "Чтение папки…"
        exec.execute {
            val e = try { Media.list(d, k) } catch (t: Throwable) { emptyList() }
            AppRepo.main.post {
                if (g != gen) return@post
                entries = e; loading = false
                rebuildItems(); list.setSelection(0)
            }
        }
    }

    /** Items = [Continue] + [Up] + folders + files; cheap, re-run on every return from the player. */
    private fun rebuildItems() {
        val out = ArrayList<Any>()
        Media.last(a, kind)?.let { (f, p) -> if (p.resumable || kind == "music") out.add(Continue(f, p)) }
        val v = vol; val d = dir
        if (v != null && d != null && d.path != v.dir.path) out.add(Up)
        out.addAll(entries)
        items = out
        adapter.notifyDataSetChanged()

        val files = entries.count { !it.dir }; val folders = entries.size - files
        statusTv.text = when {
            vols.isEmpty() -> "Накопители не найдены"
            else -> "${vol?.name ?: ""}  ·  " + (if (folders > 0) "папок: $folders  ·  " else "") +
                    (if (kind == "video") "видео: $files" else "треков: $files") + "  ·  долгое нажатие — с начала"
        }
        pathTv.text = if (v != null && d != null) (v.name + d.path.removePrefix(v.dir.path)).replace("/", "  ›  ") else ""
        val hasPerm = a.has(Manifest.permission.READ_EXTERNAL_STORAGE)
        val nothing = entries.isEmpty() && !loading
        empty.visibility = if (nothing && out.none { it is Continue }) View.VISIBLE else View.GONE
        permBtn.visibility = if (hasPerm) View.GONE else View.VISIBLE
        emptyTv.text = when {
            !hasPerm -> "Нет доступа к файлам — разрешите, чтобы видеть флешку."
            vols.isEmpty() -> "Флешка не найдена.\nВставьте USB-флешку или SD-карту и нажмите «Обновить»."
            else -> if (kind == "video") "В этой папке нет видео." else "В этой папке нет музыки."
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s / 60 % 60
        return if (h > 0) String.format(ru, "%d:%02d:%02d", h, m, s % 60) else String.format(ru, "%d:%02d", m, s % 60)
    }
    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(ru, "%.1f ГБ", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(ru, "%.0f МБ", b / 1048576.0)
        else -> String.format(ru, "%.0f КБ", b / 1024.0)
    }
    private fun progressText(p: Media.Pos?): String = when {
        p == null || (p.pos <= 0 && p.dur <= 0) -> ""
        p.finished -> "  ·  просмотрено"
        p.dur > 0 && p.pos > 0 -> "  ·  ${fmt(p.pos)} из ${fmt(p.dur)}"
        p.pos > 0 -> "  ·  остановлено на ${fmt(p.pos)}"
        else -> ""
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(p: Int) = when (val it = items[p]) { is Continue -> 0; is Media.Entry -> if (it.dir) 1 else 2; else -> 1 }

        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View = when (val it = items[p]) {
            is Continue -> continueRow(cv as? LinearLayout, it)
            is Up -> folderRow(cv as? LinearLayout, "Назад", "на уровень вверх", R.drawable.ic_back)
            is Media.Entry -> if (it.dir) folderRow(cv as? LinearLayout, it.file.name, "папка", R.drawable.ic_folder) else fileRow(cv as? LinearLayout, it)
            else -> View(a)
        }
    }

    private fun continueRow(old: LinearLayout?, c: Continue): View {
        val row = old ?: Ui.hbox(a).apply {
            Ui.card(this, blur = false, solid = (Ui.accent and 0x00FFFFFF) or 0x40000000, press = true)
            setPadding(u(14), u(14), u(24), u(14)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(Ui.MATCH, u(150))
            addView(thumbView(), Ui.lin(u(208), u(118)))
            val col = Ui.vbox(a)
            col.addView(Ui.text(a, 17, Ui.accent, Ui.BOLD))
            col.addView(Ui.text(a, 28, Ui.TEXT, Ui.BOLD).apply { setPadding(0, u(4), 0, 0) })
            col.addView(Ui.text(a, 18, Ui.TEXT2).apply { setPadding(0, u(4), 0, u(10)) })
            col.addView(ProgressLine(a), Ui.lin(Ui.MATCH, u(8)))
            addView(col, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(24) })
            addView(Ui.iconButton(a, R.drawable.ic_play, 84, 40, Ui.accent, Color.WHITE) {}.apply { isClickable = false; isDuplicateParentStateEnabled = true },
                Ui.lin(u(84), u(84)).apply { leftMargin = u(20) })
        }
        val col = row.getChildAt(1) as LinearLayout
        val video = Media.kind(c.file.name) == "video"
        (col.getChildAt(0) as TextView).text = if (video) "ПРОДОЛЖИТЬ ПРОСМОТР" else "ПРОДОЛЖИТЬ ПРОСЛУШИВАНИЕ"
        (col.getChildAt(1) as TextView).text = c.file.nameWithoutExtension
        (col.getChildAt(2) as TextView).text = (if (c.pos.pos > 0) "с ${fmt(c.pos.pos)}" + (if (c.pos.dur > 0) " из ${fmt(c.pos.dur)}" else "") else "с начала") +
                "  ·  " + (c.file.parentFile?.name ?: "")
        (col.getChildAt(3) as ProgressLine).fraction = c.pos.fraction
        bindThumb(row.getChildAt(0) as ImageView, c.file)
        return row
    }

    private fun folderRow(old: LinearLayout?, name: String, sub: String, icon: Int): View {
        val row = old ?: Ui.hbox(a).apply {
            Ui.card(this, blur = false, press = true)
            setPadding(u(20), 0, u(24), 0); gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(Ui.MATCH, u(96))
            addView(FrameLayout(a).apply { background = Ui.round(0x33FF9F0A, u(16)); addView(Ui.icon(a, icon, 0xFFFF9F0A.toInt()), Ui.frame(u(36), u(36))) }, Ui.lin(u(64), u(64)))
            val col = Ui.vbox(a)
            col.addView(Ui.text(a, 26, Ui.TEXT, Ui.MEDIUM))
            col.addView(Ui.text(a, 16, Ui.TEXT3).apply { setPadding(0, u(2), 0, 0) })
            addView(col, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(22) })
            addView(Ui.icon(a, R.drawable.ic_chevron, Ui.TEXT3), Ui.lin(u(32), u(32)))
        }
        ((row.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setImageResource(icon)
        val col = row.getChildAt(1) as LinearLayout
        (col.getChildAt(0) as TextView).text = name
        (col.getChildAt(1) as TextView).text = sub
        row.getChildAt(2).visibility = if (icon == R.drawable.ic_back) View.INVISIBLE else View.VISIBLE
        return row
    }

    private fun fileRow(old: LinearLayout?, e: Media.Entry): View {
        val row = old ?: Ui.hbox(a).apply {
            Ui.card(this, blur = false, press = true)
            setPadding(u(12), u(10), u(24), u(10)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(Ui.MATCH, u(120))
            addView(thumbView(), Ui.lin(u(176), u(99)))
            val col = Ui.vbox(a)
            col.addView(Ui.text(a, 25, Ui.TEXT, Ui.MEDIUM).apply { ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            col.addView(Ui.text(a, 17, Ui.TEXT2).apply { setPadding(0, u(6), 0, u(10)) })
            col.addView(ProgressLine(a), Ui.lin(Ui.MATCH, u(6)))
            addView(col, Ui.lin(0, Ui.WRAP, 1f).apply { leftMargin = u(22) })
            addView(Ui.icon(a, R.drawable.ic_play, Ui.accent), Ui.lin(u(40), u(40)).apply { leftMargin = u(20) })
        }
        val pos = Media.position(a, e.file.path)
        val col = row.getChildAt(1) as LinearLayout
        (col.getChildAt(0) as TextView).text = e.file.nameWithoutExtension
        (col.getChildAt(1) as TextView).text = Media.ext(e.file.name).uppercase() + "  ·  " + size(e.file.length()) + progressText(pos)
        val bar = col.getChildAt(2) as ProgressLine
        bar.fraction = pos?.fraction ?: 0f
        bar.visibility = if ((pos?.fraction ?: 0f) > 0f) View.VISIBLE else View.INVISIBLE
        bar.color = if (pos?.finished == true) 0xFF30D158.toInt() else Ui.accent
        bindThumb(row.getChildAt(0) as ImageView, e.file)
        return row
    }

    private fun thumbView() = ImageView(a).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(Ui.SURFACE2, u(14)); Ui.clipRound(this, u(14))
    }

    private fun thumbFile(f: File) = File(a.cacheDir, "mthumbs/" + Integer.toHexString((f.path + f.length() + f.lastModified()).hashCode()) + ".jpg")

    /** Video: frame thumbnail (memory LRU + small JPEG disk cache). Music: embedded cover if any, else a note icon. */
    private fun bindThumb(iv: ImageView, f: File) {
        val key = f.absolutePath
        iv.tag = key
        val cached = thumbs.get(key)
        if (cached != null) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.imageTintList = null; iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(cached); return }
        val video = Media.kind(f.name) == "video"
        iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.setPadding(u(30), u(30), u(30), u(30))
        iv.setImageResource(if (video) R.drawable.ic_videocam else R.drawable.ic_music)
        iv.imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT3)
        thumbExec.execute {
            if (iv.tag != key) return@execute
            val disk = thumbFile(f)
            val bmp = try {
                if (disk.isFile) BitmapFactory.decodeFile(disk.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                else {
                    val raw: Bitmap? = if (video) {
                        @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(key, MediaStore.Images.Thumbnails.MINI_KIND)
                    } else {
                        val mr = android.media.MediaMetadataRetriever()
                        try { mr.setDataSource(key); mr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
                        finally { try { mr.release() } catch (_: Exception) {} }
                    }
                    raw?.let { b ->
                        val w = u(208).coerceAtLeast(64); val h = w * b.height / maxOf(1, b.width)
                        Bitmap.createScaledBitmap(b, w, h, true).also { s ->
                            if (s !== b) b.recycle()
                            try { disk.parentFile?.mkdirs(); disk.outputStream().use { o -> s.compress(Bitmap.CompressFormat.JPEG, 80, o) } } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Throwable) { null } ?: return@execute
            AppRepo.main.post {
                thumbs.put(key, bmp)
                if (iv.tag == key) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.imageTintList = null; iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(bmp) }
            }
        }
    }

    /** Called after the player returned (position saved) and on every show. */
    fun refresh() { if (::list.isInitialized) rebuildItems() }

    private var permAtScan = false
    override fun onShow() {
        val perm = a.has(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (vols.isEmpty() || permAtScan != perm) { permAtScan = perm; rescan() } else refresh()
    }
}
