package com.goga.carlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * v1.6.3 «Справа: YouTube»: which app is "YouTube" and how to open it — next to the launcher (split screen, public
 * FLAG_ACTIVITY_LAUNCH_ADJACENT; Android 10 honours it only while split-screen is active) or full screen.
 */
object YouTube {
    /** Official app first, then popular alternatives (all ordinary user apps). YouTube Music is not a video app → not here. */
    val SMARTTUBE = listOf("org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
    val KNOWN = listOf("com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.vanced.android.youtube", "com.github.libretube", "org.schabi.newpipe", "org.polymorphicshade.newpipe") + SMARTTUBE
    /**
     * v1.7.0 «Приложение YouTube»: "" automatic, or a package. Automatic = SmartTube FIRST (owner's choice: no ads;
     * note it is an Android-TV app, touch is not officially supported) → ReVanced → YouTube → LibreTube / NewPipe.
     */
    fun choices(c: Context): List<Pair<String, String>> = KNOWN.filter { c.packageManager.getLaunchIntentForPackage(it) != null }.map { it to Tiles.label(c, it) }

    fun isYouTube(pkg: String?): Boolean = pkg != null && (pkg in KNOWN || (pkg.contains("youtube", true) && !pkg.contains("music", true)))

    /** Installed YouTube-like app to open, or null (then the browser shows m.youtube.com). */
    fun pkg(c: Context): String? {
        val pm = c.packageManager
        Prefs.ytApp.takeIf { it.isNotEmpty() && pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        (SMARTTUBE + listOf("app.revanced.android.youtube", "app.rvx.android.youtube") + KNOWN).firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        return try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.firstOrNull { isYouTube(it) }
        } catch (e: Exception) { null }
    }

    fun open(a: Activity, adjacent: Boolean) {
        val p = pkg(a)
        val base = p?.let { a.packageManager.getLaunchIntentForPackage(it) } ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com"))
        val i = base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (p != null) Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED else 0) or
            (if (adjacent) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0))
        try { a.startActivity(i) } catch (e: Exception) {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { Toast.makeText(a, "YouTube и браузер не найдены", Toast.LENGTH_LONG).show(); return }
        }
        if (adjacent && !(Build.VERSION.SDK_INT >= 24 && a.isInMultiWindowMode))
            Toast.makeText(a, "Если экран не разделился — магнитола не умеет «рядом», YouTube открыт на весь экран", Toast.LENGTH_LONG).show()
    }
}
