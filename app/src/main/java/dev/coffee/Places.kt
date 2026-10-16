package dev.coffee

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Coffee notices where you are. Fixes from the fused provider (GPS plus Wi-Fi) go to coffee-backend, which keeps
 * them in Postgres, matches them against OpenStreetMap, and once you've settled at a café, mall, shop… pushes
 * Coffee's text about it like any check-in. Android hands background apps a fix only a few times an hour, so
 * that's the pace. Fixes that can't be sent wait here and go with the next upload. The newest fix also carries the
 * Wi-Fi networks around it, which is how the server knows home and office (saved with [wifi] from Settings).
 */
object Places {
    /** Store setting: "on" once the user turned it on and granted location all the time. */
    const val KEY = "places"
    private const val MAX_QUEUED = 1000 // the most the server takes in one upload
    private val lock = Any()

    fun permitted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun enabled(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "") == "on"

    /** Idempotent; the request lives in Play services until [stop], a reboot or an app update. Also sends any queued fixes. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (!enabled(ctx) || !permitted(ctx)) return
        // Batched: fixes are still taken every few minutes, but handed over (waking the app and the radio to upload)
        // at most every 10, which is most of the battery cost. The newest fix of a batch stays fresh enough for place texts.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5 * 60_000L).setMinUpdateIntervalMillis(60_000L)
            .setMaxUpdateDelayMillis(10 * 60_000L).build()
        runCatching { LocationServices.getFusedLocationProviderClient(ctx).requestLocationUpdates(req, updates(ctx)) }
        thread { upload(ctx) }
    }

    fun stop(ctx: Context) {
        LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(updates(ctx))
    }

    // Mutable: Play services fills in the fix
    private fun updates(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, PlaceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * BSSIDs from the phone's last Wi-Fi scan, if it was within 2 minutes of [atNanos] (elapsed realtime). Android scans
     * on its own, often for location itself, so this costs nothing; empty with Wi-Fi off or no recent scan.
     */
    @SuppressLint("MissingPermission")
    fun wifi(ctx: Context, atNanos: Long = SystemClock.elapsedRealtimeNanos()): List<String> {
        if (!permitted(ctx)) return emptyList()
        val scan = runCatching { ctx.getSystemService(WifiManager::class.java).scanResults }.getOrNull().orEmpty()
        return scan.filter { kotlin.math.abs(it.timestamp * 1000 - atNanos) < 120_000_000_000L }.map { it.BSSID }.distinct()
    }

    fun queue(ctx: Context, fixes: List<Location>) = synchronized(lock) {
        val p = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
        val q = runCatching { JSONArray(p.getString("queue", "[]")) }.getOrDefault(JSONArray())
        val newest = fixes.maxByOrNull { it.elapsedRealtimeNanos }
        fixes.forEach {
            val f = JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("accuracy", it.accuracy.toDouble()).put("t", it.time)
            if (it === newest) wifi(ctx, it.elapsedRealtimeNanos).takeIf { w -> w.isNotEmpty() }?.let { w -> f.put("wifi", JSONArray(w)) }
            q.put(f)
        }
        val kept = JSONArray().apply { for (i in maxOf(0, q.length() - MAX_QUEUED) until q.length()) put(q.get(i)) }
        p.edit().putString("queue", kept.toString()).commit()
    }

    /** Blocking; call off the main thread. Sends the queue, and forgets what the server took. */
    fun upload(ctx: Context) {
        val p = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
        val sent = synchronized(lock) { p.getString("queue", "[]").orEmpty() }
        val n = runCatching { JSONArray(sent).length() }.getOrDefault(0)
        if (n == 0) return
        // Kept on network errors and 5xx; dropped on 400, since a bad batch would block every later one
        if (!runCatching { Backend.uploadFixes(sent) }.getOrDefault(false)) return
        synchronized(lock) {
            val q = runCatching { JSONArray(p.getString("queue", "[]")) }.getOrDefault(JSONArray())
            val rest = JSONArray().apply { for (i in n until q.length()) put(q.get(i)) } // queued while this was sending
            p.edit().putString("queue", rest.toString()).commit()
        }
    }
}

/** Location fixes from Play services, plus re-registering after a reboot or update, which drop the request. */
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return Places.start(app)
        val fixes = LocationResult.extractResult(intent)?.locations.orEmpty()
        if (fixes.isEmpty()) return
        Places.queue(app, fixes)
        // The upload's timeouts keep it well inside the time a background broadcast gets
        val pending = goAsync()
        thread {
            try {
                Places.upload(app)
            } finally {
                pending.finish()
            }
        }
    }
}
