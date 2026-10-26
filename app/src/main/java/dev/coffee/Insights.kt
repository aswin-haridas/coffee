package dev.coffee

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Kinds of place (places.go's kindOf) that count as sights rather than errands. */
private val sights = setOf("museum", "zoo", "theme park", "park", "attraction", "viewpoint", "landmark", "place of worship")
private val hm = DateTimeFormatter.ofPattern("HH:mm")

private class Report(val trips: List<TripRow>, val visits: List<Visit>?)

/** What the trips (on the phone) and visits (on the server) add up to over the last week, month or year. */
@Composable
internal fun InsightsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableIntStateOf(30) }
    var attempt by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val report by produceState<Report?>(null, days, attempt) {
        value = null
        value = withContext(Dispatchers.IO) {
            val db = Trips.db(ctx).apply { process() }
            val from = LocalDate.now().minusDays(days - 1L)
            Report(
                db.trips(from.atStartOfDay(zone).toInstant().toEpochMilli(), Long.MAX_VALUE),
                runCatching { Backend.visits(from.toString(), LocalDate.now().toString()) }.getOrNull()?.filter { it.status >= 0 },
            )
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Fg) } }, title = "Insights")
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "Week", 30 to "Month", 365 to "Year").forEach { (d, label) ->
                Text(label, color = if (d == days) Fg else Muted, fontSize = Small,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (d == days) Coral else Raised).clickable { days = d }
                        .padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        val r = report ?: return@Column Spinner(Modifier.padding(24.dp))
        val local = { t: Long -> Instant.ofEpochMilli(t).atZone(zone) }
        val trips = r.trips
        val dist = trips.sumOf { it.dist }
        val moving = trips.sumOf { it.moving }
        fun kmh(m: Double, s: Double) = if (s > 0) "%.0f km/h".format(m / s * 3.6) else "–"
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trips.isEmpty()) item { Card("Trips") { Text("No trips recorded yet. Turn on Trips in Settings, then go somewhere.", color = Faint, fontSize = Small) } }
            else {
                val byDay = trips.groupBy { local(it.start).toLocalDate() }
                item {
                    Card("Overview") {
                        Line("Distance", "%.1f km".format(dist / 1000))
                        Line("Time moving", span((moving * 1000).toLong()))
                        Line("Average speed", kmh(dist, moving))
                        Line("Trips", "${trips.size}")
                        Line("Per active day", "%.1f km · ${byDay.size} days".format(dist / 1000 / max(1, byDay.size)))
                        byDay.maxByOrNull { (_, ts) -> ts.sumOf { it.dist } }?.let { (d, ts) ->
                            Line("Longest day", "${d.format(DateTimeFormatter.ofPattern("d MMM"))} · %.1f km".format(ts.sumOf { it.dist } / 1000))
                        }
                    }
                }
                item {
                    Card("How you travel") {
                        trips.groupBy { it.mode }.toList().sortedByDescending { (_, ts) -> ts.sumOf { it.dist } }.forEach { (m, ts) ->
                            val d = ts.sumOf { it.dist }
                            Line(m.label, "%.1f km · ${ts.size}×".format(d / 1000))
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Bg)) {
                                Box(Modifier.fillMaxWidth((d / dist).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(m.color)))
                            }
                        }
                    }
                }
                val drives = trips.filter { it.mode == Mode.DRIVE }
                if (drives.isNotEmpty()) item {
                    val dd = drives.sumOf { it.dist }
                    val score = (drives.sumOf { it.score * it.dist } / dd).toInt()
                    fun per100(f: (TripRow) -> Int) = "%.1f".format(drives.sumOf(f) / dd * 100_000)
                    Card("Driving") {
                        Line("Style", "${styleOf(score).label} · $score/100")
                        Line("Average driving speed", kmh(dd, drives.sumOf { it.moving }))
                        Line("Top speed", "%.0f km/h".format(drives.maxOf { it.max }))
                        Line("Harsh accel / brake / turn per 100 km", "${per100 { it.ha }} / ${per100 { it.hb }} / ${per100 { it.ht }}")
                        Line("Driven at night (9 pm – 5 am)", "%.0f%%".format(drives.filter { local(it.start).hour !in 5..20 }.sumOf { it.dist } / dd * 100))
                        Style.entries.forEach { s ->
                            val n = drives.count { it.style == s }
                            if (n > 0) Line(s.label, "$n trips · %.0f%%".format(n * 100.0 / drives.size), quiet = true)
                        }
                    }
                }
            }
            val visits = r.visits
            if (visits == null) item {
                Card("Places") {
                    Text("Couldn't reach your Coffee server.", color = Faint, fontSize = Small)
                    TextButton({ attempt++ }) { Text("Try again", color = Fg) }
                }
            } else if (visits.isNotEmpty()) {
                val names = visits.associate { it.place to it.name }
                val counts = visits.groupingBy { it.place }.eachCount().toList().sortedByDescending { it.second }
                val cities = visits.mapNotNull { it.city.ifEmpty { null } }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
                val seen = visits.filter { it.kind in sights }.distinctBy { it.place }
                item {
                    Card("Places") {
                        counts.first().let { (p, n) -> Line("Most visited", "${names[p]} · $n×") }
                        Line("Different places", "${counts.size}")
                        Line("Cities", "${cities.size}")
                        if (cities.isNotEmpty()) Text(cities.joinToString(" · ") { it.first }, color = Faint, fontSize = Small)
                        if (seen.isNotEmpty()) {
                            Sub("Sights")
                            Text(seen.joinToString(" · ") { it.name }, color = Faint, fontSize = Small)
                        }
                        Sub("Top places")
                        counts.take(5).forEach { (p, n) -> Line(names[p].orEmpty(), "$n×", quiet = true) }
                    }
                }
                item {
                    val spans = visits.map { Span(it.place, it.arrived, it.left) }
                    val (home0, work0) = homeWork(spans, zone)
                    // Wi-Fi-saved spots beat a guess from the hours
                    val home = "spot/home".takeIf { names.containsKey(it) } ?: home0
                    val work = "spot/office".takeIf { names.containsKey(it) } ?: work0?.takeIf { it != home }
                    fun commute(a: String?, b: String?): String? {
                        if (a == null || b == null) return null
                        // a trip that set off within 20 minutes of leaving a and arrived within 20 of reaching b
                        val ts = visits.zipWithNext().filter { (x, y) -> x.place == a && y.place == b }.mapNotNull { (x, y) ->
                            trips.firstOrNull { it.start in x.left - 20 * 60_000..x.left + 20 * 60_000 && it.end in y.arrived - 20 * 60_000..y.arrived + 20 * 60_000 }
                        }
                        if (ts.size < 2) return null
                        val leave = ts.map { local(it.start).toLocalTime().toSecondOfDay() }.sorted()[ts.size / 2]
                        return "leave ~${LocalTime.ofSecondOfDay(leave.toLong()).format(hm)} · ${span(ts.map { it.end - it.start }.average().toLong())}"
                    }
                    Card("Routines") {
                        Line("Home", home?.let { names[it] } ?: "–")
                        Line("Work", work?.let { names[it] } ?: "–")
                        commute(home, work)?.let { Line("To work", it) }
                        commute(work, home)?.let { Line("Back home", it) }
                        routines(spans, zone).take(8).forEach { rt ->
                            Text("${names[rt.place]} · ${rt.days}, around ${rt.arrive.format(hm)}, ${span(rt.avgMin * 60_000)} (${rt.count}×)",
                                color = Muted, fontSize = Small, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }

@Composable
private fun Sub(text: String) = Text(text, color = Fg, fontSize = Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Line(k: String, v: String, quiet: Boolean = false) = Row(Modifier.fillMaxWidth()) {
    Text(k, color = if (quiet) Faint else Muted, fontSize = Small, modifier = Modifier.weight(1f))
    Text(v, color = Fg, fontSize = Small, fontWeight = if (quiet) FontWeight.Normal else FontWeight.SemiBold)
}
