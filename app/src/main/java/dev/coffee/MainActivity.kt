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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SentimentSatisfied
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Bg = Color(0xFF000000)
private val Raised = Color(0xFF2B2B2B)
private val Line = Color(0xFF3A3A3A)
private val DrawerBg = Color(0xFF212121)
private val CardBg = Color(0xFF3D3D3D)
private val Coral = Color(0xFFCC4A2A)
private val Teal = Color(0xFF19B48A)
private val Fg = Color(0xFFECECEC)
private val Muted = Color(0xFFADADAD)

// Two text sizes (body / body-small) plus one title size, per the type guidelines.
private val Body = 16.sp
private val Small = 14.sp
private val Title = 20.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        setContent {
            MaterialTheme(darkColorScheme(primary = Coral, background = Bg, surface = DrawerBg, surfaceContainerHigh = DrawerBg)) { App() }
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
}

@Composable
private fun Circle(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, content: @Composable () -> Unit) =
    Box(
        modifier.size(size).clip(CircleShape).background(Raised).border(1.dp, Line, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content() }

@Composable
private fun Avatar(size: Dp, font: TextUnit) =
    Box(Modifier.size(size).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
        val initials = Store["name"].split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Text(initials.ifEmpty { "?" }, color = Color.White, fontSize = font)
    }

@Composable
private fun ChatScreen(onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val chat = Store.current
    val streaming = Store.job != null
    var input by rememberSaveable { mutableStateOf("") }
    var autoSend by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send(text: String) {
        if (text.isBlank()) return
        Store.send(text.trim())
        input = ""
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Circle("Menu", onMenu) {
                Canvas(Modifier.size(16.dp, 10.dp)) {
                    val w = 1.8.dp.toPx()
                    drawLine(Fg, Offset(0f, w / 2), Offset(size.width, w / 2), w, StrokeCap.Round)
                    drawLine(Fg, Offset(0f, size.height - w / 2), Offset(size.width * .6f, size.height - w / 2), w, StrokeCap.Round)
                }
            }
            Spacer(Modifier.weight(1f))
            Circle("New chat", { Store.current = null }) { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Fg, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat == null || chat.msgs.isEmpty()) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    listOf(
                        Triple(Icons.Outlined.Edit, "Write or edit", "Help me write "),
                        Triple(Icons.Outlined.Lightbulb, "Explain something", "Explain "),
                    ).forEach { (icon, label, prompt) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                input = prompt
                                focus.requestFocus()
                                keyboard?.show()
                            }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = Fg, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
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
            onSend = { send(input) }, onMic = { listen(false) }, onVoice = { listen(true) },
        )
    }
}

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
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    SelectionContainer {
                        Text(
                            m.text, color = Fg, fontSize = Body, lineHeight = 24.sp,
                            modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(Raised).padding(horizontal = 14.dp, vertical = 8.dp),
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
        // "Thinking" shimmers in the composer while streaming, so the reply row stays empty until text arrives
        if (m.text.isNotEmpty()) SelectionContainer { Text(m.text, color = if (m.err) Coral else Fg, fontSize = Body, lineHeight = 24.sp) }
        if (!live) {
            Row(Modifier.padding(top = 2.dp)) {
                IconButton({
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("reply", m.text))
                }, Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy", tint = Muted, modifier = Modifier.size(16.dp)) }
                if (last) IconButton({ Store.regenerate(chat) }, Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Refresh, "Regenerate", tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String, onChange: (String) -> Unit, focus: FocusRequester, streaming: Boolean,
    onSend: () -> Unit, onMic: () -> Unit, onVoice: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(shape).background(Raised).border(1.dp, Line, shape)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = TextStyle(Fg, Body), cursorBrush = SolidColor(Fg), maxLines = 6,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && streaming) {
                        // alpha read inside graphicsLayer: the shimmer animates without recomposing
                        val alpha = rememberInfiniteTransition(label = "thinking").animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
                        Text("Thinking", color = Muted, fontSize = Body, modifier = Modifier.graphicsLayer { this.alpha = alpha.value })
                    } else if (value.isEmpty()) {
                        Text("Ask Coffee", color = Muted, fontSize = Body)
                    }
                    inner()
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onMic, Modifier.size(36.dp)) { Icon(Icons.Outlined.Mic, "Dictate", tint = Fg, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(6.dp))
            val (icon, label, action) = when {
                streaming -> Triple(Icons.Filled.Stop, "Stop", Store::stop)
                value.isBlank() -> Triple(Icons.Filled.GraphicEq, "Voice", onVoice)
                else -> Triple(Icons.Filled.ArrowUpward, "Send", onSend)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(Coral).clickable { action() }, contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(onPick: (Chat?) -> Unit, onProfile: () -> Unit) {
    var query by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Chat?>(null) }
    val focus = remember { FocusRequester() }
    ModalDrawerSheet(Modifier.fillMaxWidth(.8f), drawerShape = RectangleShape, drawerContainerColor = DrawerBg, windowInsets = WindowInsets(0)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val q = query
                    if (q == null) {
                        Text("Coffee", color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    } else {
                        BasicTextField(
                            q, { query = it }, Modifier.weight(1f).focusRequester(focus),
                            textStyle = TextStyle(Fg, Body), cursorBrush = SolidColor(Fg), singleLine = true,
                            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search chats", color = Muted, fontSize = Body); inner() } },
                        )
                        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                    Circle(if (q == null) "Search" else "Close search", { query = if (q == null) "" else null }) {
                        Icon(if (q == null) Icons.Outlined.Search else Icons.Outlined.Close, null, tint = Fg, modifier = Modifier.size(20.dp))
                    }
                }
                val list = query.let { q -> if (q.isNullOrBlank()) Store.chats else Store.chats.filter { it.title.contains(q, true) } }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)) {
                    items(list, key = { it.id }) { c ->
                        Text(
                            c.title, color = Fg, fontSize = Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (c === Store.current) Raised else Color.Transparent)
                                .combinedClickable(onLongClick = { deleting = c }) { onPick(c) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(DrawerBg)
                    .navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.heightIn(min = 44.dp).clip(CircleShape).background(Coral).clickable { onPick(null) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Chat", color = Color.White, fontSize = Body, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Circle("Settings", onProfile, size = 44.dp) { Avatar(32.dp, 14.sp) }
            }
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton({ Store.delete(c); deleting = null }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = Fg) } },
        )
    }
}

private class Field(val key: String, val title: String, val icon: ImageVector, val hint: String, val sub: () -> String)

private val NameField = Field("name", "Name", Icons.Outlined.Edit, "Your name") { Store["name"] }
private val Personal = listOf(
    Field("system", "Personalization", Icons.Outlined.SentimentSatisfied, "Custom instructions for every chat") {
        Store["system"].lineSequence().first().ifBlank { "Custom instructions" }
    },
)
private val Account = listOf(
    Field("model", "Model", Icons.Outlined.Memory, DEFAULT_MODEL) { Store.model },
)

@Composable
private fun Profile(onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Field?>(null) }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Circle("Back", onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Fg, modifier = Modifier.size(20.dp)) }
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(CircleShape).clickable { editing = NameField }) {
                    Avatar(84.dp, 32.sp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(Color(0xFF235C4D)).border(3.dp, Bg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Edit, "Edit name", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(Store["name"].ifBlank { "Add your name" }, color = Fg, fontSize = Title, fontWeight = FontWeight.Bold)
            }
        }
        Section("My Coffee")
        Group(Personal) { editing = it }
        Section("Account")
        Group(Account) { editing = it }
        Spacer(Modifier.height(24.dp))
    }
    editing?.let { f ->
        var v by remember(f) { mutableStateOf(Store[f.key]) }
        val multi = f.key == "system"
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.title) },
            text = {
                OutlinedTextField(
                    v, { v = it }, placeholder = { Text(f.hint) }, singleLine = !multi, minLines = if (multi) 4 else 1,
                )
            },
            confirmButton = { TextButton({ Store[f.key] = v.trim(); editing = null }) { Text("Save", color = Coral) } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel", color = Fg) } },
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, color = Muted, fontSize = Small, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))

@Composable
private fun Group(rows: List<Field>, onClick: (Field) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    rows.forEachIndexed { i, f ->
        val top = if (i == 0) 20.dp else 4.dp
        val bottom = if (i == rows.lastIndex) 20.dp else 4.dp
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(CardBg).clickable { onClick(f) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(f.icon, null, tint = Fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(f.title, color = Fg, fontSize = Body)
                Text(f.sub(), color = Muted, fontSize = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
