package com.goga.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.StrictMode
import android.os.storage.StorageManager
import android.widget.Toast
import java.io.File

/**
 * «Флешка / Медиа»: USB / SD browsing for video and music, opening in the preferred player and per-file resume.
 * VLC: launched for result with extra "position" (ms) → returns "org.videolan.vlc.player.result" with
 * extra_position / extra_duration, which we store. MX Player: "position" + "return_result". Others: best effort.
 */
object Media {
    const val VLC = "org.videolan.vlc"
    const val VLC_PLAYER = "org.videolan.vlc.gui.video.VideoPlayerActivity"
    const val VLC_RESULT = "org.videolan.vlc.player.result"
    const val MX = "com.mxtech.videoplayer.ad"
    const val MX_PRO = "com.mxtech.videoplayer.pro"
    const val REQ_PLAY = 43

    val VIDEO = mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "3gp" to "video/3gpp", "webm" to "video/webm", "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "vob" to "video/mpeg")
    val AUDIO = mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "wav" to "audio/x-wav", "wma" to "audio/x-ms-wma", "ape" to "audio/x-ape", "alac" to "audio/mp4")

    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    /** "video" / "music" / null */
    fun kind(n: String): String? = ext(n).let { if (VIDEO.containsKey(it)) "video" else if (AUDIO.containsKey(it)) "music" else null }
    fun mime(n: String) = VIDEO[ext(n)] ?: AUDIO[ext(n)] ?: "*/*"
    fun isMedia(n: String) = kind(n) != null

    // ---------------- volumes ----------------
    class Vol(val name: String, val dir: File, val removable: Boolean)

    /** Tests / simulator only. */
    var fixedVolumes: List<Vol>? = null

    fun volumes(c: Context): List<Vol> {
        fixedVolumes?.let { return it }
        val labels = HashMap<String, String>()
        try {
            for (v in c.getSystemService(StorageManager::class.java).storageVolumes) {
                val p = try { v.javaClass.getMethod("getPath").invoke(v) as String } catch (e: Exception) { null } ?: continue
                labels[File(p).canonicalPath] = v.getDescription(c)
            }
        } catch (_: Exception) {}
        val internal = try { File("/storage/emulated/0").canonicalPath } catch (e: Exception) { "/storage/emulated/0" }
        val roots = Dvr.roots(c)
        val out = roots.map { f ->
            val p = f.path
            val int = p == internal || p.startsWith("/storage/emulated") || p == "/sdcard" || p.endsWith("/sdcard")
            val name = labels[p] ?: if (int) "Память магнитолы" else when {
                p.contains("usb", true) || p.contains("udisk", true) -> "USB-флешка (${f.name})"
                p.contains("sd", true) -> "Карта SD (${f.name})"
                else -> "Накопитель ${f.name}"
            }
            Vol(name, f, !int)
        }
        // /storage/XXXX-XXXX and /mnt/media_rw/XXXX-XXXX are the same stick — keep the first (shortest path)
        return out.distinctBy { if (it.removable) it.dir.name else it.dir.path }.sortedBy { if (it.removable) 0 else 1 }
    }

    class Entry(val file: File, val dir: Boolean, val kind: String?)

    /** One directory, no recursion: folders first, then files of the requested kind (null = video + music). */
    fun list(dir: File, kind: String?): List<Entry> {
        val all = dir.listFiles() ?: return emptyList()
        val col = java.text.Collator.getInstance(java.util.Locale("ru"))
        val dirs = all.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "LOST.DIR" && it.name != "Android" }
            .sortedWith { a, b -> col.compare(a.name, b.name) }.map { Entry(it, true, null) }
        val files = all.filter { it.isFile && !it.name.startsWith(".") }.mapNotNull { f -> kind(f.name)?.takeIf { kind == null || it == kind }?.let { Entry(f, false, it) } }
            .sortedWith { a, b -> col.compare(a.file.name, b.file.name) }
        return dirs + files
    }

    // ---------------- resume positions ----------------
    class Pos(val pos: Long, val dur: Long, val at: Long) {
        val fraction get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        /** Watched: last 15 s or last 3 % (end credits). */
        val finished get() = dur > 0 && pos >= minOf(dur - 15_000, (dur * 0.97).toLong())
        /** Worth offering «Продолжить»: past 5 s and not finished. */
        val resumable get() = pos > 5_000 && !finished
    }
    private lateinit var sp: SharedPreferences
    private fun prefs(c: Context): SharedPreferences { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE); return sp }
    const val MAX_POS = 300

    fun position(c: Context, path: String): Pos? = prefs(c).getString(path, null)?.split('|')?.takeIf { it.size == 3 }?.let {
        Pos(it[0].toLongOrNull() ?: 0, it[1].toLongOrNull() ?: 0, it[2].toLongOrNull() ?: 0)
    }

    fun savePosition(c: Context, path: String, pos: Long, dur: Long, now: Long = System.currentTimeMillis()) {
        val p = prefs(c)
        p.edit().putString(path, "${pos.coerceAtLeast(0)}|${dur.coerceAtLeast(0)}|$now").apply()
        if (p.all.size > MAX_POS) {   // forget the oldest
            val old = p.all.entries.sortedBy { (it.value as? String)?.substringAfterLast('|')?.toLongOrNull() ?: 0 }.take(p.all.size - MAX_POS)
            p.edit().apply { old.forEach { remove(it.key) } }.apply()
        }
    }

    /** Most recently played file of a kind that still exists. */
    fun last(c: Context, kind: String): Pair<File, Pos>? {
        val path = if (kind == "video") Prefs.lastVideo else Prefs.lastMusic
        if (path.isEmpty()) return null
        val f = File(path); if (!f.isFile) return null
        return f to (position(c, path) ?: Pos(0, 0, 0))
    }

    // ---------------- players ----------------
    fun players(c: Context, kind: String): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.goga.carlauncher.files/x." + (if (kind == "video") "mp4" else "mp3")), if (kind == "video") "video/mp4" else "audio/mpeg")
        val pm = c.packageManager
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
            .filter { it.first != c.packageName }
    }
    /** "" = automatic (VLC if installed, else the system default / chooser), "ask" = always ask, else a package. */
    fun playerFor(c: Context, kind: String): String? {
        val pref = if (kind == "video") Prefs.videoPlayer else Prefs.musicPlayer
        return when {
            pref == "ask" -> "ask"
            pref.isNotEmpty() && Tiles.installed(c, pref) -> pref
            kind == "video" && Tiles.installed(c, VLC) -> VLC
            else -> null
        }
    }

    /** Pending play for the result callback. */
    var playing: String? = null; private set

    fun open(a: Activity, f: File, resume: Boolean) {
        val kind = kind(f.name) ?: "video"
        val mime = mime(f.name)
        if (kind == "video") Prefs.lastVideo = f.path else Prefs.lastMusic = f.path
        val pos = if (resume) position(a, f.path)?.takeIf { it.resumable }?.pos ?: 0L else 0L
        val player = playerFor(a, kind)
        val uri = FilesProvider.uriFor(f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("title", f.nameWithoutExtension)
        when (player) {
            VLC -> {
                if (kind == "video") i.component = ComponentName(VLC, VLC_PLAYER) else i.setPackage(VLC)
                if (pos > 0) i.putExtra("position", pos) else i.putExtra("from_start", true)
            }
            MX, MX_PRO -> { i.setPackage(player); i.putExtra("return_result", true); if (pos > 0) i.putExtra("position", pos.toInt()) }
            null, "ask" -> { if (pos > 0) i.putExtra("position", pos) }
            else -> { i.setPackage(player); if (pos > 0) i.putExtra("position", pos) }
        }
        playing = f.path
        markStarted(a, f, pos)
        val target = if (player == "ask") Intent.createChooser(i, "Чем открыть?") else i
        try { a.startActivityForResult(target, REQ_PLAY); return } catch (_: Exception) {}
        // fallbacks: without the chosen component, then file:// for old head-unit players
        try { a.startActivityForResult(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_PLAY); return } catch (_: Exception) {}
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            a.startActivityForResult(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), mime), REQ_PLAY)
        } catch (_: Exception) { playing = null; Toast.makeText(a, "Нет приложения для этого файла — установите VLC", Toast.LENGTH_LONG).show() }
    }

    /** First play of a file gets an entry (so it is listed as started); a known position is kept until the player reports a new one. */
    private fun markStarted(c: Context, f: File, pos: Long) {
        if (position(c, f.path) == null) savePosition(c, f.path, pos, 0)
    }

    /** Player came back: VLC (extra_position / extra_duration), MX Player (position / duration), others if they send anything. */
    fun onResult(c: Context, data: Intent?) {
        val path = playing ?: return
        playing = null
        val ex = data?.extras ?: return
        fun num(vararg keys: String): Long? { for (k in keys) { val v = ex.get(k); if (v is Number) return v.toLong() }; return null }
        val pos = num("extra_position", "position") ?: return
        val dur = num("extra_duration", "duration") ?: position(c, path)?.dur ?: 0L
        savePosition(c, path, pos, dur)
    }
}
