package dev.coffee

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Coffee notices where you are. Fixes from the fused provider (GPS plus Wi-Fi) are matched against OpenStreetMap
 * through the free Overpass API, and once you've stayed at a mall, café, restaurant, shop… for a few minutes,
 * Coffee texts first about it. Android hands background apps a fix only a few times an hour, so that's the pace.
 */
object Places {
    /** Store setting: "on" once the user turned it on and granted location all the time. */
    const val KEY = "places"
    private const val DWELL = 3 * 60_000L      // seen at the same place this far apart before it counts as being there
    private const val AGAIN = 6 * 3600_000L    // a place is mentioned at most once in this long
    private const val GAP = 30 * 60_000L       // and place texts are at least this far apart
    private val SERVERS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
    private val BIG = setOf("mall", "park", "zoo", "theme park") // you're "at" these only from inside, and a spot inside is a detail
    val scope = MainScope()

    class Place(val id: String, val name: String, val kind: String, val near: String? = null)

    fun permitted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun enabled(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "") == "on"

    /** Idempotent; the request lives in Play services until [stop], a reboot or an app update. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (!enabled(ctx) || !permitted(ctx)) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5 * 60_000L).setMinUpdateIntervalMillis(60_000L).build()
        runCatching { LocationServices.getFusedLocationProviderClient(ctx).requestLocationUpdates(req, updates(ctx)) }
    }

    fun stop(ctx: Context) {
        LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(updates(ctx))
    }

    // Mutable: Play services fills in the fix
    private fun updates(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, PlaceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** One fix: look up the place, and text about it if the user has settled there. Runs on the main thread, so fixes are handled one at a time. */
    suspend fun seen(ctx: Context, loc: Location) {
        if (!enabled(ctx) || loc.accuracy > 100f) return // too vague to tell a café from the shop next door
        val p = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
        val found = runCatching { withContext(Dispatchers.IO) { cachedOrLookup(p, loc) } }.getOrElse { return } // offline: no news, keep the dwell
        val place = arrived(p, found, System.currentTimeMillis()) ?: return
        Store.init(ctx)
        val text = runCatching { withTimeout(25_000) { Store.prompted(note(place)) } }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it != "No response." } ?: fallback(place)
        PushService.checkIn(ctx, text)
    }

    /** The place to text about now, if any, marked as told so the next fix won't repeat it. */
    private fun arrived(p: SharedPreferences, place: Place?, now: Long): Place? {
        if (place?.id != p.getString("here", null)) {
            p.edit().putString("here", place?.id).putLong("since", now).putBoolean("told", false).apply()
            return null
        }
        if (place == null || p.getBoolean("told", false) || now - p.getLong("since", now) < DWELL) return null
        if (now - p.getLong("said:${place.id}", 0) < AGAIN || now - p.getLong("said", 0) < GAP) return null
        val e = p.edit()
        p.all.keys.filter { it.startsWith("said:") && now - p.getLong(it, 0) >= AGAIN }.forEach { e.remove(it) }
        e.putBoolean("told", true).putLong("said", now).putLong("said:${place.id}", now).apply()
        return place
    }

    /** A fix within 30 m of the last lookup in the last half hour reuses it, to go easy on the free servers. */
    private fun cachedOrLookup(p: SharedPreferences, loc: Location): Place? {
        val d = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, p.getFloat("qLat", 0f).toDouble(), p.getFloat("qLon", 0f).toDouble(), d)
        if (d[0] < 30f && System.currentTimeMillis() - p.getLong("qT", 0) < 30 * 60_000L) {
            return p.getString("qPlace", "").orEmpty().takeIf { it.isNotEmpty() }?.let { s ->
                JSONObject(s).run { Place(getString("id"), getString("name"), getString("kind"), optString("near").ifEmpty { null }) }
            }
        }
        val place = lookup(loc.latitude, loc.longitude, loc.accuracy.toInt().coerceIn(25, 60))
        val json = place?.let { JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("near", it.near).toString() }.orEmpty()
        p.edit().putFloat("qLat", loc.latitude.toFloat()).putFloat("qLon", loc.longitude.toFloat()).putLong("qT", System.currentTimeMillis()).putString("qPlace", json).apply()
        return place
    }

    private val FILTERS = listOf(
        "[shop]",
        "[amenity~\"^(cafe|restaurant|fast_food|food_court|bar|pub|biergarten|nightclub|ice_cream|cinema|theatre|library)$\"]",
        "[leisure~\"^(fitness_centre|sports_centre|park)$\"]",
        "[tourism~\"^(museum|gallery|zoo|theme_park)$\"]",
    )

    private class Hit(val id: String, val name: String, val kind: String, val inside: Boolean, val dist: Float)

    /**
     * Blocking; throws when every server fails. Named places whose outline contains the point (they come back with
     * "bounds"), then ones within [radius] metres. Inside a mall or park, that's the place and the nearest spot is a
     * detail; otherwise the building you're in, else the nearest spot.
     */
    fun lookup(lat: Double, lon: Double, radius: Int): Place? {
        val at = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val q = buildString {
            append("[out:json][timeout:10];is_in($at)->.a;(")
            FILTERS.forEach { append("way(pivot.a)[name]$it;rel(pivot.a)[name]$it;") }
            append(");out bb;(")
            FILTERS.forEach { append("nwr(around:$radius,$at)[name]$it;") }
            append(");out center;")
        }
        val body = SERVERS.firstNotNullOfOrNull { runCatching { post(it, q) }.getOrNull() } ?: throw IOException("Overpass unreachable")
        val els = JSONObject(body).getJSONArray("elements")
        val hits = List(els.length()) { els.getJSONObject(it) }.mapNotNull { e ->
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val kind = kindOf(tags) ?: return@mapNotNull null
            val inside = e.has("bounds")
            val c = e.optJSONObject("center") ?: e
            val d = FloatArray(1)
            Location.distanceBetween(lat, lon, c.optDouble("lat", lat), c.optDouble("lon", lon), d)
            Hit("${e.getString("type")}/${e.getLong("id")}", tags.getString("name"), kind, inside, if (inside) 0f else d[0])
        }.sortedBy { it.dist }.distinctBy { it.id } // containing ones sort first, so they win over their "around" copy
        val big = hits.filter { it.kind in BIG && (it.inside || it.kind == "mall") }.minByOrNull { if (it.kind == "mall") 0 else 1 }
        val spot = hits.firstOrNull { it.kind !in BIG }
        return when {
            big != null -> Place(big.id, big.name, big.kind, spot?.let { "${it.name} (${it.kind})" })
            spot != null -> Place(spot.id, spot.name, spot.kind)
            else -> null
        }
    }

    private fun post(server: String, q: String): String {
        val conn = URL(server).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Coffee/${BuildConfig.VERSION_NAME} (github.com/aswin-haridas/coffee)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("data=${URLEncoder.encode(q, "UTF-8")}".toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("Overpass: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun kindOf(t: JSONObject): String? {
        val shop = t.optString("shop")
        val amenity = t.optString("amenity")
        val leisure = t.optString("leisure")
        val tourism = t.optString("tourism")
        return when {
            shop == "mall" || shop == "department_store" -> "mall"
            amenity == "cafe" || shop == "coffee" || shop == "tea" -> "cafe"
            amenity == "restaurant" || amenity == "food_court" -> "restaurant"
            amenity == "fast_food" -> "fast food place"
            amenity == "bar" || amenity == "pub" || amenity == "biergarten" -> "bar"
            amenity == "nightclub" -> "nightclub"
            amenity == "ice_cream" -> "ice cream shop"
            shop == "bakery" || shop == "pastry" || shop == "confectionery" -> "bakery"
            shop == "supermarket" -> "supermarket"
            amenity == "cinema" -> "cinema"
            amenity == "theatre" -> "theatre"
            amenity == "library" -> "library"
            leisure == "fitness_centre" || leisure == "sports_centre" -> "gym"
            leisure == "park" -> "park"
            tourism == "museum" || tourism == "gallery" -> "museum"
            tourism == "zoo" -> "zoo"
            tourism == "theme_park" -> "theme park"
            shop.isNotEmpty() && shop != "vacant" && shop != "no" -> "shop"
            else -> null
        }
    }

    /** Sent to the model as the newest user turn, never shown or kept in the chat. */
    private fun note(p: Place): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val where = "${p.name}, a ${p.kind}" + (p.near?.let { ", near $it" } ?: "")
        return "[Automatic note from my phone, not typed by me] I've been at $where for a few minutes now ($time). " +
            "Text me first about it: one or two short, casual lines, like a friend who noticed. Don't mention my location being tracked or this note."
    }

    private fun fallback(p: Place) = when (p.kind) {
        "cafe" -> "Coffee break at ${p.name}? Good call ☕"
        "restaurant", "fast food place" -> "Eating at ${p.name}? Enjoy!"
        "bar", "nightclub" -> "Out at ${p.name}! Have fun."
        "mall" -> "Spotted you at ${p.name}. Shopping or just wandering?"
        "supermarket", "shop", "bakery" -> "Picking something up at ${p.name}?"
        "gym" -> "Gym time at ${p.name}. You've got this 💪"
        "park" -> "A bit of fresh air at ${p.name}, nice."
        else -> "Looks like you're at ${p.name}. Have a good time!"
    }
}

/** Location fixes from Play services, plus re-registering after a reboot or update, which drop the request. */
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return Places.start(app)
        val loc = LocationResult.extractResult(intent)?.lastLocation ?: return
        // Lookup plus reply stays well inside the 60 s a background broadcast gets
        val pending = goAsync()
        Places.scope.launch {
            try {
                Places.seen(app, loc)
            } finally {
                pending.finish()
            }
        }
    }
}
