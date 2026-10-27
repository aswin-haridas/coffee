package dev.coffee

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The PIN is the login: the server trades it for a token ([Backend.login]) that every other call carries. Asked on launch
 * and after the app has been away for [GRACE], so a quick trip to the camera or a permission screen doesn't re-lock.
 * Each unlock gets a fresh token, so one only runs out (30 days) if Coffee isn't opened for that long.
 */
object Lock {
    private const val GRACE = 30_000L
    var locked by mutableStateOf(true)
    private var away = 0L // elapsedRealtime when the app left the screen; 0 until it first has, so a fresh launch locks
    private lateinit var prefs: SharedPreferences

    /** Safe to call often; background work calls it before reaching the server. */
    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    val token get() = prefs.getString("token", "").orEmpty()

    // ponytail: a 4-digit hash is guessable offline in 10k tries; it only keeps the PIN out of plain sight, and only unlocks offline with the old token
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256").digest("coffee:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun signedIn(token: String, pin: String) = prefs.edit().putString("token", token).putString("pin", hash(pin)).apply()

    /** For when the server can't be reached: the last PIN that logged in, if there's a token to go with it. */
    fun offline(pin: String) = token.isNotEmpty() && prefs.getString("pin", "") == hash(pin)

    fun left() { away = SystemClock.elapsedRealtime() }
    fun back() { if (away == 0L || SystemClock.elapsedRealtime() - away > GRACE) locked = true }
}

private const val TRIES = 5          // wrong PINs in a row before a wait
private const val WAIT = 30_000L

/** Dots for the digits so far, a message line, and a keypad. Calls [onPin] once four digits are in, then clears. */
@Composable
private fun PinPad(title: String, message: String, enabled: Boolean = true, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun press(d: String) {
        if (!enabled) return
        pin += d
        if (pin.length == 4) { onPin(pin); pin = "" }
    }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ph_lock), null, tint = Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Fg, fontSize = Title)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(Fg) else Modifier.border(1.5.dp, Faint, CircleShape)))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(message, color = Coral, fontSize = Small, modifier = Modifier.height(20.dp))
        Spacer(Modifier.height(24.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).then(
                            when (k) {
                                "" -> Modifier
                                "⌫" -> Modifier.clickable { pin = pin.dropLast(1) }
                                else -> Modifier.background(Raised).clickable { press(k) }
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = if (enabled) Fg else Faint, fontSize = if (k == "⌫") Title else 26.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Covers the app until the server takes the PIN; after [TRIES] wrong ones in a row, the keypad rests for [WAIT]. */
@Composable
fun LockScreen() {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var fails by remember { mutableIntStateOf(0) }
    var until by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(until) {
        if (until == 0L) return@LaunchedEffect
        delay(until - SystemClock.elapsedRealtime())
        until = 0L
        message = ""
    }
    fun wrong(why: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        fails++
        if (fails % TRIES == 0) { until = SystemClock.elapsedRealtime() + WAIT; message = "Too many tries. Wait 30 seconds" } else message = why
    }
    PinPad("Enter PIN", message, enabled = until == 0L && !busy) { pin ->
        busy = true
        message = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backend.login(pin) } }
            busy = false
            val (code, token) = r.getOrNull() ?: (0 to "")
            when {
                code == 200 -> { Lock.signedIn(token, pin); Lock.locked = false }
                code == 401 -> wrong("Wrong PIN")
                code == 429 -> message = "Too many wrong PINs. Try again later"
                Lock.offline(pin) -> Lock.locked = false // offline: the cached chat opens, sending waits for the server
                r.isFailure -> wrong("Can't reach your Coffee server")
                else -> message = "Server error $code. Try again"
            }
        }
    }
}
