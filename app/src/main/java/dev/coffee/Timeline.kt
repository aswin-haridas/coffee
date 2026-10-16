package dev.coffee

import android.content.Intent
import android.location.Location
import android.net.Uri
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** Google-Maps-Timeline-style day view: a map of the day, then each place and the moves between them. */
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
                // Above the list rather than in it, so dragging the map doesn't scroll the list
                DayMap(path, visits, Modifier.fillMaxWidth().height(300.dp))
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** The day on OpenStreetMap: the path as a line, places as pins (tap one for its name). Tiles are cached in the app's cache. */
@Composable
private fun DayMap(path: List<DoubleArray>, visits: List<Visit>, modifier: Modifier) = AndroidView(
    modifier = modifier,
    factory = { c ->
        Configuration.getInstance().apply {
            userAgentValue = c.packageName // OSM's tile servers turn away requests without one
            osmdroidBasePath = c.cacheDir
            osmdroidTileCache = File(c.cacheDir, "tiles")
        }
        MapView(c).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) // dark, like the rest of the app
        }
    },
    update = { map ->
        map.overlays.clear()
        val line = path.map { GeoPoint(it[0], it[1]) }
        if (line.size >= 2) map.overlays += Polyline(map).apply {
            setPoints(line)
            outlinePaint.color = Coral.toArgb()
            outlinePaint.strokeWidth = 10f
        }
        val pins = visits.filter { it.lat != null && it.lon != null }.map { v ->
            Marker(map).apply {
                position = GeoPoint(v.lat!!, v.lon!!)
                title = v.name.replaceFirstChar { it.uppercase() }
                snippet = "${clock(v.arrived)} – ${clock(v.left)}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        }
        map.overlays += pins
        val all = line + pins.map { it.position }
        fun fit() = if (all.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.4f), false)
            else { map.controller.setZoom(16.0); map.controller.setCenter(all.first()) }
        if (map.width > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() } // can't zoom to fit before it has a size
        map.invalidate()
    },
    onRelease = { it.onDetach() },
)
