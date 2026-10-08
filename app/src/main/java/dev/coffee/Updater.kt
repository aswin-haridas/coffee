package dev.coffee

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.URL

// Self-update from GitHub releases: the latest release's .apk asset is downloaded and handed to the system installer.
object Updater {
    class Release(val version: String, val apk: String)

    // Blocking; call off the main thread. Null when up to date or offline.
    fun check(): Release? = runCatching {
        val o = JSONObject(URL("https://api.github.com/repos/aswin-haridas/coffee/releases/latest").readText())
        val v = o.getString("tag_name").removePrefix("v")
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
        Release(v, apk.getString("browser_download_url")).takeIf { newer(v, BuildConfig.VERSION_NAME) }
    }.getOrNull()

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun install(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(r.apk)).setTitle("Coffee ${r.version}").setMimeType(APK))
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id) ?: return
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private const val APK = "application/vnd.android.package-archive"
}
