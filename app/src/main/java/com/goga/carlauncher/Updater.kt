package com.goga.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Easy updates without a computer:
 *  • «Обновить с телефона» — the phone page of [UploadServer] also takes an .apk (POST /apk);
 *  • «Проверить обновления» — a small JSON manifest at [Prefs.updateUrl] (default [UPDATE_URL]):
 *        {"versionCode": 8, "versionName": "1.7", "apk": "https://…/CarLauncher-1.7.apk", "sha256": "…", "notes": "…"}
 * Before installing, every APK is checked: package = com.goga.carlauncher, signing certificate SHA-256 = ours
 * ([CERT_SHA256], the same key as the installed app), versionCode ≥ installed; downloads also by the manifest's sha256.
 * Installing goes through the system PackageInstaller (the user confirms; Android re-checks the signature itself).
 */
object Updater {
    /** Set when hosting is decided (GitHub Releases / raw file). Empty = «Проверить обновления» asks for an address. */
    const val UPDATE_URL = "https://api.github.com/repos/strobmax-cloud/carlauncher/releases/latest"
    /** SHA-256 of the release signing certificate (keystore/carlauncher.jks, alias carlauncher). */
    const val CERT_SHA256 = "88325746437563c16b240ad8049d086110c45138a9db9fc89188dad7940c9ce7"
    const val PKG = "com.goga.carlauncher"
    const val MAX_APK = 80 * 1024 * 1024
    private const val ACTION = "com.goga.carlauncher.INSTALL_STATUS"

    var host: WeakReference<Activity>? = null

    class Check(val ok: Boolean, val message: String, val versionName: String? = null, val versionCode: Long = 0)

