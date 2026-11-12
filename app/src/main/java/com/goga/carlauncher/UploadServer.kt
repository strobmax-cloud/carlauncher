package com.goga.carlauncher

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny HTTP server for uploading wallpapers from the phone (same Wi-Fi / phone hotspot).
 * Plain ServerSocket, no dependencies. Runs only while the «Загрузить обои с телефона» window is open (max 10 min).
 *   GET  /            → mobile page (Russian)
 *   GET  /list        → JSON array of gallery ids
 *   GET  /img?id=…    → gallery JPEG (thumbnails on the phone)
 *   POST /upload      → raw image body (the page sends files one by one, already downscaled on the phone)
 *   POST /delete?id=… → removes a picture
 *   POST /apk         → Car Launcher update (.apk): saved to cache, verified (package + our signature), then the
 *                       system installer is started on the head unit ([Updater])
 */
class UploadServer(private val ctx: Context, private val onChange: (added: String?) -> Unit) {
    companion object {
        const val MAX_UPLOAD = 25 * 1024 * 1024
        const val TIMEOUT_MS = 10 * 60_000L

        /** IPv4 addresses of active non-loopback interfaces, Wi-Fi first (wlan0 = client of the phone hotspot). */
        fun addresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { it.hostAddress ?: "" } }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
    }

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    var port = 0; private set
    @Volatile var received = 0; private set

    fun start(): Boolean {
        if (server != null) return true
        for (p in listOf(8080, 8088, 8090, 8181, 0)) {
            try {
                val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(p)); server = s; port = s.localPort; break
            } catch (e: Exception) {}
        }
        val s = server ?: return false
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { try { c.close() } catch (_: Exception) {} }
            }
        }, "wp-upload").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    val running get() = server?.isClosed == false

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 8192) return null
        }
        return b.toString("UTF-8")
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 20_000
                val inp = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = readLine(inp) ?: return
                val parts = req.split(" ")
                if (parts.size < 2) return
                val method = parts[0]; val target = parts[1]
                var len = 0
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val k = h.indexOf(':'); if (k <= 0) continue
                    if (h.substring(0, k).trim().equals("Content-Length", true)) len = h.substring(k + 1).trim().toIntOrNull() ?: 0
                }
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> send(out, 200, "text/html; charset=utf-8", page().toByteArray())
                    method == "GET" && path == "/list" ->
                        send(out, 200, "application/json", Gallery.ids(ctx).joinToString(",", "[", "]") { "\"$it\"" }.toByteArray())
                    method == "GET" && path == "/img" -> {
                        val f = Gallery.fileFor(ctx, query["id"] ?: "")
                        if (f == null) send(out, 404, "text/plain", "нет".toByteArray()) else send(out, 200, "image/jpeg", f.readBytes(), cache = true)
                    }
                    method == "POST" && path == "/upload" -> {
                        if (len <= 0 || len > MAX_UPLOAD) { send(out, 413, "text/plain; charset=utf-8", "Слишком большой файл".toByteArray()); return }
                        val body = ByteArray(len); var n = 0
                        while (n < len) { val r = inp.read(body, n, len - n); if (r < 0) break; n += r }
                        if (n < len) return
                        val id = try { Gallery.addBytes(ctx, body) } catch (e: Throwable) { null }
                        if (id == null) send(out, 415, "text/plain; charset=utf-8", "Не картинка".toByteArray())
                        else { received++; AppRepo.main.post { onChange(id) }; send(out, 200, "application/json", "{\"id\":\"$id\"}".toByteArray()) }
                    }
                    method == "GET" && path == "/version" -> send(out, 200, "text/plain; charset=utf-8", Updater.installedName(ctx).toByteArray())
                    method == "POST" && path == "/apk" -> {
                        if (len <= 0 || len > Updater.MAX_APK) { send(out, 413, "text/plain; charset=utf-8", "Слишком большой файл".toByteArray()); return }
                        val f = java.io.File(ctx.cacheDir, "update.apk")
                        var n = 0
                        f.outputStream().use { o -> val b = ByteArray(64 * 1024); while (n < len) { val r = inp.read(b, 0, minOf(b.size, len - n)); if (r < 0) break; o.write(b, 0, r); n += r } }
                        if (n < len) { f.delete(); return }
                        val chk = Updater.verify(ctx, f)
                        if (chk.ok) AppRepo.main.post { Updater.install(ctx, f) } else f.delete()
                        val msg = if (chk.ok) chk.message + ". Подтвердите установку на экране магнитолы." else chk.message
                        send(out, if (chk.ok) 200 else 422, "text/plain; charset=utf-8", msg.toByteArray())
                    }
                    method == "POST" && path == "/delete" -> {
                        val id = query["id"] ?: ""
                        val ok = Gallery.delete(ctx, id)
                        if (ok) AppRepo.main.post { onChange(null) }
                        send(out, if (ok) 200 else 404, "text/plain", (if (ok) "ok" else "нет").toByteArray())
                    }
                    else -> send(out, 404, "text/plain", "404".toByteArray())
                }
            } catch (e: Exception) {}
        }
    }

    private fun send(o: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            (if (cache) "Cache-Control: max-age=3600\r\n" else "Cache-Control: no-store\r\n") + "Connection: close\r\n\r\n"
        o.write(head.toByteArray()); o.write(body); o.flush()
    }

    /** Mobile page: pick several photos → each is downscaled on the phone (canvas, 1920 px) → POST /upload. */
    fun page(): String = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Car Launcher — обои</title><style>
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#000;color:#fff;padding:20px 16px 40px}
h1{font-size:24px;margin:4px 0 6px}p{color:#8E8E93;margin:0 0 18px;font-size:15px;line-height:1.35}
.btn{display:block;width:100%;padding:18px;border:0;border-radius:16px;background:#0A84FF;color:#fff;font-size:19px;font-weight:600;text-align:center}
.st{margin:14px 0;font-size:16px;min-height:22px;color:#30D158}
.g{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-top:10px}
.it{position:relative;aspect-ratio:16/9;border-radius:12px;overflow:hidden;background:#1C1C1E}
.it img{width:100%;height:100%;object-fit:cover;display:block}
.it button{position:absolute;right:6px;top:6px;width:34px;height:34px;border-radius:17px;border:0;background:rgba(0,0,0,.6);color:#fff;font-size:18px}
h2{font-size:17px;margin:26px 0 4px;color:#EBEBF5}
</style></head><body>
<h1>Обои для магнитолы</h1><p>Выберите одно или несколько фото — они сразу появятся в Car Launcher. Фото уменьшаются до 1280×720 и хранятся только в магнитоле.</p>
<label class="btn">Выбрать фото<input id="f" type="file" accept="image/*" multiple hidden></label>
<div class="st" id="st"></div><h2>В магнитоле</h2><div class="g" id="g"></div>
<h2>Обновить Car Launcher</h2><p id="ver">Выберите файл CarLauncher-….apk — магнитола проверит, что это ваша сборка, и предложит установить.</p>
<label class="btn" style="background:#2C2C2E">Выбрать APK<input id="a" type="file" accept=".apk,application/vnd.android.package-archive" hidden></label>
<div class="st" id="ast"></div>
<script>
const st=document.getElementById('st'),g=document.getElementById('g');
function shrink(file){return new Promise(res=>{const u=URL.createObjectURL(file),im=new Image();im.onload=()=>{const k=Math.min(1,1920/Math.max(im.width,im.height));
 const c=document.createElement('canvas');c.width=Math.round(im.width*k);c.height=Math.round(im.height*k);c.getContext('2d').drawImage(im,0,0,c.width,c.height);URL.revokeObjectURL(u);
 c.toBlob(b=>res(b||file),'image/jpeg',.9)};im.onerror=()=>{URL.revokeObjectURL(u);res(file)};im.src=u})}
async function load(){const r=await fetch('/list');const l=await r.json();g.innerHTML='';l.reverse().forEach(id=>{const d=document.createElement('div');d.className='it';
 d.innerHTML='<img src="/img?id='+encodeURIComponent(id)+'"><button>✕</button>';d.querySelector('button').onclick=async()=>{if(!confirm('Удалить эту картинку?'))return;
 await fetch('/delete?id='+encodeURIComponent(id),{method:'POST'});load()};g.appendChild(d)});if(!l.length)g.innerHTML='<p>Пока пусто</p>'}
document.getElementById('f').onchange=async e=>{const fs=[...e.target.files];let ok=0;
 for(let i=0;i<fs.length;i++){st.style.color='#fff';st.textContent='Загрузка '+(i+1)+' из '+fs.length+'…';
  try{const b=await shrink(fs[i]);const r=await fetch('/upload',{method:'POST',body:b});if(r.ok)ok++}catch(x){}}
 st.style.color=ok===fs.length?'#30D158':'#FF9F0A';st.textContent='Готово: '+ok+' из '+fs.length;e.target.value='';load()};
fetch('/version').then(r=>r.text()).then(v=>{document.getElementById('ver').textContent='Сейчас в магнитоле версия '+v+'. Выберите файл CarLauncher-….apk — магнитола проверит, что это ваша сборка, и предложит установить.'});
document.getElementById('a').onchange=async e=>{const f=e.target.files[0];if(!f)return;const s=document.getElementById('ast');s.style.color='#fff';s.textContent='Отправка '+Math.round(f.size/1024)+' КБ…';
 try{const r=await fetch('/apk',{method:'POST',body:f});const t=await r.text();s.style.color=r.ok?'#30D158':'#FF453A';s.textContent=t}catch(x){s.style.color='#FF453A';s.textContent='Не отправилось: '+x}e.target.value=''};
load();
</script></body></html>"""
}
