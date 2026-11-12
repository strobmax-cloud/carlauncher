package com.goga.carlauncher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

object AppRepo {
    class App(val label: String, val pkg: String, val cls: String) {
        val key get() = "$pkg/$cls"
    }

    @Volatile var apps: List<App>? = null
    private val icons = HashMap<String, Drawable>()
    val exec = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())
    private var gen = 0

    fun load(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val col = Collator.getInstance(Locale("ru"))
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { App(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedWith { a, b -> col.compare(a.label, b.label) }
    }

    fun loadAsync(ctx: Context, cb: (List<App>) -> Unit) {
        apps?.let { cb(it); return }
        val app = ctx.applicationContext
        val g = gen
        exec.execute {
            val l = load(app)
            main.post { if (g == gen) apps = l; cb(l) }
        }
    }

    fun invalidate() { gen++; apps = null; synchronized(icons) { icons.clear(); generic.clear() } }

    fun trimIcons() { synchronized(icons) { icons.clear() } }
    fun iconCount() = synchronized(icons) { icons.size }

    fun cachedIcon(key: String): Drawable? = synchronized(icons) { icons[key] }

    /** Packages whose real icon is missing / the generic Android robot (→ bundled brand-style art). */
    private val generic = HashSet<String>()

    /** True when the app has no own icon (Android would draw the default robot). */
    fun hasOwnIcon(pm: android.content.pm.PackageManager, pkg: String, cls: String?): Boolean = try {
        val ai = pm.getApplicationInfo(pkg, 0)
        val act = if (cls != null) pm.getActivityInfo(ComponentName(pkg, cls), 0).icon else 0
        ai.icon != 0 || act != 0
    } catch (e: Exception) { false }

    /** Real app icon; if the app has none (or it cannot be loaded) → [fallback] (bundled brand-style art), else Android's default. */
    fun bindIcon(ctx: Context, pkg: String, cls: String?, iv: ImageView, fallback: Int = Tiles.brand(pkg)) {
        val key = "$pkg/${cls ?: ""}"
        iv.tag = key
        if (fallback != 0 && synchronized(icons) { key in generic }) { iv.setImageResource(fallback); return }
        cachedIcon(key)?.let { iv.setImageDrawable(it); return }
        iv.setImageDrawable(null)
        val pm = ctx.applicationContext.packageManager
        exec.execute {
            if (iv.tag != key) return@execute
            val own = hasOwnIcon(pm, pkg, cls)
            if (!own && fallback != 0) {
                synchronized(icons) { generic.add(key) }
                main.post { if (iv.tag == key) iv.setImageResource(fallback) }
                return@execute
            }
            val d: Drawable = try {
                if (cls != null) pm.getActivityIcon(ComponentName(pkg, cls)) else pm.getApplicationIcon(pkg)
            } catch (e: Exception) { pm.defaultActivityIcon }
            synchronized(icons) { icons[key] = d }
            main.post { if (iv.tag == key) iv.setImageDrawable(d) }
        }
    }

    fun launch(ctx: Context, pkg: String, cls: String? = null): Boolean {
        val intent = if (cls != null) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
        else ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { Toast.makeText(ctx, "Приложение не установлено", Toast.LENGTH_SHORT).show(); return false }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { ctx.startActivity(intent); try { MediaHub.launched(ctx, pkg) } catch (_: Throwable) {}; true } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось открыть", Toast.LENGTH_SHORT).show(); false
        }
    }

    fun startSafe(ctx: Context, intent: Intent, fallback: Intent? = null, vararg more: Intent) {
        for (i in listOfNotNull(intent, fallback) + more) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        Toast.makeText(ctx, "Экран недоступен на этом устройстве", Toast.LENGTH_SHORT).show()
    }
}