    fun installedCode(c: Context): Long = try {
        @Suppress("DEPRECATION") c.packageManager.getPackageInfo(c.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
    } catch (e: Exception) { 0 }
    fun installedName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Signing certificate SHA-256s of an APK file: from PackageManager, else read from the APK Signature Scheme v2/v3 block. */
    fun certsOf(c: Context, f: File): List<String> {
        val out = ArrayList<String>()
        try {
            @Suppress("DEPRECATION")
            val pi: PackageInfo? = c.packageManager.getPackageArchiveInfo(f.path,
                if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi?.signingInfo?.apkContentsSigners?.forEach { out.add(hex(it.toByteArray())) }
            @Suppress("DEPRECATION") if (out.isEmpty()) pi?.signatures?.forEach { out.add(hex(it.toByteArray())) }
        } catch (_: Throwable) {}
        if (out.isEmpty()) out.addAll(v2Certs(f))
        return out.distinct()
    }

    /** Minimal reader of the APK Signing Block (v2 0x7109871a / v3 0xf05368c0): returns first-certificate digests. */
    fun v2Certs(f: File): List<String> = try {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val tailLen = minOf(len, 65_557L).toInt(); val tail = ByteArray(tailLen)
            raf.seek(len - tailLen); raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) { eocd = i; break }
            if (eocd < 0) return emptyList()
            val cdOff = ByteBuffer.wrap(tail, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val foot = ByteArray(24); raf.seek(cdOff - 24); raf.readFully(foot)
            if (String(foot, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return emptyList()
            val blockSize = ByteBuffer.wrap(foot, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val block = ByteArray((blockSize - 24).toInt()); raf.seek(cdOff - blockSize); raf.readFully(block)   // pairs, after the leading size
            val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            val res = ArrayList<String>()
            while (bb.remaining() >= 12) {
                val pl = bb.long.toInt(); val id = bb.int; val start = bb.position()
                if (id == 0x7109871a || id == 0xf05368c0.toInt()) {
                    val v = bb.slice().order(ByteOrder.LITTLE_ENDIAN); v.limit(pl - 4)
                    fun lp(b: ByteBuffer): ByteBuffer { val n = b.int; val s = b.slice().order(ByteOrder.LITTLE_ENDIAN); s.limit(n); b.position(b.position() + n); return s }
                    val signers = lp(v)
                    while (signers.hasRemaining()) {
                        val signer = lp(signers); val signed = lp(signer)
                        lp(signed)                       // digests
                        val certs = lp(signed)
                        if (certs.hasRemaining()) { val cert = lp(certs); val b = ByteArray(cert.remaining()); cert.get(b); res.add(hex(b)) }
                    }
                }
                bb.position(start + pl - 4)
            }
            res
        }
    } catch (e: Throwable) { emptyList() }

    /** Checks an APK before installing. */
    fun verify(c: Context, f: File, expectSha: String? = null): Check {
        if (!f.isFile || f.length() < 1024) return Check(false, "Файл пустой или не скачался")
        if (expectSha != null && !sha256(f).equals(expectSha.trim(), true)) return Check(false, "Контрольная сумма не совпала — файл повреждён, установка отменена")
        @Suppress("DEPRECATION") val pi = try { c.packageManager.getPackageArchiveInfo(f.path, 0) } catch (e: Throwable) { null }
            ?: return Check(false, "Это не APK-файл")
        if (pi.packageName != PKG) return Check(false, "Это другое приложение (${pi.packageName}), не Car Launcher — не ставлю")
        val certs = certsOf(c, f)
        if (certs.isEmpty()) return Check(false, "Не удалось прочитать подпись APK — не ставлю")
        if (CERT_SHA256 !in certs) return Check(false, "Подпись не совпадает с вашим Car Launcher — не ставлю (это чужая сборка)")
        @Suppress("DEPRECATION") val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        if (code < installedCode(c)) return Check(false, "Это старая версия (${pi.versionName}), у вас ${installedName(c)} — Android не даст поставить старее")
        return Check(true, "Car Launcher ${pi.versionName} — подпись ваша ✓", pi.versionName, code)
    }

    /**
     * v1.7.1: an update APK is never kept. On every start (= also right after a successful update, the app restarts)
     * the installer files the updater downloaded / the phone upload saved (app cache, private dirs) are deleted.
     * Files the user put in Download himself are NOT touched (a previous APK there is the rollback). Returns bytes freed.
     */
    fun cleanupLeftovers(c: Context): Long {
        var freed = 0L
        fun del(f: File) { val n = f.length(); if (f.delete()) freed += n }
        try {
            listOfNotNull(c.cacheDir, c.externalCacheDir, c.filesDir).forEach { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", true) }?.forEach { del(it) } }
        } catch (_: Throwable) {}
        return freed
    }

    /** «Install unknown apps» for the launcher; explains and opens the system page if it is off. */
    fun ensureAllowed(a: Activity): Boolean {
        if (a.packageManager.canRequestPackageInstalls()) return true
        val d = AlertDialog.Builder(a, R.style.Dialog).setTitle("Разрешите установку обновлений")
            .setMessage("Чтобы Car Launcher мог обновлять сам себя, Android просит разрешение «Установка неизвестных приложений» именно для Car Launcher.\n\n" +
                "В открывшемся окне включите переключатель «Разрешить из этого источника», вернитесь и повторите. Ничего другого это не открывает.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                AppRepo.startSafe(a, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)), Intent(Settings.ACTION_SECURITY_SETTINGS))
            }.setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
        return false
    }

    /** Streams the verified APK into a PackageInstaller session; Android shows its own confirmation screen. */
    fun install(c: Context, f: File): Boolean {
        val a = host?.get()
        if (a != null && !ensureAllowed(a)) return false
        return try {
            val pi = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(PKG); setSize(f.length()) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("carlauncher.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) }; s.fsync(o) }
                val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pend.intentSender)
            }
            true
        } catch (e: Exception) {
            Toast.makeText(c, "Не удалось начать установку: ${e.message}", Toast.LENGTH_LONG).show(); false
        }
    }

    class Manifest(val versionCode: Long, val versionName: String, val apk: String, val sha256: String, val notes: String)

    fun parse(json: String): Manifest {
        val o = JSONObject(json)
        return Manifest(o.getLong("versionCode"), o.optString("versionName", ""), o.getString("apk"), o.getString("sha256"), o.optString("notes", ""))
    }

    private fun get(url: String, limit: Int): ByteArray {
        var u = URL(url); var hops = 0
        while (true) {
            val cn = u.openConnection() as HttpURLConnection
            cn.connectTimeout = 15_000; cn.readTimeout = 30_000; cn.instanceFollowRedirects = false
            cn.setRequestProperty("User-Agent", "CarLauncher-updater")
            if (u.host == "api.github.com") cn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = cn.responseCode
            if (code in 300..399 && hops++ < 5) { u = URL(u, cn.getHeaderField("Location")); cn.disconnect(); continue }   // GitHub release assets redirect
            if (code != 200) throw java.io.IOException("HTTP $code")
            return cn.inputStream.use { i ->
                val bo = java.io.ByteArrayOutputStream(); val b = ByteArray(64 * 1024); var n = 0
                while (true) { val r = i.read(b); if (r < 0) break; n += r; if (n > limit) throw java.io.IOException("слишком большой файл"); bo.write(b, 0, r) }
                bo.toByteArray()
            }
        }
    }

    // ---------------- v1.7.0: GitHub Releases ----------------
    /** "owner/repo", "github.com/owner/repo", "https://github.com/owner/repo(.git)(/releases…)" → API URL of the latest release; else null. */
    fun githubApi(s: String): String? {
        val t = s.trim().removeSuffix("/")
        if (t.startsWith("https://api.github.com/repos/")) return t
        val m = Regex("^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$").find(t)
            ?: Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").find(t) ?: return null
        return "https://api.github.com/repos/${m.groupValues[1]}/${m.groupValues[2]}/releases/latest"
    }

    /** Compares "1.7.0" style names: >0 when a is newer. */
    fun compareNames(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }

    /**
     * Latest GitHub release → Manifest. versionName = tag (v1.7.1 → 1.7.1); versionCode from the release text
     * ("versionCode: 12") or, if absent, installed+1 when the tag is newer; apk = first *.apk asset; sha256 from the
     * asset "digest" (GitHub gives "sha256:…") or a "sha256: …" line in the text. The APK is verified anyway
     * (package, OUR signing certificate, versionCode) before the system installer is called.
     */
    fun parseGithub(json: String, installedName: String, installedCode: Long): Manifest {
        val o = JSONObject(json)
        val tag = o.optString("tag_name", "").removePrefix("v").removePrefix("V")
        val body = o.optString("body", "")
        val assets = o.optJSONArray("assets") ?: org.json.JSONArray()
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) { val x = assets.getJSONObject(i); if (x.optString("name").endsWith(".apk", true)) { apk = x; break } }
        if (apk == null) throw java.io.IOException("в релизе нет .apk")
        val code = Regex("versionCode\\s*[:=]\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: if (compareNames(tag, installedName) > 0) installedCode + 1 else installedCode
        val sha = apk.optString("digest", "").removePrefix("sha256:").takeIf { it.length == 64 }
            ?: Regex("sha256\\s*[:=]\\s*([0-9a-fA-F]{64})").find(body)?.groupValues?.get(1)
            ?: throw java.io.IOException("нет контрольной суммы sha256 у APK")
        return Manifest(code, tag.ifEmpty { o.optString("name") }, apk.getString("browser_download_url"), sha, body.take(1500))
    }

    /** «Проверить обновления»: manifest → dialog with notes → download → verify (sha256 + package + signature) → install. */
    fun check(a: Activity) {
        val raw = Prefs.updateUrl.ifEmpty { UPDATE_URL }
        val gh = githubApi(raw)
        val url = gh ?: raw
        if (url.isEmpty()) {
            Toast.makeText(a, "Адрес обновлений ещё не задан — пока обновляйте «С телефона»", Toast.LENGTH_LONG).show(); return
        }
        Toast.makeText(a, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        AppRepo.exec.execute {
            val r = try {
                val txt = String(get(url, 512 * 1024))
                Result.success(if (gh != null) parseGithub(txt, installedName(app), installedCode(app)) else parse(txt))
            } catch (e: Exception) { Result.failure(e) }
            AppRepo.main.post {
                val m = r.getOrNull()
                when {
                    m == null -> Toast.makeText(a, "Не удалось проверить: ${r.exceptionOrNull()?.message ?: "нет связи"}", Toast.LENGTH_LONG).show()
                    m.versionCode <= installedCode(app) -> Toast.makeText(a, "У вас последняя версия (${installedName(app)})", Toast.LENGTH_LONG).show()
                    else -> {
                        val d = AlertDialog.Builder(a, R.style.Dialog).setTitle("Есть Car Launcher ${m.versionName}")
                            .setMessage((m.notes.ifEmpty { "Новая версия." }) + "\n\nСейчас у вас ${installedName(app)}.")
                            .setPositiveButton("Скачать и установить") { _, _ -> download(a, m) }
                            .setNegativeButton("Позже", null).create()
                        d.window?.setWindowAnimations(0); d.show()
                    }
                }
            }
        }
    }

    private fun download(a: Activity, m: Manifest) {
        if (!ensureAllowed(a)) return
        Toast.makeText(a, "Скачиваю ${m.versionName}…", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        AppRepo.exec.execute {
            val f = File(app.cacheDir, "update.apk")
            val res = try { f.writeBytes(get(m.apk, MAX_APK)); verify(app, f, m.sha256) } catch (e: Exception) { Check(false, "Не скачалось: ${e.message}") }
            AppRepo.main.post {
                if (res.ok) install(app, f) else { f.delete(); Toast.makeText(a, res.message, Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Status of a PackageInstaller session: shows the system confirmation, reports errors. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(c, "Car Launcher обновлён", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(c, "Обновление не установлено" + (if (msg.isNotEmpty()) ": $msg" else ""), Toast.LENGTH_LONG).show()
            }
        }
        File(c.cacheDir, "update.apk").delete()
    }
}
