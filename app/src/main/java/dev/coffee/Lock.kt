package dev.coffee

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Coffee signs in with a BigShelf account: BigShelf's login gives a 15-minute access token, which the Coffee server
 * accepts for its owner, and a 7-day refresh token (BigShelf's `refresh_token` cookie) that [token] trades for a new
 * pair whenever the access token is about to run out. So the session is BigShelf's, and stays alive while Coffee
 * keeps using it (location uploads do, in the background).
 *
 * Once signed in, the app re-locks on launch and after [GRACE] away, and opens with the phone's own unlock (fingerprint,
 * face, or screen lock), which works offline. The password is only asked again when the session ends.
 */
object Lock {
    private const val GRACE = 30_000L
    private const val BIGSHELF = "https://bigshelf.aswinharidas.uk/api/v1/auth"
    var locked by mutableStateOf(true)
    private var away = 0L // elapsedRealtime when the app left the screen; 0 until it first has, so a fresh launch locks
    private lateinit var prefs: SharedPreferences

    /** Safe to call often; background work calls it before reaching the server. */
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        signedIn = prefs.getString("refresh", "").orEmpty().isNotEmpty()
    }

    var signedIn by mutableStateOf(false)
        private set

    private fun save(access: String?, refresh: String?) {
        prefs.edit().clear().putString("token", access).putString("refresh", refresh).commit() // the old refresh token is already spent
        signedIn = refresh != null
        if (!signedIn) locked = true
    }

    /**
     * Blocking; call off the main thread. The access token, refreshed first if it has under a minute left. Synchronized
     * because BigShelf rotates the refresh token and treats a reused one as stolen, signing out everywhere.
     */
    val token: String
        @Synchronized get() {
            val access = prefs.getString("token", "").orEmpty()
            if (signedIn && expiry(access) < System.currentTimeMillis() / 1000 + 60) runCatching { refresh() }
            return prefs.getString("token", "").orEmpty()
        }

    private fun expiry(jwt: String) =
        runCatching { JSONObject(String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE))).getLong("exp") }.getOrDefault(0L)

    /** Blocking; call off the main thread. BigShelf's response code: 200 signed in, 401 wrong login, 400 inactive account. Throws when offline. */
    fun signIn(identifier: String, password: String): Int =
        post("/login", JSONObject().put("identifier", identifier).put("password", password).toString(), null)

    /** Forgets the session here and ends it at BigShelf, if it can be reached. */
    fun signOut() {
        val refresh = prefs.getString("refresh", "").orEmpty()
        save(null, null)
        if (refresh.isNotEmpty()) runCatching { post("/logout", "{}", refresh) }
    }

    // Throws when offline, which keeps the old pair for the next try. Any other failure means the session is over.
    private fun refresh() {
        val code = post("/refresh", "{}", prefs.getString("refresh", ""))
        if (code != 200 && code < 500) save(null, null)
    }

    /** Stores the new access and refresh tokens when BigShelf hands them out. */
    private fun post(path: String, body: String, refresh: String?): Int {
        val conn = URL(BIGSHELF + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (refresh != null) conn.setRequestProperty("Cookie", "refresh_token=$refresh")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 200 && path != "/logout") {
                val access = JSONObject(conn.inputStream.bufferedReader().readText()).getString("access_token")
                val cookie = conn.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
                    .first { it.startsWith("refresh_token=") }.substringBefore(";").substringAfter("=")
                save(access, cookie)
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    fun left() { away = SystemClock.elapsedRealtime() }
    fun back() { if (away == 0L || SystemClock.elapsedRealtime() - away > GRACE) locked = true }
}

/** Covers the app: the BigShelf sign-in when there's no session, else the phone's unlock. */
@Composable
fun LockScreen() = if (Lock.signedIn) QuickUnlock() else SignIn()

@Composable
private fun SignIn() {
    val scope = rememberCoroutineScope()
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun submit() {
        if (busy || identifier.isBlank() || password.isEmpty()) return
        busy = true
        message = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val code = Lock.signIn(identifier.trim(), password)
                    // BigShelf signup is open; the Coffee server only lets its owner in
                    if (code == 200 && Backend.check() == 401) { Lock.signOut(); -1 } else code
                }
            }
            busy = false
            message = when (r.getOrNull()) {
                200 -> { password = ""; Lock.locked = false; "" }
                401 -> "Wrong username, email or password"
                400 -> "This BigShelf account is inactive"
                -1 -> "This account isn't the one Coffee belongs to"
                null -> "Can't reach BigShelf"
                else -> "Server error ${r.getOrNull()}. Try again"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().imePadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ph_lock), null, tint = Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text("Sign in with BigShelf", color = Fg, fontSize = Title)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            identifier, { identifier = it }, Modifier.fillMaxWidth(), label = { Text("Username or email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = Coral, fontSize = Small, modifier = Modifier.height(20.dp))
        Spacer(Modifier.height(12.dp))
        Button(::submit, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Signing in…" else "Sign in", color = Fg) }
    }
}

/** The phone's fingerprint, face or screen lock; straight through on a phone with no lock, since anyone holding it could unlock it anyway. */
@Composable
private fun QuickUnlock() {
    val ctx = LocalContext.current
    fun prompt() {
        // ponytail: framework BiometricPrompt with DEVICE_CREDENTIAL needs API 30; older phones skip the quick unlock
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            Lock.locked = false
            return
        }
        BiometricPrompt.Builder(ctx).setTitle("Unlock Coffee").setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build()
            .authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { Lock.locked = false }
            })
    }
    LaunchedEffect(Unit) { prompt() }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ph_lock), null, tint = Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text("Coffee is locked", color = Fg, fontSize = Title)
        Spacer(Modifier.height(24.dp))
        Button(::prompt) { Text("Unlock", color = Fg) }
        TextButton({ Thread { Lock.signOut() }.start() }) { Text("Sign out", color = Muted) }
    }
}
