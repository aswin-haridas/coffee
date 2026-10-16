package dev.coffee

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.runtime.produceState
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Near-black take on the "Apps in ChatGPT" Figma dark tokens (Foundations / Color).
internal val Bg = Color(0xFF0D0D0D)       // background primary
internal val Raised = Color(0xFF212121)   // background secondary: bubbles, composer, cards
internal val Fg = Color(0xFFFFFFFF)       // text primary
internal val Muted = Color(0xFFCDCDCD)    // text secondary
internal val Faint = Color(0xFFAFAFAF)    // text tertiary: placeholders, labels, quiet icons
internal val Coral = Color(0xFFCC4A2A)    // brand accent (AA with white): primary button, the user's bubbles
private val Teal = Color(0xFF19B48A)
private val Mine = Coral                 // the user's bubbles, with Fg text

internal val Body = 16.sp
internal val Small = 14.sp
internal val Title = 18.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        PushService.start(this)
        if (Store[Places.KEY] == "on") { if (Places.permitted(this)) Places.start(this) else Store[Places.KEY] = "" } // revoked in system settings
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            MaterialTheme(darkColorScheme(primary = Coral, background = Bg, surface = Raised, surfaceContainerHigh = Raised)) { App() }
        }
    }

    // While the chat is on screen, check-ins just appear in it; opening the app clears their notification.
    override fun onResume() {
        super.onResume()
        visible = true
        getSystemService(NotificationManager::class.java).cancel(PushService.ID)
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    companion object {
        var visible = false
    }
}

@Composable
private fun App() {
    var screen by rememberSaveable { mutableStateOf("chat") }
    BackHandler(screen != "chat") { screen = "chat" }
    when (screen) {
        "profile" -> Profile { screen = "chat" }
        "timeline" -> TimelineScreen { screen = "chat" }
        else -> ChatScreen(onProfile = { screen = "profile" }, onTimeline = { screen = "timeline" })
    }
    UpdatePrompt()
}

@Composable
private fun UpdatePrompt() {
    val ctx = LocalContext.current
    val release by produceState<Updater.Release?>(null) { value = withContext(Dispatchers.IO) { Updater.check() } }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val r = release ?: return
    if (dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        containerColor = Raised,
        title = { Text("Update available", color = Fg) },
        text = { Text("Coffee ${r.version} is out. You have ${BuildConfig.VERSION_NAME}.", color = Muted) },
        confirmButton = {
            TextButton({
                dismissed = true
                Updater.install(ctx, r)
                Toast.makeText(ctx, "Downloading update…", Toast.LENGTH_SHORT).show()
            }) { Text("Update", color = Fg) }
        },
        dismissButton = { TextButton({ dismissed = true }) { Text("Later", color = Muted) } },
    )
}

/** The user's photo if they set one, else their initials. */
@Composable
private fun Avatar(size: Dp, font: TextUnit, modifier: Modifier = Modifier) =
    Box(modifier.size(size).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
        val photo = Store["avatar"]
        if (photo.isNotBlank()) Thumb(photo, Modifier.fillMaxSize()) else {
            val initials = Store["name"].split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            Text(initials.ifEmpty { "?" }, color = Color.White, fontSize = font)
        }
    }

