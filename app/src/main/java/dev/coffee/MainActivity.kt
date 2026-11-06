package dev.coffee

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Near-black take on the "Apps in ChatGPT" Figma dark tokens (Foundations / Color).
internal val Bg = Color(0xFF0D0D0D)       // background primary
internal val Raised = Color(0xFF212121)   // background secondary: bubbles, composer, cards
internal val Fg = Color(0xFFFFFFFF)       // text primary
internal val Muted = Color(0xFFCDCDCD)    // text secondary
internal val Faint = Color(0xFFAFAFAF)    // text tertiary: placeholders, labels, quiet icons
internal val Coral = Color(0xFFCC4A2A)    // brand accent (AA with white): primary button, the user's bubbles

internal val Body = 16.sp
internal val Small = 14.sp
internal val Title = 18.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        setContent {
            MaterialTheme(darkColorScheme(primary = Coral, background = Bg, surface = Raised, surfaceContainerHigh = Raised)) { App() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Lock.locked) Store.sync()
    }
}

@Composable
private fun App() {
    if (Lock.locked) return LockScreen()
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { Store.sync() }
    BackHandler(settings) { settings = false }
    if (settings) Profile { settings = false } else ChatScreen { settings = true }
}

@Composable
private fun TopBar(nav: @Composable () -> Unit, title: String) =
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        nav()
        Text(title, color = Fg, fontSize = Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
    }

@Composable
private fun ChatScreen(onProfile: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val streaming = Store.job != null
    val listState = remember { LazyListState() }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().imePadding()) {
        TopBar(nav = { IconButton(onProfile) { Icon(painterResource(R.drawable.ph_smiley), "Settings", tint = Muted) } }, title = "Coffee")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (Store.blocks.isEmpty()) Text("Say hello to Coffee", color = Faint, modifier = Modifier.align(Alignment.Center))
            else Messages(streaming, listState)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(26.dp)).background(Raised), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                input, { input = it }, Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 14.dp),
                textStyle = TextStyle(Fg, Body, lineHeight = 22.sp), cursorBrush = SolidColor(Fg), maxLines = 6,
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("Message Coffee", color = Faint, fontSize = Body); inner() } },
            )
            IconButton(
                onClick = { if (input.isNotBlank()) { Store.send(input.trim()); input = "" } else Store.stop() },
                enabled = input.isNotBlank() || streaming,
            ) {
                Icon(painterResource(if (streaming && input.isBlank()) R.drawable.ph_stop else R.drawable.ph_arrow_up),
                    if (streaming && input.isBlank()) "Stop" else "Send", tint = if (input.isNotBlank() || streaming) Coral else Faint)
            }
        }
    }
}

@Composable
private fun Messages(streaming: Boolean, listState: LazyListState) {
    val blocks = Store.blocks
    // Only a block joining the end scrolls down; older pages loading above leave the view where it is
    LaunchedEffect(blocks.lastOrNull()?.let { it.key.ifEmpty { "${it.id}" } }) { listState.animateScrollToItem(0) }
    // Infinite scroll: near the oldest loaded block, fetch the page before it
    val nearTop by remember { derivedStateOf { listState.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalItemsCount - 10 } } }
    LaunchedEffect(nearTop, blocks.size) { if (nearTop) Store.loadOlder() }
    val waiting = streaming && blocks.lastOrNull()?.user != false
    // reverseLayout pins the list to the bottom, so new blocks grow upward with no scroll bookkeeping
    LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        if (waiting) item(key = "waiting") { Spinner(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) }
        // A block the server couldn't store has no id; its place in the list keeps its key unique
        items(blocks.size, key = { r -> blocks[blocks.lastIndex - r].let { it.key.ifEmpty { if (it.id > 0) "b${it.id}" else "r$r" } } }) { r ->
            val i = blocks.lastIndex - r
            val b = blocks[i]
            val last = i == blocks.lastIndex
            // A new turn starts with room above it; blocks of one turn sit close together
            val top = if (blocks.getOrNull(i - 1)?.user != b.user) 16.dp else 4.dp
            val side = if (b.user) Alignment.End else Alignment.Start
            Column(Modifier.fillMaxWidth().padding(top = top), horizontalAlignment = side) {
                when (b.type) {
                    "status" -> Text(b.text, color = Faint, fontSize = Small)
                    "reply" -> Quote(b.text, Fg, Modifier.widthIn(max = bubbleWidth()))
                    "image" -> Photo(b, Modifier.size(180.dp).clip(RoundedCornerShape(18.dp)))
                    else -> if (b.user) {
                        Bubble(b.text, Coral)
                    } else {
                        // Regenerating a check-in would replace it with a reply to an older message
                        val asked = blocks.subList(0, i).lastOrNull { it.user || it.message }?.user == true
                        Assistant(b, canRedo = last && !streaming && (asked || b.err))
                    }
                }
            }
        }
        if (Store.loadingOlder) item(key = "older") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() } }
    }
}

