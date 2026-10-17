package dev.coffee

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

// Self-update from GitHub releases: the latest release's .apk asset is downloaded and installed through a
// PackageInstaller session. Once Coffee is its own installer of record (after the first update this way), Android 12+
// installs updates without asking; otherwise the system shows its confirm screen.
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
                val uri = dm.getUriForDownloadedFile(id) ?: run {
                    toast(app, "Couldn't download the update. Coffee will offer it again next time you open it")
                    dm.remove(id)
                    return
                }
                thread {
                    runCatching { commit(app, uri) }.onFailure { toast(app, "Update failed: ${it.message}") }
                    dm.remove(id)
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun commit(app: Context, uri: Uri) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        installer.openSession(installer.createSession(params)).use { s ->
            app.contentResolver.openInputStream(uri)!!.use { input -> s.openWrite("coffee.apk", 0, -1).use { input.copyTo(it); s.fsync(it) } }
            ContextCompat.registerReceiver(app, status, IntentFilter(STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Mutable so the installer can fill in the result extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(app, 0, Intent(STATUS).setPackage(app.packageName), flags).intentSender)
        }
    }

    private val status = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                // Not yet the installer of record, or Android 11 and older: the system asks once.
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                PackageInstaller.STATUS_SUCCESS -> c.applicationContext.unregisterReceiver(this)
                else -> {
                    c.applicationContext.unregisterReceiver(this)
                    toast(c, "Update failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
            }
        }
    }

    private fun toast(c: Context, msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }

    private const val STATUS = "dev.coffee.INSTALL_STATUS"
    private const val APK = "application/vnd.android.package-archive"
}
