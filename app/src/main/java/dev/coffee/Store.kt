package dev.coffee

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
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

/** Text is snapshot state so a streaming token only invalidates its own Text, not the whole list. [t] is when it was sent, epoch ms. */
class Msg(val user: Boolean, text: String, err: Boolean = false, val image: String? = null, val t: Long = System.currentTimeMillis()) {
    var text by mutableStateOf(text)
    var err by mutableStateOf(err)
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
        Msg(o.getBoolean("u"), o.getString("x"), o.optBoolean("e"), o.optString("i").ifEmpty { null }, o.optLong("t", t))
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

    fun send(text: String, image: String? = null) {
        msgs += Msg(true, text, image = image)
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
        val history = msgs.filter { !it.err && (it.text.isNotBlank() || it.image != null) }.map { Turn(it.user, it.text, it.image, it.t) }
        val m = Msg(false, "")
        msgs += m
        val system = listOfNotNull(
            this["name"].takeIf { it.isNotBlank() }?.let { "The user's name is $it." },
            this["system"].takeIf { it.isNotBlank() }?.let { "Their own instructions for you:\n$it" },
        ).joinToString("\n\n")
        job = scope.launch {
            try {
                Backend.stream(system, history).collect { m.text = it }
            } catch (_: CancellationException) {
                // stopped by user; keep the partial text
            } catch (e: Exception) {
                m.text += (if (m.text.isEmpty()) "" else "\n\n") + (e.message ?: "Network error")
                m.err = true
            } finally {
                job = null
                save()
                if (!m.err && m.text.isNotBlank()) PushService.show(app, m.text) // only if they left the app while it was replying
            }
        }
    }

    private fun save() {
        val json = JSONArray().apply {
            msgs.forEach { put(JSONObject().put("u", it.user).put("x", it.text).put("e", it.err).put("i", it.image).put("t", it.t)) }
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

class Turn(val user: Boolean, val text: String, val image: String?, val t: Long)

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
     * Streams an OpenAI-style chat completion and emits the full accumulated reply.
     * Conflated so the UI renders at most one update per frame; cancelling disconnects the socket immediately.
     */
    fun stream(system: String, history: List<Turn>) = callbackFlow {
        val conn = url.openConnection() as HttpURLConnection
        launch(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                history.forEachIndexed { i, t ->
                    // Photos ride along only on the newest messages: older ones were already talked about, and each is ~150 KB.
                    val content: Any = if (t.image == null || i < history.size - 10) (if (t.image != null) "[photo] " else "") + t.text else JSONArray().apply {
                        if (t.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", t.text))
                        val b64 = Base64.encodeToString(File(t.image).readBytes(), Base64.NO_WRAP)
                        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
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
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data: ")) continue // blank separators
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") break
                        val o = JSONObject(data)
                        if (o.has("error")) throw IOException(errorOf(data, 0))
                        val text = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (text.isNotEmpty()) trySend(sb.append(text).toString())
                    }
                }
                if (sb.isEmpty()) trySend("No response.")
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