/** Small ring spinner in place of "Loading…" text. */
@Composable
fun Spinner(modifier: Modifier = Modifier) =
    CircularProgressIndicator(modifier.size(16.dp).semantics { contentDescription = "Loading" }, color = Faint, strokeWidth = 2.dp)

@Composable
private fun Assistant(b: Block, canRedo: Boolean) {
    Bubble(b.text, Raised, if (b.err) Color(0xFFFF8A80) else Fg)
    // A failed reply gets a plain next step instead of the regenerate icon
    if (b.err) { if (canRedo) TextButton(Store::regenerate) { Text("Try again", color = Fg) }; return }
    if (canRedo) IconButton(Store::regenerate, Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ph_arrow_clockwise), "Regenerate", tint = Faint, modifier = Modifier.size(16.dp))
    }
}

/** Bubbles stop at 70% of the screen, like Instagram's, so a long text wraps instead of spanning the chat. */
@Composable
private fun bubbleWidth() = (LocalConfiguration.current.screenWidthDp * .7f).dp

/** A text bubble. Hold it for Copy. */
@Composable
private fun Bubble(text: String, bg: Color, fg: Color = Fg) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.widthIn(max = bubbleWidth()).clip(RoundedCornerShape(18.dp)).background(bg)
                .combinedClickable(onClickLabel = null, onLongClickLabel = "Copy", onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) {}
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(text, color = fg, fontSize = Body, lineHeight = 24.sp) }
        DropdownMenu(menu, { menu = false }, containerColor = Raised) {
            DropdownMenuItem({ Text("Copy", color = Fg) }, {
                menu = false
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() // 13+ shows its own
            }, leadingIcon = { Icon(painterResource(R.drawable.ph_copy), null, tint = Fg, modifier = Modifier.size(18.dp)) })
        }
    }
}

/** An image block's photo: the copy on this phone, or downloaded from the server the first time it's shown. */
@Composable
private fun Photo(b: Block, modifier: Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    // null while loading, "" when the download failed
    val path by produceState<String?>(null, b.id, b.path, attempt) { value = null; value = withContext(Dispatchers.IO) { Store.photo(b)?.path.orEmpty() } }
    when (val p = path) {
        null -> Box(modifier.background(Raised), contentAlignment = Alignment.Center) { Spinner() }
        "" -> Box(modifier.background(Raised).clickable { attempt++ }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ph_arrow_clockwise), "Couldn't load photo. Tap to retry", tint = Faint, modifier = Modifier.size(20.dp))
        }
        else -> Thumb(p, modifier)
    }
}

/** The message being replied to, cut to two lines. */
@Composable
private fun Quote(text: String, color: Color, modifier: Modifier = Modifier) = Text(
    text, color = color.copy(alpha = .7f), fontSize = Small, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    modifier = modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .1f)).padding(horizontal = 8.dp, vertical = 4.dp),
)

/** Downsampled off the main thread; images are stored at <=1024px so half size is plenty for thumbnails. */
@Composable
private fun Thumb(path: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
    }
    bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier.background(Raised))
}

@Composable
private fun Profile(onBack: () -> Unit) {
    var name by remember { mutableStateOf(Store["name"]) }
    var instructions by remember { mutableStateOf(Store["system"]) }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().imePadding()) {
        TopBar(nav = { IconButton(onBack) { Icon(painterResource(R.drawable.ph_arrow_left), "Back", tint = Muted) } }, title = "Settings")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
            OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text("Custom instructions") }, minLines = 4)
            TextButton({ Store["name"] = name.trim(); Store["system"] = instructions.trim(); onBack() }) { Text("Save", color = Fg) }
        }
    }
}