/** Flat 56dp app bar: nav icon, title, trailing actions. */
@Composable
internal fun TopBar(nav: @Composable () -> Unit, title: String, actions: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        nav()
        Text(title, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }

@Composable
private fun ChatScreen(onProfile: () -> Unit, onTimeline: () -> Unit) {
    val ctx = LocalContext.current
    val streaming = Store.job != null
    var input by rememberSaveable { mutableStateOf("") }
    var images by rememberSaveable { mutableStateOf(listOf<String>()) }
    var reply by rememberSaveable { mutableStateOf("") } // text of the message being replied to
    val scope = rememberCoroutineScope()
    // ponytail: 4 per pick, each ~150 KB on the wire; raise if the backend takes bigger requests
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val saved = withContext(Dispatchers.IO) { uris.map { Store.saveImage(ctx, it) } }
            images += saved.filterNotNull()
            if (null in saved) Toast.makeText(ctx, "Couldn't read some images", Toast.LENGTH_SHORT).show()
        }
    }
    // System camera app writes into a cache file we share via FileProvider; no CAMERA permission needed.
    val shot = remember { File(ctx.cacheDir, "camera/shot.jpg").apply { parentFile!!.mkdirs() } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            val saved = withContext(Dispatchers.IO) { Store.saveImage(ctx, Uri.fromFile(shot)).also { shot.delete() } }
            if (saved == null) Toast.makeText(ctx, "Couldn't read that photo", Toast.LENGTH_SHORT).show() else images += saved
        }
    }
    fun takePhoto() = try {
        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", shot))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No camera app on this device", Toast.LENGTH_SHORT).show()
    }
    var autoSend by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = remember { LazyListState() }

    fun send(text: String) {
        if (text.isBlank() && images.isEmpty()) return
        Store.send(text.trim(), images, reply)
        input = ""
        images = emptyList()
        reply = ""
        scope.launch { listState.animateScrollToItem(0) }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        if (autoSend) send(heard) else input = "$input $heard".trim()
    }
    fun listen(thenSend: Boolean) {
        autoSend = thenSend
        try {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No speech recognizer on this device", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onProfile, Modifier.semantics { contentDescription = "Settings" }) { Avatar(30.dp, 12.sp) } }, title = "Coffee") {
            if (Store[Places.KEY] == "on") IconButton(onTimeline) { Icon(painterResource(R.drawable.ph_map_pin), "Timeline", tint = Muted) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (Store.msgs.isEmpty()) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    listOf(
                        Triple(R.drawable.ph_pencil_simple, "Write or edit", "Help me write "),
                        Triple(R.drawable.ph_lightbulb, "Explain something", "Explain "),
                    ).forEach { (icon, label, prompt) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                input = prompt
                                focus.requestFocus()
                                keyboard?.show()
                            }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(icon), null, tint = Faint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = Fg, fontSize = Body)
                        }
                    }
                }
            } else {
                Messages(streaming, listState) {
                    reply = it
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        }
        Composer(
            value = input, onChange = { input = it }, focus = focus, streaming = streaming,
            images = images, onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onCamera = { takePhoto() },
            onRemoveImage = { images -= it }, reply = reply, onCancelReply = { reply = "" },
            onSend = { send(input) }, onMic = { listen(false) }, onVoice = { listen(true) },
        )
    }
}

@Composable
private fun Messages(streaming: Boolean, listState: LazyListState, onReply: (String) -> Unit) {
    val msgs = Store.msgs
    LaunchedEffect(msgs.size) { listState.animateScrollToItem(0) }
    // reverseLayout pins the list to the bottom, so streaming text grows upward with no scroll bookkeeping
    LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(msgs.size, key = { msgs.lastIndex - it }) { r ->
            val i = msgs.lastIndex - r
            val m = msgs[i]
            val last = i == msgs.lastIndex
            val live = streaming && last
            val swipe = if (live) Modifier else Modifier.swipeToReply { onReply(m.text.ifBlank { "Photo" }) }
            if (m.user) {
                Column(swipe.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(4.dp)) {
                        val size = if (m.images.size == 1) 180.dp else 120.dp
                        m.images.forEach { Thumb(it, Modifier.size(size).clip(RoundedCornerShape(18.dp))) }
                    }
                    if (m.text.isNotEmpty() || m.reply.isNotEmpty()) Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(Mine).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (m.reply.isNotEmpty()) Quote(m.reply, Fg, Modifier.padding(bottom = 4.dp))
                        if (m.text.isNotEmpty()) SelectionContainer { Text(m.text, color = Fg, fontSize = Body, lineHeight = 24.sp) }
                    }
                }
            } else {
                // Regenerating a check-in would replace it with a reply to an older message
                Assistant(m, canRedo = last && msgs.getOrNull(i - 1)?.user == true, live = live, modifier = swipe)
            }
        }
    }
}

