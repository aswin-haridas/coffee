package dev.coffee

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.runtime.produceState
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Dark tokens from the official "Apps in ChatGPT" Figma (Foundations / Color).
private val Bg = Color(0xFF212121)       // background primary
private val Raised = Color(0xFF303030)   // background secondary: bubbles, composer, cards
private val Pressed = Color(0xFF414141)  // background tertiary: selected rows
private val Fg = Color(0xFFFFFFFF)       // text primary
private val Muted = Color(0xFFCDCDCD)    // text secondary
private val Faint = Color(0xFFAFAFAF)    // text tertiary: placeholders, labels, quiet icons
private val Coral = Color(0xFFCC4A2A)    // brand accent (AA with white), primary button only
private val Teal = Color(0xFF19B48A)

private val Body = 16.sp
private val Small = 14.sp
private val Title = 18.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        setContent {
            MaterialTheme(darkColorScheme(primary = Coral, background = Bg, surface = Raised, surfaceContainerHigh = Raised)) { App() }
        }
    }
}

@Composable
private fun App() {
    var profile by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(profile) { profile = false }
    if (profile) {
        Profile { profile = false }
    } else {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            Drawer(
                onPick = { Store.current = it; scope.launch { drawer.close() } },
                onProfile = { scope.launch { drawer.snapTo(DrawerValue.Closed) }; profile = true },
            )
        }) {
            ChatScreen(onMenu = { scope.launch { drawer.open() } })
        }
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

@Composable
private fun Avatar(size: Dp, font: TextUnit) =
    Box(Modifier.size(size).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
        val initials = Store["name"].split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Text(initials.ifEmpty { "?" }, color = Color.White, fontSize = font)
    }

/** Flat 56dp app bar: nav icon, title, trailing actions. */
@Composable
private fun TopBar(nav: @Composable () -> Unit, title: String, actions: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        nav()
        Text(title, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }

@Composable
private fun ChatScreen(onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val chat = Store.current
    val streaming = Store.job != null
    var input by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            image = withContext(Dispatchers.IO) { Store.saveImage(ctx, uri) }
            if (image == null) Toast.makeText(ctx, "Couldn't read that image", Toast.LENGTH_SHORT).show()
        }
    }
    var autoSend by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send(text: String) {
        if (text.isBlank() && image == null) return
        Store.send(text.trim(), image)
        input = ""
        image = null
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
        TopBar(nav = { IconButton(onMenu) { Icon(painterResource(R.drawable.ph_list), "Menu", tint = Fg) } }, title = "Coffee") {
            IconButton({ Store.current = null }) { Icon(painterResource(R.drawable.ph_note_pencil), "New chat", tint = Fg) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat == null || chat.msgs.isEmpty()) {
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
                Messages(chat, streaming)
            }
        }
        Composer(
            value = input, onChange = { input = it }, focus = focus, streaming = streaming,
            image = image, onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = { image = null },
            onSend = { send(input) }, onMic = { listen(false) }, onVoice = { listen(true) },
        )
    }
}

