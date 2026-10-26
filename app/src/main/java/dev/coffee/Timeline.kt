package dev.coffee

import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A stay at a place; [lat]/[lon] are null when none of its fixes were precise enough to keep. Times are epoch ms.
 * [place] is its OpenStreetMap element (or spot/home, spot/office); [status] the user's say: 1 confirmed, -1 wasn't there, 0 not asked. */
class Visit(
    val id: Long, val place: String, val name: String, val kind: String, val near: String, val city: String,
    val arrived: Long, val left: Long, val status: Int, val lat: Double?, val lon: Double?,
)

/** A day from /v1/timeline: the visits in order, the path as [lat, lon, epoch ms] fixes, oldest first, and the moves between visits snapped to the roads as lines of [lat, lon]. */
class Day(val visits: List<Visit>, val path: List<DoubleArray>, val route: List<List<DoubleArray>>, val trips: List<Pair<TripRow, List<Pt>>> = emptyList())

/** Which visit the map is on, or the whole day when [i] is null. No equals, so asking again re-centres even if nothing changed. */
private class Focus(val i: Int?)

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
internal fun span(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

/** Metres walked or driven along the path between [from] and [to]. */
private fun moved(path: List<DoubleArray>, from: Long, to: Long): Float {
    val pts = path.filter { it[2] >= from && it[2] <= to }
    val d = FloatArray(1)
    return pts.zipWithNext().sumOf { (a, b) -> Location.distanceBetween(a[0], a[1], b[0], b[1], d); d[0].toDouble() }.toFloat()
}

/** Google-Maps-Timeline-style day view: a map of the day, then each place and the moves between them. */
@Composable
internal fun TimelineScreen(onBack: () -> Unit, onInsights: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val trips = Trips.db(ctx)
    var tripsChanged by remember { mutableIntStateOf(0) }
    // Corrections show at once, without fetching the day again
    var statuses by remember { mutableStateOf(mapOf<Long, Int>()) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var editVisit by remember { mutableStateOf<Visit?>(null) }
    var editTrip by remember { mutableStateOf<TripRow?>(null) }
    fun fix(v: Visit, status: Int? = null, name: String? = null) = scope.launch {
        val ok = withContext(Dispatchers.IO) { runCatching { Backend.fixVisit(v.id, status, name) }.isSuccess }
        if (!ok) return@launch Toast.makeText(ctx, "Couldn't reach your Coffee server", Toast.LENGTH_SHORT).show()
        status?.let { statuses = statuses + (v.id to it) }
        name?.let { names = names + (v.place to it) }
    }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var attempt by remember { mutableIntStateOf(0) }
    var focus by remember(date) { mutableStateOf(Focus(null)) }
    val server by produceState<Result<Day>?>(null, date, attempt) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { Backend.timeline(date) } }
    }
    val local by produceState(emptyList<Pair<TripRow, List<Pt>>>(), date, tripsChanged) {
        value = withContext(Dispatchers.IO) {
            trips.process()
            val from = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            trips.trips(from, from + 86_400_000L).map { it to trips.path(it) }
        }
    }
    val day = server?.map { d ->
        Day(d.visits.map { v ->
            Visit(v.id, v.place, names[v.place] ?: v.name, v.kind, v.near, v.city, v.arrived, v.left, statuses[v.id] ?: v.status, v.lat, v.lon)
        }, d.path, d.route, local)
    }
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Fg) } }, title = "Timeline") {
            IconButton(onInsights) { Icon(painterResource(R.drawable.ph_lightbulb), "Insights", tint = Muted) }
        }
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
            r.isFailure -> Column {
                Note("Couldn't reach your Coffee server.")
                TextButton({ attempt++ }, Modifier.padding(horizontal = 12.dp)) { Text("Try again", color = Fg) }
            }
            r.getOrThrow().let { it.visits.isEmpty() && it.path.isEmpty() && it.trips.isEmpty() } -> Note("Nothing recorded that day")
            else -> {
                val (visits, path) = r.getOrThrow().let { it.visits to it.path }
                // Above the list rather than in it, so dragging the map doesn't scroll the list
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))) {
                    DayMap(r.getOrThrow(), focus, Modifier.fillMaxSize())
                    Text("© OpenStreetMap © CARTO", color = Faint, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                    if (focus.i != null) IconButton({ focus = Focus(null) }, Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape).background(Raised)) {
                        Icon(painterResource(R.drawable.ph_map_pin), "Show the whole day", tint = Fg, modifier = Modifier.size(20.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dayTrips = r.getOrThrow().trips.map { it.first }
                    if (dayTrips.isNotEmpty()) item {
                        val m = dayTrips.sumOf { it.dist }
                        val s = dayTrips.sumOf { it.moving }
                        Text("${visits.count { it.status >= 0 }} places · %.1f km · ${span((s * 1000).toLong())} moving · avg %.0f km/h".format(m / 1000, if (s > 0) m / s * 3.6 else 0.0),
                            color = Muted, fontSize = Small)
                    } else if (path.size >= 2) item {
                        val km = moved(path, path.first()[2].toLong(), path.last()[2].toLong()) / 1000
                        Text("${visits.size} places · %.1f km moved".format(km), color = Muted, fontSize = Small)
                    }
                    val pinned = visits.filter { it.lat != null }
                    if (pinned.size >= 2) item {
                        // ponytail: Maps takes ~10 stops in a link; longer days show only their first 10
                        val stops = pinned.take(10).map { "${it.lat},${it.lon}" }
                        val url = "https://www.google.com/maps/dir/?api=1&origin=${stops.first()}&destination=${stops.last()}" +
                            if (stops.size > 2) "&waypoints=" + Uri.encode(stops.subList(1, stops.size - 1).joinToString("|")) else ""
                        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open route in Maps", color = Coral) }
                    }
                    fun tripsIn(from: Long, to: Long) = dayTrips.filter { it.start >= from && it.start < to }
                    visits.forEachIndexed { i, v ->
                        val from = if (i > 0) visits[i - 1].left else Long.MIN_VALUE
                        val recorded = tripsIn(from, v.arrived)
                        recorded.forEach { t -> item { TripLine(t) { editTrip = t } } }
                        if (i > 0 && recorded.isEmpty()) item {
                            val m = moved(path, from, v.arrived)
                            Text(
                                "Moved" + (if (m >= 100) " · %.1f km".format(m / 1000) else "") + " · ${span(v.arrived - from)}",
                                color = Faint, fontSize = Small, modifier = Modifier.padding(start = 36.dp),
                            )
                        }
                        item {
                            Place(i + 1, v, focus.i == i, { focus = Focus(i) }, { editVisit = v }, { fix(v, status = 1) }, { fix(v, status = -1) })
                        }
                    }
                    tripsIn(visits.lastOrNull()?.left ?: Long.MIN_VALUE, Long.MAX_VALUE).forEach { t -> item { TripLine(t) { editTrip = t } } }
                }
            }
        }
    }
    editVisit?.let { v ->
        var name by remember(v.id) { mutableStateOf(v.name) }
        AlertDialog(
            onDismissRequest = { editVisit = null },
            containerColor = Raised,
            title = { Text("Correct this visit", color = Fg) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Place name") })
                    Text("Renaming changes every visit to this place.", color = Faint, fontSize = Small, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton({
                    editVisit = null
                    fix(v, status = if (v.status < 0) 0 else 1, name = name.trim().takeIf { it.isNotEmpty() && it != v.name })
                }) { Text(if (v.status < 0) "I was here" else "Save", color = Fg) }
            },
            dismissButton = {
                if (v.status >= 0) TextButton({ editVisit = null; fix(v, status = -1) }) { Text("I wasn't here", color = Muted) }
            },
        )
    }
    editTrip?.let { t ->
        AlertDialog(
            onDismissRequest = { editTrip = null },
            containerColor = Raised,
            title = { Text("How did you travel?", color = Fg) },
            text = {
                Column {
                    Mode.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                editTrip = null
                                scope.launch { withContext(Dispatchers.IO) { trips.setMode(t, m) }; tripsChanged++ }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(t.mode == m, null)
                            Text(m.label, color = Fg, fontSize = Body)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ editTrip = null }) { Text("Close", color = Fg) } },
            dismissButton = {
                TextButton({
                    editTrip = null
                    scope.launch { withContext(Dispatchers.IO) { trips.delete(t) }; tripsChanged++ }
                }) { Text("Delete trip", color = Muted) }
            },
        )
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Faint, fontSize = Body, modifier = Modifier.padding(24.dp))