/** Braille-dot spinner, the terminal kind, in place of "Loading…" text. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val frame by produceState(0) { while (true) { delay(80); value++ } }
    Text("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[frame % 10].toString(), color = Faint, fontSize = Body, lineHeight = 24.sp, modifier = modifier.semantics { contentDescription = "Loading" })
}

@Composable
private fun Assistant(m: Msg, canRedo: Boolean, live: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val bubble = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(Raised).padding(horizontal = 12.dp, vertical = 8.dp)
        if (live && m.text.isEmpty()) {
            Spinner(bubble)
        } else {
            Column(bubble) {
                if (m.reply.isNotEmpty()) Quote(m.reply, Fg, Modifier.padding(bottom = 4.dp))
                SelectionContainer { Text(m.text, color = if (m.err) Color(0xFFFF8A80) else Fg, fontSize = Body, lineHeight = 24.sp) }
            }
        }
        if (!live) {
            Row {
                IconButton({
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("reply", m.text))
                }, Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ph_copy), "Copy", tint = Faint, modifier = Modifier.size(16.dp)) }
                if (canRedo) IconButton(Store::regenerate, Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ph_arrow_clockwise), "Regenerate", tint = Faint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** The message being replied to, cut to two lines. */
@Composable
private fun Quote(text: String, color: Color, modifier: Modifier = Modifier) = Text(
    text, color = color.copy(alpha = .7f), fontSize = Small, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    modifier = modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .1f)).padding(horizontal = 8.dp, vertical = 4.dp),
)

/** Drag right past 64dp (a tick confirms it) and let go to reply; the bubble springs back either way. */
@Composable
private fun Modifier.swipeToReply(onReply: () -> Unit): Modifier {
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reply by rememberUpdatedState(onReply)
    val limit = with(LocalDensity.current) { 64.dp.toPx() }
    return offset { IntOffset(dx.value.roundToInt(), 0) }.pointerInput(Unit) {
        fun release() = scope.launch { dx.animateTo(0f) }
        detectHorizontalDragGestures(
            onDragEnd = { if (dx.value >= limit) reply(); release() },
            onDragCancel = { release() },
        ) { change, amount ->
            change.consume()
            val to = (dx.value + amount).coerceIn(0f, limit * 1.25f)
            if (dx.value < limit && to >= limit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { dx.snapTo(to) }
        }
    }
}

/** Downsampled off the main thread; images are stored at <=1024px so half size is plenty for thumbnails. */
@Composable
private fun Thumb(path: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
    }
    bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier.background(Raised))
}

