package com.goga.carlauncher

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Finds dashcam recordings on all mounted storages. Runs on a background thread. */
object Dvr {
    private val EXT = mapOf("mp4" to "video/mp4", "ts" to "video/mp2t", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "mkv" to "video/x-matroska", "3gp" to "video/3gpp")
    private val DIRS = setOf("dvr", "dcim", "video", "videos", "record", "records", "recorder", "recording", "recordings",
        "camera", "cameras", "camera360", "movie", "movies", "carrecorder", "car_recorder", "dashcam", "dvrdata",
        "normal", "emergency", "event", "events", "lock", "locked", "front", "rear", "loop", "parking", "100media",
        // v1.7.0: TEYES / FYT names (Cam REC, DVR, «HD видеорегистратор»), Russian folder names of the X5
        "cameralock", "camrec", "cam_rec", "cam rec", "hddvr", "hd_dvr", "cardvr", "car_dvr", "vision", "rec", "видео", "запуск", "записи", "регистратор",
        "ch0", "ch1", "ch2", "ch3", "avm", "360", "photo", "nor", "evt", "par", "emr")
    private const val LIMIT = 8000

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isVideo(name: String) = EXT.containsKey(ext(name))
    fun mime(path: String) = EXT[ext(path)] ?: "video/*"

    private fun dirMatches(name: String): Boolean {
        val n = name.lowercase()
        return n in DIRS || n.contains("dvr") || n.contains("record") || n.contains("dashcam") || n.contains("camrec") || n.contains("cameralock")
    }

    /** Dashcam-looking file name anywhere (ch0_…, 20261112_083000…, REC…, NOR/EVT/LOCK…). */
    private val FILE_RX = Regex("^(ch\\d[_-]|rec|nor|evt|emr|lock|par|dvr|file\\d+|\\d{8}[_-]?\\d{4,6}|\\d{14}|\\d{4}[_-]\\d{2}[_-]\\d{2}).*", RegexOption.IGNORE_CASE)
    fun looksLikeDashcam(name: String): Boolean = isVideo(name) && FILE_RX.matches(name)


    fun roots(ctx: Context): List<File> {
        val set = LinkedHashSet<String>()
        fun add(f: File?) {
            if (f == null) return
            try { if (f.isDirectory && f.canRead()) set.add(f.canonicalPath) } catch (_: Exception) {}
        }
        File("/storage").listFiles()?.forEach { if (it.name != "self" && it.name != "emulated") add(it) }
        add(File("/storage/emulated/0"))
        @Suppress("DEPRECATION") add(Environment.getExternalStorageDirectory())
        for (p in listOf("/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/external_sd", "/mnt/extsd",
            "/mnt/sdcard", "/mnt/sdcard2", "/sdcard", "/udisk", "/mnt/udisk", "/mnt/usbdisk")) {
            val d = File(p); add(d); d.listFiles()?.forEach { if (it.isDirectory) add(it) }
        }
        ctx.getExternalFilesDirs(null).forEach { f ->
            f?.absolutePath?.let { val i = it.indexOf("/Android/"); if (i > 0) add(File(it.substring(0, i))) }
        }
        try {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                try { add(File(v.javaClass.getMethod("getPath").invoke(v) as String)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // drop roots that are inside other roots
        val list = set.toList().sortedBy { it.length }
        val out = ArrayList<String>()
        for (p in list) if (out.none { p.startsWith("$it/") }) out.add(p)
        return out.map { File(it) }
    }

    class Clip(val file: File, val time: Long, val size: Long)

    fun scan(ctx: Context): Pair<List<Clip>, List<File>> {
        val roots = roots(ctx).toMutableList()
        Prefs.dvrFolder.takeIf { it.isNotEmpty() }?.let { roots.add(File(it)) }
        val seen = HashSet<String>()
        val out = ArrayList<Clip>()
        fun walk(dir: File, depth: Int, inMatch: Boolean) {
            if (out.size >= LIMIT) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                val name = f.name
                if (f.isDirectory) {
                    if (name.startsWith(".")) continue
                    if (!inMatch && (name == "Android" || name == "LOST.DIR")) continue
                    val m = inMatch || dirMatches(name)
                    if (m) { if (depth < 7) walk(f, depth + 1, true) } else if (depth < 3) walk(f, depth + 1, false)
                } else if ((inMatch && isVideo(name)) || (depth <= 3 && looksLikeDashcam(name))) {
                    val len = f.length()
                    val t = f.lastModified()
                    if (len > 0 && seen.add("$name|$len|$t")) out.add(Clip(f, t, len))
                }
            }
        }
        for (r in roots.dropLast(if (Prefs.dvrFolder.isNotEmpty()) 1 else 0)) walk(r, 0, dirMatches(r.name))
        Prefs.dvrFolder.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { walk(it, 0, true) }
        out.sortByDescending { it.time }
        return out to roots
    }
}