/** A visit, numbered like its pin; tapping it shows it on the map, holding it corrects it. Unreviewed visits ask yes or no. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Place(n: Int, v: Visit, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onConfirm: () -> Unit, onReject: () -> Unit) {
    val gone = v.status < 0
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised)
            .then(if (selected) Modifier.border(1.dp, Coral, RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onLongClick = onEdit, onClick = { if (v.lat != null) onClick() }).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (v.lat != null && !gone) Coral else Bg), contentAlignment = Alignment.Center) {
            Text("$n", color = Fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name.replaceFirstChar { it.uppercase() }, color = if (gone) Faint else Fg, fontSize = Body,
                textDecoration = if (gone) TextDecoration.LineThrough else null)
            Text(listOf(v.kind, v.near.takeIf { it.isNotEmpty() }?.let { "near $it" }.orEmpty(), v.city).filter { it.isNotEmpty() }.joinToString(" · "), color = Faint, fontSize = Small)
            Text("${clock(v.arrived)} – ${clock(v.left)} · ${span(v.left - v.arrived)}", color = Muted, fontSize = Small)
        }
        when (v.status) {
            0 -> Row {
                IconButton(onConfirm, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ph_check_circle_fill), "I was here", tint = Muted, modifier = Modifier.size(20.dp)) }
                IconButton(onReject, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ph_x), "I wasn't here", tint = Faint, modifier = Modifier.size(18.dp)) }
            }
            1 -> Icon(painterResource(R.drawable.ph_check_circle_fill), "Confirmed", tint = Coral, modifier = Modifier.size(20.dp))
        }
    }
}

/** A recorded trip between places: how you went, how far and fast, and for a drive how smoothly. Tapping it corrects it. */
@Composable
private fun TripLine(t: TripRow, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.padding(start = 3.dp).size(4.dp, 40.dp).clip(RoundedCornerShape(2.dp)).background(Color(t.mode.color)))
    Spacer(Modifier.width(21.dp))
    Column {
        Text("${t.mode.label} · %.1f km · ${span(t.end - t.start)}".format(t.dist / 1000), color = Muted, fontSize = Small)
        Text("avg %.0f · top %.0f km/h".format(t.avg, t.max) + (t.style?.let { " · ${it.label} driving ${t.score}/100" } ?: ""), color = Faint, fontSize = Small)
    }
}