/** Figma "Composer": [+] then a 44dp pill — attachment preview, text, mic, 32dp primary button (voice / send / stop). */
@Composable
private fun Composer(
    value: String, onChange: (String) -> Unit, focus: FocusRequester, streaming: Boolean,
    images: List<String>, onAttach: () -> Unit, onCamera: () -> Unit, onRemoveImage: (String) -> Unit, reply: String, onCancelReply: () -> Unit,
    onSend: () -> Unit, onMic: () -> Unit, onVoice: () -> Unit,
) = Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.size(44.dp).clip(CircleShape).background(Raised).clickable { menu = true }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ph_plus), "Attach image", tint = Fg, modifier = Modifier.size(20.dp))
        DropdownMenu(menu, { menu = false }, containerColor = Raised) {
            DropdownMenuItem({ Text("Take photo", color = Fg) }, { menu = false; onCamera() })
            DropdownMenuItem({ Text("Choose photos", color = Fg) }, { menu = false; onAttach() })
        }
    }
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Raised)) {
    if (reply.isNotEmpty()) Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Quote(reply, Fg, Modifier.weight(1f))
        IconButton(onCancelReply, Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ph_x), "Cancel reply", tint = Faint, modifier = Modifier.size(16.dp)) }
    }
    if (images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp), Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            Box {
                Thumb(image, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(Bg).border(1.dp, Raised, CircleShape).clickable { onRemoveImage(image) },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ph_x), "Remove image", tint = Fg, modifier = Modifier.size(12.dp)) }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value, onChange, Modifier.weight(1f).focusRequester(focus),
            textStyle = TextStyle(Fg, Body, lineHeight = 22.sp), cursorBrush = SolidColor(Fg), maxLines = 6,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Ask anything", color = Faint, fontSize = Body)
                    inner()
                }
            },
        )
        IconButton(onMic, Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ph_microphone), "Dictate", tint = Faint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(4.dp))
        // Send wins over Stop, so a second text can go out while Coffee is still thinking
        val (icon, label, action) = when {
            value.isNotBlank() || images.isNotEmpty() -> Triple(R.drawable.ph_arrow_up, "Send", onSend)
            streaming -> Triple(R.drawable.ph_stop, "Stop", Store::stop)
            else -> Triple(R.drawable.ph_waveform, "Voice", onVoice)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(Coral).clickable { action() }, contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
    }
}

private class Field(val key: String, val title: String, val icon: Int, val hint: String, val sub: () -> String)

private val NameField = Field("name", "Name", R.drawable.ph_pencil_simple, "Your name") { Store["name"] }
private val Personal = listOf(
    Field("system", "Personalization", R.drawable.ph_smiley, "Custom instructions for every chat") {
        Store["system"].lineSequence().first().ifBlank { "Custom instructions" }
    },
    Field(Places.KEY, "Places", R.drawable.ph_map_pin, "") {
        if (Store[Places.KEY] == "on") "Coffee texts when you're at a café, mall, shop…" else "Off"
    },
    Field("spots", "Home & office", R.drawable.ph_wifi_high, "") {
        val saved = listOf("home", "office").filter { Store["spot_$it"] == "on" }
        if (saved.isEmpty()) "Recognise them by their Wi-Fi" else saved.joinToString(" and ").replaceFirstChar { it.uppercase() } + " saved"
    },
)