@Composable
private fun DeleteDialog(chat: Chat, onDone: () -> Unit) = AlertDialog(
    onDismissRequest = onDone,
    containerColor = Raised,
    title = { Text("Delete chat?", color = Fg) },
    text = { Text(chat.title, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    confirmButton = { TextButton({ Store.delete(chat); onDone() }) { Text("Delete", color = Color(0xFFFF8A80)) } },
    dismissButton = { TextButton(onDone) { Text("Cancel", color = Fg) } },
)

@Composable
private fun Messages(chat: Chat, streaming: Boolean) {
    val msgs = chat.msgs
    // reverseLayout pins the list to the bottom, so streaming text grows upward with no scroll bookkeeping
    LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(msgs.size, key = { msgs.lastIndex - it }) { r ->
            val i = msgs.lastIndex - r
            val m = msgs[i]
            val last = i == msgs.lastIndex
            if (m.user) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    m.image?.let { Thumb(it, Modifier.size(180.dp).clip(RoundedCornerShape(18.dp))) }
                    if (m.text.isNotEmpty()) SelectionContainer {
                        Text(
                            m.text, color = Fg, fontSize = Body, lineHeight = 24.sp,
                            modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(Raised).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                Assistant(chat, m, last, streaming && last)
            }
        }
    }
}

@Composable
private fun Assistant(chat: Chat, m: Msg, last: Boolean, live: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (live && m.text.isEmpty()) {
            // Figma "isLoading": a pulsing dot. Scale read inside graphicsLayer, so it animates without recomposing.
            val pulse = rememberInfiniteTransition(label = "loading").animateFloat(.6f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Box(Modifier.padding(vertical = 6.dp).size(12.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }.clip(CircleShape).background(Fg))
        } else {
            SelectionContainer { Text(m.text, color = if (m.err) Color(0xFFFF8A80) else Fg, fontSize = Body, lineHeight = 24.sp) }
        }
        if (!live) {
            Row {
                IconButton({
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("reply", m.text))
                }, Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ph_copy), "Copy", tint = Faint, modifier = Modifier.size(16.dp)) }
                if (last) IconButton({ Store.regenerate(chat) }, Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ph_arrow_clockwise), "Regenerate", tint = Faint, modifier = Modifier.size(16.dp))
                }
            }
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
    image: String?, onAttach: () -> Unit, onRemoveImage: () -> Unit,
    onSend: () -> Unit, onMic: () -> Unit, onVoice: () -> Unit,
) = Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Raised).clickable(onClick = onAttach), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ph_plus), "Attach image", tint = Fg, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Raised)) {
    if (image != null) Box(Modifier.padding(start = 8.dp, top = 8.dp)) {
        Thumb(image, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(Bg).border(1.dp, Raised, CircleShape).clickable(onClick = onRemoveImage),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ph_x), "Remove image", tint = Fg, modifier = Modifier.size(12.dp)) }
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
        val (icon, label, action) = when {
            streaming -> Triple(R.drawable.ph_stop, "Stop", Store::stop)
            value.isBlank() && image == null -> Triple(R.drawable.ph_waveform, "Voice", onVoice)
            else -> Triple(R.drawable.ph_arrow_up, "Send", onSend)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(Coral).clickable { action() }, contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
    }
}

/** Figma "Sidebar": search pill + new chat, "Chats" label, 48dp rows; profile row pinned at the bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(onPick: (Chat?) -> Unit, onProfile: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Chat?>(null) }
    ModalDrawerSheet(Modifier.fillMaxWidth(.8f), drawerShape = RectangleShape, drawerContainerColor = Bg, windowInsets = WindowInsets(0)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(44.dp).clip(CircleShape).background(Raised).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ph_magnifying_glass), null, tint = Faint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        query, { query = it }, Modifier.weight(1f),
                        textStyle = TextStyle(Fg, Body), cursorBrush = SolidColor(Fg), singleLine = true,
                        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search", color = Faint, fontSize = Body); inner() } },
                    )
                }
                IconButton({ onPick(null) }) { Icon(painterResource(R.drawable.ph_note_pencil), "New chat", tint = Fg) }
            }
            val list = if (query.isBlank()) Store.chats else Store.chats.filter { it.title.contains(query, true) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                if (list.isNotEmpty()) item { Text("Chats", color = Faint, fontSize = Small, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)) }
                items(list, key = { it.id }) { c ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (c === Store.current) Pressed else Color.Transparent)
                            .combinedClickable(onLongClick = { deleting = c }) { onPick(c) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(c.title, color = Fg, fontSize = Body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onProfile).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(32.dp, 13.sp)
                Spacer(Modifier.width(12.dp))
                Text(Store["name"].ifBlank { "Settings" }, color = Fg, fontSize = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    deleting?.let { c -> DeleteDialog(c) { deleting = null } }
}

private class Field(val key: String, val title: String, val icon: Int, val hint: String, val sub: () -> String)

private val NameField = Field("name", "Name", R.drawable.ph_pencil_simple, "Your name") { Store["name"] }
private val Personal = listOf(
    Field("system", "Personalization", R.drawable.ph_smiley, "Custom instructions for every chat") {
        Store["system"].lineSequence().first().ifBlank { "Custom instructions" }
    },
)
private val Account = listOf(
    Field("model", "Model", R.drawable.ph_cpu, DEFAULT_MODEL) { Store.model },
)

@Composable
private fun Profile(onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Field?>(null) }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Fg) } }, title = "Settings")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { editing = NameField }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(56.dp, 22.sp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(Store["name"].ifBlank { "Add your name" }, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold)
                    Text("Tap to edit", color = Faint, fontSize = Small)
                }
            }
            Section("My Coffee")
            Group(Personal) { editing = it }
            Section("Account")
            Group(Account) { editing = it }
            Spacer(Modifier.height(24.dp))
        }
    }
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