/** The day on OpenStreetMap's dark CARTO tiles: the moves as a line along the roads, visits as numbered pins
 * (tap one for its name). Fits the whole day, or zooms to the [focus]ed visit. Tiles are cached in the app's cache. */
@Composable
private fun DayMap(day: Day, focus: Focus, modifier: Modifier) = AndroidView(
    modifier = modifier,
    factory = { c ->
        Configuration.getInstance().apply {
            userAgentValue = c.packageName // tile servers turn away requests without one
            osmdroidBasePath = c.cacheDir
            osmdroidTileCache = File(c.cacheDir, "tiles")
        }
        MapView(c).apply {
            setTileSource(XYTileSource("CartoDark", 0, 20, 256, ".png", arrayOf("a", "b", "c").map { "https://$it.basemaps.cartocdn.com/dark_all/" }.toTypedArray()))
            isTilesScaledToDpi = true // the 256 px tiles are unreadably small on a phone otherwise
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    },
    update = { map ->
        val dp = map.resources.displayMetrics.density
        map.overlays.clear()
        // An older server sends no route: draw the raw path then
        val lines = day.route.ifEmpty { listOf(day.path) }.map { l -> l.map { GeoPoint(it[0], it[1]) } }.filter { it.size >= 2 }
        for ((color, width) in listOf(Bg to 9f, Coral to 5f)) { // a dark casing under the line keeps it readable over roads
            lines.forEach { l ->
                map.overlays += Polyline(map).apply {
                    setPoints(l)
                    outlinePaint.color = color.toArgb()
                    outlinePaint.strokeWidth = width * dp
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
            }
        }
        val recorded = day.trips.map { (t, pts) -> t.mode.color to pts.map { GeoPoint(it.lat, it.lon) } }.filter { it.second.size >= 2 }
        recorded.forEach { (color, l) ->
            map.overlays += Polyline(map).apply {
                setPoints(l)
                outlinePaint.color = color
                outlinePaint.strokeWidth = 3 * dp
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
        }
        val pins = day.visits.mapIndexedNotNull { i, v ->
            if (v.lat == null || v.lon == null || v.status < 0) null else i to Marker(map).apply {
                position = GeoPoint(v.lat, v.lon)
                title = v.name.replaceFirstChar { it.uppercase() }
                snippet = "${clock(v.arrived)} – ${clock(v.left)}"
                icon = pin(map.resources, i + 1)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
        }.toMap()
        map.overlays += pins.values
        val all = lines.flatten() + recorded.flatMap { it.second } + pins.values.map { it.position }
        val target = focus.i?.let { pins[it] }
        fun fit() {
            when {
                target != null -> { map.controller.animateTo(target.position, 17.0, 400L); target.showInfoWindow() }
                all.size >= 2 -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.3f), true)
                all.isNotEmpty() -> { map.controller.setZoom(16.0); map.controller.setCenter(all.first()) }
            }
        }
        if (map.width > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() } // can't zoom to fit before it has a size
        map.invalidate()
    },
    onRelease = { it.onDetach() },
)

/** A coral disc with the visit's number, ringed in the background colour so it stands off the line. */
private fun pin(res: Resources, n: Int): BitmapDrawable {
    val dp = res.displayMetrics.density
    val s = (26 * dp).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Bg.toArgb(); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.color = Coral.toArgb(); c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * dp, p)
    p.color = Fg.toArgb(); p.textSize = 12 * dp; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
    c.drawText("$n", s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(res, b)
}
