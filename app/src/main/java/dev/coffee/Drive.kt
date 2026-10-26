package dev.coffee

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

// Trip analytics: pure Kotlin, no Android, unit-tested on the JVM (DriveTest). Fed by TripService's 2 s fixes.

/** Calibration knobs. GPS chips and roads differ — tune here, not in the algorithms. */
object Tune {
    const val STAY_RADIUS_M = 120.0
    const val STAY_MIN_MS = 5 * 60_000L
    const val MIN_TRIP_M = 250.0
    const val MAX_ACC_M = 60f
    const val PLACE_RADIUS_M = 150.0
    const val HARSH_ACCEL = 3.0   // m/s²
    const val HARSH_BRAKE = -3.5  // m/s²
    const val HARSH_TURN = 4.0    // lateral m/s²
    const val SPEEDING = 33.3     // m/s ≈ 120 km/h
}

/** act = Google DetectedActivity type: 0 vehicle, 1 bicycle, 2 foot, 3 still, 7 walking, 8 running, -1 unknown. */
data class Pt(val t: Long, val lat: Double, val lon: Double, val acc: Float = 10f, val speed: Float = -1f, val act: Int = -1)

enum class Mode(val label: String, val cap: Double, val color: Int) {
    WALK("🚶 Walk", 2.5, 0xFF2E7D32.toInt()),
    RUN("🏃 Run", 5.0, 0xFF00897B.toInt()),
    CYCLE("🚲 Cycle", 10.0, 0xFFF9A825.toInt()),
    DRIVE("🚗 Drive", 45.0, 0xFF1565C0.toInt()),
    TRAIN("🚆 Train", 70.0, 0xFF6A1B9A.toInt()),
    FLIGHT("✈️ Flight", 400.0, 0xFFC62828.toInt());
}

enum class Style(val label: String) { CRUISING("Cruising"), MODERATE("Moderate"), SPORTY("Sporty"), AGGRESSIVE("Aggressive") }

sealed interface Seg { val start: Long; val end: Long }
data class Stay(override val start: Long, override val end: Long, val lat: Double, val lon: Double) : Seg
data class Trip(val pts: List<Pt>) : Seg {
    override val start get() = pts.first().t
    override val end get() = pts.last().t
}

fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val h = sin(Math.toRadians(la2 - la1) / 2).pow(2) +
        cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(Math.toRadians(lo2 - lo1) / 2).pow(2)
    return 2 * 6_371_000 * asin(sqrt(h))
}

fun dist(a: Pt, b: Pt) = haversine(a.lat, a.lon, b.lat, b.lon)
fun pathLen(p: List<Pt>) = p.zipWithNext(::dist).sum()

private fun bearing(a: Pt, b: Pt): Double {
    val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
    val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
        sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
    return Math.toDegrees(atan2(y, x))
}

/** Stay-point detection: anywhere you lingered within STAY_RADIUS for STAY_MIN is a stay, the rest are trips. */
fun segment(raw: List<Pt>): List<Seg> {
    val p = raw.filter { it.acc <= Tune.MAX_ACC_M }
    val segs = mutableListOf<Seg>()
    var i = 0
    var from = 0
    while (i < p.size) {
        var j = i + 1
        while (j < p.size && dist(p[i], p[j]) <= Tune.STAY_RADIUS_M) j++
        if (p[j - 1].t - p[i].t >= Tune.STAY_MIN_MS) {
            // The radius swallows the slow approach/departure; hand those edges back to the trips.
            val la = p.subList(i, j).sumOf { it.lat } / (j - i)
            val lo = p.subList(i, j).sumOf { it.lon } / (j - i)
            val core = (i until j).filter { haversine(la, lo, p[it].lat, p[it].lon) <= Tune.STAY_RADIUS_M / 3 }
            val a = core.firstOrNull() ?: i
            val b = core.lastOrNull() ?: (j - 1)
            if (a > from) segs += Trip(p.subList(from, a + 1))
            segs += Stay(p[a].t, p[b].t, la, lo)
            from = b
            i = j
        } else i++
    }
    if (from < p.size - 1) segs += Trip(p.subList(from, p.size))

    // Drop GPS-jitter "trips" and fuse the stays they split.
    val out = mutableListOf<Seg>()
    for (s in segs) {
        if (s is Trip && s.pts.maxOf { dist(s.pts[0], it) } < Tune.MIN_TRIP_M) continue // zig-zag jitter has length but no reach
        val prev = out.lastOrNull()
        if (s is Stay && prev is Stay && haversine(prev.lat, prev.lon, s.lat, s.lon) < 2 * Tune.STAY_RADIUS_M) {
            out[out.size - 1] = Stay(prev.start, s.end, (prev.lat + s.lat) / 2, (prev.lon + s.lon) / 2)
        } else out += s
    }
    return out
}

data class TripStats(
    val distM: Double, val movingS: Double, val avgKmh: Double, val maxKmh: Double, val mode: Mode,
    val style: Style?, val score: Int, val harshAccel: Int, val harshBrake: Int, val harshTurn: Int,
)

/** Speed at the end of each interval: Doppler speed when the chip gave one, else derived. */
private fun speeds(p: List<Pt>) = p.zipWithNext { a, b ->
    if (b.speed >= 0) b.speed.toDouble() else dist(a, b) / max(1.0, (b.t - a.t) / 1000.0)
}

private fun actMode(act: Int) = when (act) {
    0 -> Mode.DRIVE; 1 -> Mode.CYCLE; 2, 7 -> Mode.WALK; 8 -> Mode.RUN; else -> null
}