@Composable
private fun Profile(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Field?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { Store.saveImage(ctx, uri) }
            if (path == null) Toast.makeText(ctx, "Couldn't read that image", Toast.LENGTH_SHORT).show() else Store.setAvatar(path)
        }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var placesInfo by remember { mutableStateOf(false) }
    var spots by remember { mutableStateOf(false) }
    fun saveSpot(name: String) {
        spots = false
        val wifi = Places.wifi(ctx)
        if (wifi.isEmpty()) return Toast.makeText(ctx, "No Wi-Fi networks nearby. Turn on Wi-Fi and try again in a minute", Toast.LENGTH_LONG).show()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Backend.setSpot(name, wifi) }.isSuccess }
            if (ok) Store["spot_$name"] = "on"
            Toast.makeText(ctx, if (ok) "Saved ${wifi.size} networks as $name" else "Couldn't reach your Coffee server", Toast.LENGTH_SHORT).show()
        }
    }
    fun placesOn() {
        Store[Places.KEY] = "on"
        Places.start(ctx)
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) placesOn() else Toast.makeText(ctx, "Places needs location access all the time", Toast.LENGTH_LONG).show()
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        when {
            r[Manifest.permission.ACCESS_FINE_LOCATION] != true -> Toast.makeText(ctx, "Places needs precise location", Toast.LENGTH_LONG).show()
            Build.VERSION.SDK_INT >= 29 -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) // asked on its own, after foreground
            else -> placesOn()
        }
    }
    fun togglePlaces() {
        when {
            Store[Places.KEY] == "on" -> { Store[Places.KEY] = ""; Places.stop(ctx) }
            Places.permitted(ctx) -> placesOn()
            else -> placesInfo = true
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Fg) } }, title = "Settings")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { editing = NameField }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(56.dp, 22.sp, Modifier.clickable { if (Store["avatar"].isBlank()) pick() else photoMenu = true }.semantics { contentDescription = "Profile photo" })
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Raised).border(2.dp, Bg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ph_pencil_simple), null, tint = Fg, modifier = Modifier.size(11.dp)) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(Store["name"].ifBlank { "Add your name" }, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold)
                    Text("Tap to edit", color = Faint, fontSize = Small)
                }
            }
            Section("My Coffee")
            Group(Personal.filter { it.key != "spots" || Store[Places.KEY] == "on" }) {
                when (it.key) {
                    Places.KEY -> togglePlaces()
                    "spots" -> spots = true
                    else -> editing = it
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (photoMenu) AlertDialog(
        onDismissRequest = { photoMenu = false },
        containerColor = Raised,
        title = { Text("Profile photo", color = Fg) },
        confirmButton = { TextButton({ photoMenu = false; pick() }) { Text("Change", color = Fg) } },
        dismissButton = { TextButton({ photoMenu = false; Store.setAvatar("") }) { Text("Remove", color = Muted) } },
    )
    if (placesInfo) AlertDialog(
        onDismissRequest = { placesInfo = false },
        containerColor = Raised,
        title = { Text("Places", color = Fg) },
        text = {
            Text(
                "Coffee checks your location in the background, even when the app is closed, and sends it to your Coffee server, " +
                    "which keeps your location history and looks up the place on OpenStreetMap. When you've been at a café, restaurant, " +
                    "mall or shop for a few minutes, Coffee texts you about it." +
                    if (Build.VERSION.SDK_INT >= 30) "\n\nOn the next screens, allow precise location, then \"Allow all the time\"." else "",
                color = Muted,
            )
        },
        confirmButton = {
            TextButton({
                placesInfo = false
                foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Continue", color = Fg) }
        },
        dismissButton = { TextButton({ placesInfo = false }) { Text("Cancel", color = Muted) } },
    )
    if (spots) AlertDialog(
        onDismissRequest = { spots = false },
        containerColor = Raised,
        title = { Text("Home & office", color = Fg) },
        text = {
            Text(
                "Saves the Wi-Fi networks around you right now, so Coffee knows you're there even when GPS can't tell. " +
                    "Do it once at each place; saving again replaces what was saved.",
                color = Muted,
            )
        },
        confirmButton = {
            Row {
                TextButton({ saveSpot("home") }) { Text("I'm home", color = Fg) }
                TextButton({ saveSpot("office") }) { Text("I'm at the office", color = Fg) }
            }
        },
        dismissButton = { TextButton({ spots = false }) { Text("Cancel", color = Muted) } },
    )
    editing?.let { f ->
        var v by remember(f) { mutableStateOf(Store[f.key]) }
        val multi = f.key == "system"
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Raised,
            title = { Text(f.title, color = Fg) },
            text = { OutlinedTextField(v, { v = it }, placeholder = { Text(f.hint) }, singleLine = !multi, minLines = if (multi) 4 else 1) },
            confirmButton = { TextButton({ Store[f.key] = v.trim(); editing = null }) { Text("Save", color = Fg) } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, color = Faint, fontSize = Small, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))

@Composable
private fun Group(rows: List<Field>, onClick: (Field) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    rows.forEachIndexed { i, f ->
        val top = if (i == 0) 16.dp else 4.dp
        val bottom = if (i == rows.lastIndex) 16.dp else 4.dp
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(top, top, bottom, bottom)).background(Raised).clickable { onClick(f) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(f.icon), null, tint = Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(f.title, color = Fg, fontSize = Body)
                Text(f.sub(), color = Faint, fontSize = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
