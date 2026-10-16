package dev.coffee

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos

/** A stay at a place; [lat]/[lon] are null when none of its fixes were precise enough to keep. Times are epoch ms. */
class Visit(val name: String, val kind: String, val near: String, val arrived: Long, val left: Long, val lat: Double?, val lon: Double?)

/** A day from /v1/timeline: the visits in order, and the path as [lat, lon, epoch ms] fixes, oldest first. */
class Day(val visits: List<Visit>, val path: List<DoubleArray>)

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
private fun span(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

/** Metres walked or driven along the path between [from] and [to]. */
private fun moved(path: List<DoubleArray>, from: Long, to: Long): Float {
    val pts = path.filter { it[2] >= from && it[2] <= to }
    val d = FloatArray(1)
    return pts.zipWithNext().sumOf { (a, b) -> Location.distanceBetween(a[0], a[1], b[0], b[1], d); d[0].toDouble() }.toFloat()
}

/** Google-Maps-Timeline-style day view: a sketch of the path, then each place and the moves between them. */
@Composable
internal fun TimelineScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day by produceState<Result<Day>?>(null, date) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { Backend.timeline(date) } }
    }
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Fg) } }, title = "Timeline")
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ date = d.minusDays(1).toString() }) { Icon(painterResource(R.drawable.ph_arrow_left), "Previous day", tint = Muted) }
            Text(
                when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) },
                color = Fg, fontSize = Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            IconButton({ date = d.plusDays(1).toString() }, enabled = d < today) {
                Icon(painterResource(R.drawable.ph_arrow_left), "Next day", tint = if (d < today) Muted else Raised, modifier = Modifier.rotate(180f))
            }
        }
        val r = day
        when {
            r == null -> Spinner(Modifier.padding(24.dp))
            r.isFailure -> Note("Couldn't reach your Coffee server")
            r.getOrThrow().let { it.visits.isEmpty() && it.path.isEmpty() } -> Note("Nothing recorded that day")
            else -> {
                val (visits, path) = r.getOrThrow().let { it.visits to it.path }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (path.size >= 2) item { Sketch(path, visits) }
                    val pinned = visits.filter { it.lat != null }
                    if (pinned.size >= 2) item {
                        // ponytail: Maps takes ~10 stops in a link; longer days show only their first 10
                        val stops = pinned.take(10).map { "${it.lat},${it.lon}" }
                        val url = "https://www.google.com/maps/dir/?api=1&origin=${stops.first()}&destination=${stops.last()}" +
                            if (stops.size > 2) "&waypoints=" + Uri.encode(stops.subList(1, stops.size - 1).joinToString("|")) else ""
                        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open route in Maps", color = Coral) }
                    }
                    visits.forEachIndexed { i, v ->
                        if (i > 0) item {
                            val from = visits[i - 1].left
                            val m = moved(path, from, v.arrived)
                            Text(
                                "Moved" + (if (m >= 100) " · %.1f km".format(m / 1000) else "") + " · ${span(v.arrived - from)}",
                                color = Faint, fontSize = Small, modifier = Modifier.padding(start = 36.dp),
                            )
                        }
                        item { Place(v) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Faint, fontSize = Body, modifier = Modifier.padding(24.dp))

@Composable
private fun Place(v: Visit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised)
            .clickable(enabled = v.lat != null) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${v.lat},${v.lon}?q=${v.lat},${v.lon}(${Uri.encode(v.name)})")))
            }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(if (v.kind.isEmpty()) R.drawable.ph_wifi_high else R.drawable.ph_map_pin), null, tint = Coral, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(v.name.replaceFirstChar { it.uppercase() }, color = Fg, fontSize = Body)
            Text(listOf(v.kind, v.near.takeIf { it.isNotEmpty() }?.let { "near $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), color = Faint, fontSize = Small)
            Text("${clock(v.arrived)} – ${clock(v.left)} · ${span(v.left - v.arrived)}", color = Muted, fontSize = Small)
        }
    }
}

/** The day's path drawn to fit, places as dots. No map tiles: the shape of the day, with Maps a tap away. */
@Composable
private fun Sketch(path: List<DoubleArray>, visits: List<Visit>) = Canvas(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(Raised)) {
    val k = cos(Math.toRadians(path.map { it[0] }.average())) // shrink longitude away from the equator
    val xs = path.map { it[1] * k } + visits.mapNotNull { v -> v.lon?.let { it * k } }
    val ys = path.map { it[0] } + visits.mapNotNull { it.lat }
    val pad = 24.dp.toPx()
    val scale = minOf((size.width - 2 * pad) / (xs.max() - xs.min()).coerceAtLeast(1e-6), (size.height - 2 * pad) / (ys.max() - ys.min()).coerceAtLeast(1e-6))
    // centred both ways, north up
    val ox = (size.width - (xs.max() - xs.min()) * scale) / 2
    val oy = (size.height - (ys.max() - ys.min()) * scale) / 2
    fun at(lat: Double, lon: Double) = Offset((ox + (lon * k - xs.min()) * scale).toFloat(), (size.height - oy - (lat - ys.min()) * scale).toFloat())
    val line = Path().apply { path.forEachIndexed { i, p -> at(p[0], p[1]).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
    drawPath(line, Muted, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    visits.forEach { v -> if (v.lat != null && v.lon != null) drawCircle(Coral, 6.dp.toPx(), at(v.lat, v.lon)) }
}