/** Vehicle detection: on-device activity recognition votes, physics (speed profile) vetoes impossible votes. */
fun classify(p: List<Pt>, p95: Double, median: Double): Mode {
    val bySpeed = when {
        p95 > 70 -> Mode.FLIGHT
        p95 > 45 -> Mode.TRAIN // ponytail: cars rarely hold 160+ km/h; add rail-line proximity if this misfires
        p95 < 2.5 -> Mode.WALK
        p95 < 4.5 && median < 3.5 -> Mode.RUN
        p95 < 9 -> Mode.CYCLE
        else -> Mode.DRIVE
    }
    val voted = p.mapNotNull { actMode(it.act) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return if (voted != null && p95 <= voted.cap * 1.3) voted else bySpeed
}

fun tripStats(p: List<Pt>, forced: Mode? = null): TripStats {
    val d = pathLen(p)
    val v = speeds(p)
    if (v.isEmpty()) return TripStats(d, 0.0, 0.0, 0.0, forced ?: Mode.WALK, null, 100, 0, 0, 0)
    var moving = 0.0
    for (k in v.indices) {
        val dt = (p[k + 1].t - p[k].t) / 1000.0
        if (dt <= 120 && v[k] > 0.5) moving += dt
    }
    val sorted = v.sorted()
    val p95 = sorted[min(sorted.size - 1, (sorted.size * 0.95).toInt())]
    val mode = forced ?: classify(p, p95, sorted[sorted.size / 2])
    val avg = if (moving > 0) d / moving * 3.6 else 0.0
    if (mode != Mode.DRIVE) return TripStats(d, moving, avg, p95 * 3.6, mode, null, 100, 0, 0, 0)

    // Driving style: count harsh events (edge-triggered so one long brake = one event).
    var ha = 0; var hb = 0; var ht = 0
    var inA = false; var inB = false; var inT = false
    for (k in 1 until v.size) {
        val dt = (p[k + 1].t - p[k].t) / 1000.0
        if (dt <= 0 || dt > 5) { inA = false; inB = false; inT = false; continue }
        val a = (v[k] - v[k - 1]) / dt
        if (a > Tune.HARSH_ACCEL) { if (!inA) ha++; inA = true } else inA = false
        if (a < Tune.HARSH_BRAKE) { if (!inB) hb++; inB = true } else inB = false
        if (dist(p[k - 1], p[k]) > 5 && dist(p[k], p[k + 1]) > 5) {
            var dh = abs(bearing(p[k], p[k + 1]) - bearing(p[k - 1], p[k]))
            if (dh > 180) dh = 360 - dh
            val lateral = v[k] * Math.toRadians(dh) / ((p[k + 1].t - p[k - 1].t) / 2000.0)
            // >10 m/s² is beyond tyre grip: a GPS artefact, not a turn
            if (lateral > Tune.HARSH_TURN && lateral < 10) { if (!inT) ht++; inT = true } else inT = false
        }
    }
    val per10km = (ha + hb + ht) / max(d / 1000, 1.0) * 10
    val mv = v.filter { it > 2 }
    val cv = if (mv.size > 2) mv.average().let { m -> sqrt(mv.sumOf { (it - m).pow(2) } / mv.size) / m } else 0.0
    val speeding = v.count { it > Tune.SPEEDING }.toDouble() / v.size
    val score = (100 - per10km * 6 - speeding * 60 - max(0.0, cv - 0.35) * 40).roundToInt().coerceIn(0, 100)
    return TripStats(d, moving, avg, p95 * 3.6, mode, styleOf(score), score, ha, hb, ht)
}

fun styleOf(score: Int) = when {
    score >= 85 -> Style.CRUISING
    score >= 70 -> Style.MODERATE
    score >= 50 -> Style.SPORTY
    else -> Style.AGGRESSIVE
}

data class Span(val place: String, val start: Long, val end: Long)
data class Routine(val place: String, val days: String, val arrive: LocalTime, val avgMin: Long, val count: Int)

private fun fmtDays(d: List<DayOfWeek>) = when (d) {
    DayOfWeek.entries.subList(0, 5) -> "Weekdays"
    DayOfWeek.entries.subList(5, 7) -> "Weekends"
    DayOfWeek.entries -> "Every day"
    else -> d.joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
}

/** A routine = a place you reach on the same days at about the same time, at least 3 times. */
fun routines(v: List<Span>, zone: ZoneId): List<Routine> = v.groupBy { it.place }.flatMap { (place, vs) ->
    vs.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek >= DayOfWeek.SATURDAY }.values.mapNotNull { g ->
        val starts = g.map { Instant.ofEpochMilli(it.start).atZone(zone) }
        val days = starts.groupingBy { it.dayOfWeek }.eachCount().filter { it.value >= 2 }.keys.sorted()
        if (g.size < 3 || days.isEmpty()) return@mapNotNull null
        val arrive = starts.map { it.toLocalTime().toSecondOfDay() }.sorted()[starts.size / 2] // median
        Routine(place, fmtDays(days), LocalTime.ofSecondOfDay(arrive.toLong()), g.sumOf { it.end - it.start } / g.size / 60_000, g.size)
    }
}.sortedByDescending { it.count }

/** Home = where your nights are spent; work = where weekday 9–17 is spent (if not home). */
fun homeWork(v: List<Span>, zone: ZoneId): Pair<String?, String?> {
    val night = mutableMapOf<String, Int>()
    val day = mutableMapOf<String, Int>()
    for (s in v) {
        var t = s.start
        while (t < s.end) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            if (z.hour < 6) night.merge(s.place, 1, Int::plus)
            else if (z.hour in 9..16 && z.dayOfWeek < DayOfWeek.SATURDAY) day.merge(s.place, 1, Int::plus)
            t += 3_600_000
        }
    }
    val home = night.maxByOrNull { it.value }?.key
    val work = day.filterKeys { it != home }.filterValues { it >= 10 }.maxByOrNull { it.value }?.key
    return home to work
}
