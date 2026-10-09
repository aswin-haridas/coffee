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
import androidx.compose.runtime.toMutableStateList
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
 * Sent with every request to pin down the voice and format. Replies render as plain text.
 */
private const val BASE_PROMPT = """You are Coffee, a helpful assistant in a mobile chat app.

Reply like a thoughtful person texting a friend: natural, warm and direct.
- Lead with the answer. No preamble, no restating the question, no "Great question" or "Sure!".
- Keep it short. One to three sentences is usually enough; go longer only when the question truly needs detail or the user asks for it.
- Write plain text. The app does not render markdown, so never use headings, bold, italics, tables or code fences. Use a simple numbered or dashed list only when steps or options are genuinely clearer that way.
- Don't mention being an AI, a language model, your training or your model name unless asked. Don't add disclaimers or a closing offer to help more.
- If something is unclear, ask one short question instead of guessing.
- Match the user's language and tone."""

/** Text is snapshot state so a streaming token only invalidates its own Text, not the whole list. */
class Msg(val user: Boolean, text: String, err: Boolean = false, val image: String? = null) {
    var text by mutableStateOf(text)
    var err by mutableStateOf(err)
}

class Chat(val id: Long, val title: String, msgs: List<Msg>) {
    val msgs = msgs.toMutableStateList()
}

object Store {
    val chats = mutableStateListOf<Chat>()
    var current by mutableStateOf<Chat?>(null)
    var job by mutableStateOf<Job?>(null)
        private set
    private val scope = MainScope()
    private val io = Dispatchers.IO.limitedParallelism(1) // serialises file writes
    private val settings = mutableStateMapOf<String, String>()
    private lateinit var prefs: SharedPreferences
    private lateinit var file: AtomicFile
    private lateinit var images: File

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> settings[k] = v.toString() }
        file = AtomicFile(File(ctx.filesDir, "chats.json"))
        images = File(ctx.filesDir, "images").apply { mkdirs() }
        // ponytail: whole history read on main thread at startup, move to IO if it ever grows past a few MB
        runCatching {
            val arr = JSONArray(String(file.readFully()))
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val m = c.getJSONArray("m")
                chats += Chat(c.getLong("id"), c.getString("t"), List(m.length()) {
                    val o = m.getJSONObject(it)
                    Msg(o.getBoolean("u"), o.getString("x"), o.optBoolean("e"), o.optString("i").ifEmpty { null })
                })
            }
        }
    }

    operator fun get(k: String) = settings[k].orEmpty()
    operator fun set(k: String, v: String) {
        settings[k] = v
        prefs.edit().putString(k, v).apply()
    }

    fun send(text: String, image: String? = null) {
        val chat = current ?: Chat(System.currentTimeMillis(), text.ifBlank { "Image" }.take(60), emptyList()).also {
            chats.add(0, it)
            current = it
        }
        chat.msgs += Msg(true, text, image = image)
        reply(chat)
    }

    /** A check-in from the backend agent becomes a new chat, so replying to it keeps the context. */
    fun nudge(ctx: Context, text: String): Chat {
        init(ctx)
        val id = maxOf(System.currentTimeMillis(), (chats.maxOfOrNull { it.id } ?: 0) + 1) // unique even for a batch
        return Chat(id, text.take(60), listOf(Msg(false, text))).also {
            chats.add(0, it)
            save()
        }
    }

    fun regenerate(chat: Chat) {
        if (chat.msgs.lastOrNull()?.user == false) chat.msgs.removeAt(chat.msgs.lastIndex)
        reply(chat)
    }

    fun stop() = job?.cancel()

    fun delete(chat: Chat) {
        chats.remove(chat)
        chat.msgs.forEach { m -> m.image?.let { File(it).delete() } }
        if (current === chat) current = null
        save()
    }

    private fun reply(chat: Chat) {
        val history = chat.msgs.filter { !it.err && (it.text.isNotBlank() || it.image != null) }.map { Turn(it.user, it.text, it.image) }
        val m = Msg(false, "")
        chat.msgs += m
        val system = buildString {
            append(BASE_PROMPT)
            this@Store["name"].takeIf { it.isNotBlank() }?.let { append("\n\nThe user's name is $it.") }
            this@Store["system"].takeIf { it.isNotBlank() }?.let { append("\n\nThe user's own instructions, which take priority over the above:\n$it") }
        }
        // ponytail: every other chat's text rides along on each request; send ids + search server-side if it gets heavy
        val others = JSONArray().apply {
            chats.filter { it !== chat }.forEach { c ->
                put(JSONObject().put("id", c.id).put("title", c.title).put("messages", JSONArray().apply {
                    c.msgs.filter { !it.err && it.text.isNotBlank() }.forEach { put(JSONObject().put("role", if (it.user) "user" else "assistant").put("content", it.text)) }
                }))
            }
        }
        job = scope.launch {
            try {
                Backend.stream(system, history, others).collect { m.text = it }
            } catch (_: CancellationException) {
                // stopped by user; keep the partial text
            } catch (e: Exception) {
                m.text += (if (m.text.isEmpty()) "" else "\n\n") + (e.message ?: "Network error")
                m.err = true
            } finally {
                job = null
                save()
            }
        }
    }

    private fun save() {
        val json = JSONArray().apply {
            chats.forEach { c ->
                put(JSONObject().put("id", c.id).put("t", c.title).put("m", JSONArray().apply {
                    c.msgs.forEach { put(JSONObject().put("u", it.user).put("x", it.text).put("e", it.err).put("i", it.image)) }
                }))
            }
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

class Turn(val user: Boolean, val text: String, val image: String?)

/** coffee-backend /v1/chat: holds the Gemini key and model, and lets the model use the notes and the other chats. */
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
    fun stream(system: String, history: List<Turn>, chats: JSONArray) = callbackFlow {
        val conn = url.openConnection() as HttpURLConnection
        launch(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                history.forEach { t ->
                    val content: Any = if (t.image == null) t.text else JSONArray().apply {
                        if (t.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", t.text))
                        val b64 = Base64.encodeToString(File(t.image).readBytes(), Base64.NO_WRAP)
                        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                    }
                    messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", content))
                }
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("system", system).put("messages", messages).put("chats", chats).toString().toByteArray()) }
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
