package dev.coffee

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** A place visit from Google, for the server's visits: [place] is google/<placeId>, or spot/home and spot/office; [name] is "" when Google didn't say. */
data class ImportVisit(val place: String, val name: String, val arrived: Long, val left: Long, val lat: Double, val lon: Double)

class Imported(val trips: List<TripRow>, val visits: List<ImportVisit>, val path: List<DoubleArray>)

/**
 * Google Maps Timeline exports, parsed into Coffee's trips (for trips.db) and visits and path fixes (for the server).
 * Reads the on-device export (Timeline.json, `semanticSegments`) and the old Takeout files (`timelineObjects`).
 * Google keeps no fixes fine enough for speed or driving style, so imported trips have the average speed and no style.
 */
object GoogleTimeline {
    // ponytail: whole file in memory as one JSONObject; a stream parser if years of history run out of heap
    fun parse(json: String): Imported {
        val root = JSONObject(json)
        val trips = mutableListOf<TripRow>()
        val visits = mutableListOf<ImportVisit>()
        val path = mutableListOf<DoubleArray>()
        root.optJSONArray("semanticSegments")?.objects()?.forEach { s ->
            val t0 = time(s.optString("startTime")) ?: return@forEach
            val t1 = time(s.optString("endTime")) ?: return@forEach
            s.optJSONObject("visit")?.takeIf { it.optInt("hierarchyLevel") == 0 }?.optJSONObject("topCandidate")?.let { c ->
                val (lat, lon) = latLng(c.optJSONObject("placeLocation")?.optString("latLng")) ?: return@let
                visits += ImportVisit(place(c.optString("placeId"), c.optString("semanticType")), "", t0, t1, lat, lon)
            }
            s.optJSONObject("activity")?.let { a -> trip(t0, t1, a.optDouble("distanceMeters"), a.optJSONObject("topCandidate")?.optString("type"))?.let(trips::add) }
            s.optJSONArray("timelinePath")?.objects()?.forEach { p ->
                val ll = latLng(p.optString("point")) ?: return@forEach
                path += doubleArrayOf(ll.first, ll.second, (time(p.optString("time")) ?: return@forEach).toDouble())
            }
        }
        root.optJSONArray("timelineObjects")?.objects()?.forEach { o ->
            o.optJSONObject("placeVisit")?.let { v ->
                val l = v.optJSONObject("location") ?: return@let
                val (t0, t1) = duration(v) ?: return@let
                visits += ImportVisit(place(l.optString("placeId"), l.optString("semanticType")), l.optString("name"), t0, t1,
                    l.optDouble("latitudeE7") / 1e7, l.optDouble("longitudeE7") / 1e7)
            }
            o.optJSONObject("activitySegment")?.let { a ->
                val (t0, t1) = duration(a) ?: return@let
                trip(t0, t1, a.optDouble("distance"), a.optString("activityType"))?.let(trips::add)
            }
        }
        return Imported(trips, visits.filter { !it.lat.isNaN() && it.left >= it.arrived }, path)
    }

    /** Blocking; call off the main thread. Adds the files' trips to trips.db and sends their visits and path to the server; says what came in. */
    fun import(ctx: Context, uris: List<Uri>): String {
        val all = uris.map { u -> parse(ctx.contentResolver.openInputStream(u)!!.use { it.reader().readText() }) }
        val trips = Trips.db(ctx).addImported(all.flatMap { it.trips })
        val visits = all.flatMap { it.visits }
        val path = all.flatMap { it.path }
        val sent = runCatching {
            visits.chunked(500).forEach { Backend.importVisits(it, emptyList()) }
            path.chunked(4000).forEach { Backend.importVisits(emptyList(), it) }
        }.isSuccess
        return "$trips new trips · " + if (sent) "${visits.size} visits sent" else "visits not sent, couldn't reach your Coffee server"
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun time(s: String?) = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private fun duration(o: JSONObject): Pair<Long, Long>? {
        val d = o.optJSONObject("duration") ?: return null
        fun at(k: String) = time(d.optString("${k}Timestamp")) ?: d.optString("${k}TimestampMs").toLongOrNull()
        return (at("start") ?: return null) to (at("end") ?: return null)
    }

    /** "10.0271°, 76.3082°" or "geo:10.0271,76.3082". */
    private fun latLng(s: String?): Pair<Double, Double>? {
        val n = Regex("-?\\d+(\\.\\d+)?").findAll(s ?: return null).map { it.value.toDouble() }.toList()
        return if (n.size == 2) n[0] to n[1] else null
    }

    private fun place(id: String, type: String) = when (type) {
        "HOME", "INFERRED_HOME" -> "spot/home" // the same place a Wi-Fi-saved home is
        "WORK", "INFERRED_WORK" -> "spot/office"
        else -> "google/$id"
    }

    private fun trip(t0: Long, t1: Long, dist: Double, type: String?): TripRow? {
        val mode = when (type.orEmpty().uppercase()) {
            "WALKING", "ON_FOOT", "HIKING" -> Mode.WALK
            "RUNNING" -> Mode.RUN
            "CYCLING", "ON_BICYCLE" -> Mode.CYCLE
            "IN_TRAIN", "IN_SUBWAY", "IN_TRAM" -> Mode.TRAIN
            "FLYING" -> Mode.FLIGHT
            "STILL", "UNKNOWN_ACTIVITY_TYPE", "" -> return null
            else -> Mode.DRIVE // car, bus, taxi, motorcycle, ferry…
        }
        if (dist.isNaN() || dist < Tune.MIN_TRIP_M || t1 <= t0) return null
        val kmh = dist / ((t1 - t0) / 1000.0) * 3.6
        return TripRow(t0, t1, dist, (t1 - t0) / 1000.0, kmh, kmh, mode, null, 0, 0, 0, 0)
    }
}
