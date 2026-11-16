package dev.coffee

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * Coffee opens behind a PIN, 8882 until it's changed in Settings, and locks again whenever it leaves the screen.
 * Only a hash of the PIN is kept. Five wrong tries in a row wait 30 seconds before the next.
 */
object Lock {
    private const val DEFAULT = "8882"
    var locked by mutableStateOf(true)
    private lateinit var prefs: SharedPreferences
    private var wrong = 0
    private var waitUntil = 0L

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        prefs.edit().remove("token").remove("refresh").apply() // BigShelf sign-in from before the PIN
    }

    private fun hash(pin: String) =
        MessageDigest.getInstance("SHA-256").digest("coffee:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun matches(pin: String) = MessageDigest.isEqual(hash(pin).toByteArray(), prefs.getString("pin", hash(DEFAULT))!!.toByteArray())

    /** Seconds left before another try is allowed, 0 when one is. */
    fun cooldown() = ((waitUntil - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)

    /** Unlocks on the right PIN. */
    fun unlock(pin: String): Boolean {
        if (cooldown() > 0) return false
        if (matches(pin)) { wrong = 0; locked = false; return true }
        if (++wrong >= 5) { wrong = 0; waitUntil = SystemClock.elapsedRealtime() + 30_000 }
        return false
    }

    fun valid(pin: String) = pin.length in 4..8 && pin.all(Char::isDigit)

    /** Sets [new] as the PIN when [current] is right. */
    fun change(current: String, new: String): Boolean {
        if (!valid(new) || cooldown() > 0 || !matches(current)) return false
        prefs.edit().putString("pin", hash(new)).apply()
        return true
    }
}

/** Covers the app until the PIN is entered. */
@Composable
fun LockScreen() {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        if (pin.isEmpty()) return
        if (!Lock.unlock(pin)) message = Lock.cooldown().let { if (it > 0) "Too many tries. Wait ${it}s" else "Wrong PIN" }
        pin = ""
    }
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().imePadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ph_lock), null, tint = Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text("Enter PIN", color = Fg, fontSize = Title)
        Spacer(Modifier.height(24.dp))
        PinField(pin, { pin = it; message = "" }, "PIN", Modifier.focusRequester(focus), ::submit)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Coral, fontSize = Small, modifier = Modifier.height(20.dp))
        Spacer(Modifier.height(12.dp))
        Button(::submit, Modifier.fillMaxWidth()) { Text("Unlock", color = Fg) }
    }
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) =
    OutlinedTextField(
        value, { v -> if (v.length <= 8 && v.all(Char::isDigit)) onChange(v) }, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (onDone != null) ImeAction.Go else ImeAction.Next),
        keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
    )
