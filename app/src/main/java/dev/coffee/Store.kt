package dev.coffee

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.util.Base64
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Text is snapshot state so a streaming token only invalidates its own Text, not the whole list. [t] is when it was sent, epoch ms.
 * [extra] goes to the model after [text] but is never shown in the bubble. [reply] is the text of the message swiped to reply to,
 * or on Coffee's messages the one of the user's it answers.
 */
class Msg(val user: Boolean, text: String, err: Boolean = false, val images: List<String> = emptyList(), val t: Long = System.currentTimeMillis(), val extra: String = "", reply: String = "") {
    /** What the model sees: the quoted message, the text, then any hidden context. Coffee's own quotes stay out. */
    val sent get() = listOf(if (reply.isBlank() || !user) "" else "[replying to: \"$reply\"]", text, extra).filter { it.isNotBlank() }.joinToString("\n\n")
    var text by mutableStateOf(text)
    var err by mutableStateOf(err)
    var reply by mutableStateOf(reply)
}

object Store {
    /** The one ongoing chat, oldest first. Check-ins from the backend agent are Coffee's messages in it. */
    val msgs = mutableStateListOf<Msg>()
    var job by mutableStateOf<Job?>(null)
        private set
    private val scope = MainScope()
    private val io = Dispatchers.IO.limitedParallelism(1) // serialises file writes
    private val settings = mutableStateMapOf<String, String>()
    private lateinit var prefs: SharedPreferences
    private lateinit var file: AtomicFile
    private lateinit var images: File
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        app = ctx.applicationContext
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> settings[k] = v.toString() }
        file = AtomicFile(File(ctx.filesDir, "thread.json"))
        images = File(ctx.filesDir, "images").apply { mkdirs() }
        // ponytail: whole history read on main thread at startup, move to IO if it ever grows past a few MB
        runCatching { msgs += parse(JSONArray(String(file.readFully()))) }.onFailure {
            // Before 1.10 every chat was separate (chats.json, newest first). They merge into the one chat,
            // oldest first, each message dated by its chat's start; the old file stays as a backup.
            runCatching {
                val old = JSONArray(String(AtomicFile(File(ctx.filesDir, "chats.json")).readFully()))
                msgs += List(old.length()) { old.getJSONObject(it) }.sortedBy { it.getLong("id") }.flatMap { parse(it.getJSONArray("m"), it.getLong("id")) }
                save()
            }
        }
    }

    private fun parse(m: JSONArray, t: Long = 0) = List(m.length()) {
        val o = m.getJSONObject(it)
        // "i" was one image path before 1.14, now an array of them
        val i = when (val v = o.opt("i")) { is JSONArray -> List(v.length()) { v.getString(it) }; is String -> listOf(v); else -> emptyList() }
        Msg(o.getBoolean("u"), o.getString("x"), o.optBoolean("e"), i, o.optLong("t", t), o.optString("c"), o.optString("r"))
    }

    operator fun get(k: String) = settings[k].orEmpty()
    operator fun set(k: String, v: String) {
        settings[k] = v
        prefs.edit().putString(k, v).apply()
    }

    /** Swaps the profile photo (a path from [saveImage], or "" for initials) and deletes the old file. */
    fun setAvatar(path: String) {
        val old = this["avatar"]
        this["avatar"] = path
        if (old.isNotBlank() && old != path) scope.launch(io) { File(old).delete() }
    }

    /** The server already stamps each message's time and knows where they are; the app adds what only the phone knows. */
    fun send(text: String, images: List<String> = emptyList(), reply: String = "") {
        // Double text: drop the reply still being thought up and answer both messages together (the server merges them into one turn)
        val pending = msgs.lastOrNull()?.takeIf { job != null && !it.user && it.text.isEmpty() }
        if (pending != null) {
            job?.cancel()
            msgs.remove(pending)
        }
        val hours = msgs.lastOrNull()?.let { (System.currentTimeMillis() - it.t) / 3_600_000 } ?: 0L
        val bm = app.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val extra = listOfNotNull(
            if (hours >= 48) "${hours / 24} days since the last message" else if (hours >= 1) "${hours}h since the last message" else null,
            "battery $battery%".takeIf { battery in 0..20 && !bm.isCharging }, // only when it should change the reply
        )
        msgs += Msg(true, text, images = images, extra = if (extra.isEmpty()) "" else extra.joinToString(", ", "[", "]"), reply = reply)
        reply()
    }

    /** A check-in from the backend agent: Coffee texting first, in the same chat. */
    fun nudge(ctx: Context, text: String) {
        init(ctx)
        msgs += Msg(false, text)
        save()
    }

    fun regenerate() {
        if (msgs.lastOrNull()?.user == false) msgs.removeAt(msgs.lastIndex)
        reply()
    }

    fun stop() = job?.cancel()

    /** The persona and reply rules live on the server; the app only sends the user's own settings. */
    private fun reply() {
        val history = msgs.filter { !it.err && (it.sent.isNotBlank() || it.images.isNotEmpty()) }.map { Turn(it.user, it.sent, it.images, it.t) }
        val m = Msg(false, "")
        msgs += m
        val system = listOfNotNull(
            this["name"].takeIf { it.isNotBlank() }?.let { "The user's name is $it." },
            this["system"].takeIf { it.isNotBlank() }?.let { "Their own instructions for you:\n$it" },
        ).joinToString("\n\n")
        job = scope.launch {
            val me = coroutineContext[Job]
            try {
                Backend.stream(system, history).collect { (text, to) ->
                    m.text = text
                    if (to > 0) msgs.find { it.user && it.t == to }?.let { m.reply = it.text.ifBlank { "Photo" } }
                }
            } catch (_: CancellationException) {
                // stopped by user; keep the partial text
            } catch (e: Exception) {
                m.text += (if (m.text.isEmpty()) "" else "\n\n") + (e.message ?: "Network error")
                m.err = true
            } finally {
                if (job === me) job = null // a double text may have started the next reply already
                save()
                if (!m.err && m.text.isNotBlank() && m in msgs) PushService.show(app, m.text) // only if they left the app while it was replying
            }
        }
    }

    private fun save() {
        val json = JSONArray().apply {
            msgs.forEach { put(JSONObject().put("u", it.user).put("x", it.text).put("e", it.err).put("i", JSONArray(it.images)).put("t", it.t).put("c", it.extra).put("r", it.reply)) }
        }.toString()
        scope.launch(io) {
            val out = file.startWrite()
            try {
                out.write(json.toByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
            }
        }
    }

    /**
     * Copies a picked image into app storage as a JPEG at most 1024px on the long side (upright per EXIF).
     * Small enough for a fast upload and well within vision models' input limits. Call off the main thread.
     */
    fun saveImage(ctx: Context, uri: Uri): String? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val rotation = cr.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
        if (scale < 1f || rotation != 0) {
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = File(images, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out.path
    }.getOrNull()
}

class Turn(val user: Boolean, val text: String, val images: List<String>, val t: Long)

/** coffee-backend /v1/chat: holds the model keys, the prompt and the notes; shows the model the newest part of the chat. */
object Backend {
    private val url = URL("https://coffee.aswinharidas.uk/v1/chat")

    /** Blocking; call off the main thread. Tells the server where to push check-ins. */
    fun registerPush(token: String) {
        val conn = URL("https://coffee.aswinharidas.uk/v1/push-token").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("token", token).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("push token: HTTP ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Blocking; call off the main thread. Sends location fixes, a JSON array of {lat, lon, accuracy, t}, to be kept in
     * Postgres and turned into place texts. True when the server took them (or rejected them as malformed); throws when offline.
     */
    fun uploadFixes(fixes: String): Boolean {
        val conn = URL("https://coffee.aswinharidas.uk/v1/locations").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("{\"fixes\":$fixes}".toByteArray()) }
            return conn.responseCode in 200..299 || conn.responseCode == 400
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. Saves [bssids] as the Wi-Fi networks at [spot], "home" or "office"; throws on failure. */
    fun setSpot(spot: String, bssids: List<String>) {
        val conn = URL("https://coffee.aswinharidas.uk/v1/spots/$spot").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("wifi", JSONArray(bssids)).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("spot: HTTP ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. Check-ins the server couldn't push, cleared there once fetched. */
    fun nudges(): List<String> {
        val conn = URL("https://coffee.aswinharidas.uk/v1/nudges").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
        try {
            val a = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("nudges")
            return List(a.length()) { a.getString(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams an OpenAI-style chat completion and emits the full accumulated reply, with the send time of the
     * user message it answers when the server picked one (else 0).
     * Conflated so the UI renders at most one update per frame; cancelling disconnects the socket immediately.
     */
    fun stream(system: String, history: List<Turn>) = callbackFlow {
        val conn = url.openConnection() as HttpURLConnection
        launch(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                history.forEachIndexed { i, t ->
                    // Photos ride along only on the newest messages: older ones were already talked about, and each is ~150 KB.
                    val content: Any = if (t.images.isEmpty() || i < history.size - 10) "[photo] ".repeat(t.images.size) + t.text else JSONArray().apply {
                        if (t.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", t.text))
                        t.images.forEach {
                            val b64 = Base64.encodeToString(File(it).readBytes(), Base64.NO_WRAP)
                            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                        }
                    }
                    messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", content).put("t", t.t))
                }
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("system", system).put("messages", messages).toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException(errorOf(conn.errorStream?.bufferedReader()?.readText(), conn.responseCode))
                val sb = StringBuilder()
                var to = 0L
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data: ")) continue // blank separators
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") break
                        val o = JSONObject(data)
                        if (o.has("error")) throw IOException(errorOf(data, 0))
                        if (o.has("reply_to")) to = o.optLong("reply_to")
                        val text = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (text.isNotEmpty()) trySend(sb.append(text).toString() to to)
                    }
                }
                if (sb.isEmpty()) trySend("No response." to 0L)
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { conn.disconnect() }
    }.conflate()

    private fun errorOf(body: String?, code: Int): String {
        val msg = runCatching { JSONObject(body!!).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "Invalid chat key."
            429 -> "Rate limited. Try again in a moment."
            else -> "API error${if (code > 0) " $code" else ""}: ${msg ?: body ?: "unknown"}"
        }
    }
}
