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

const val DEFAULT_MODEL = "openrouter/free"

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
    // OpenRouter ids are "vendor/model"; anything else is a stale Claude/Gemini id from older versions
    val model get() = this["model"].takeIf { '/' in it } ?: DEFAULT_MODEL

    fun send(text: String, image: String? = null) {
        val chat = current ?: Chat(System.currentTimeMillis(), text.ifBlank { "Image" }.take(60), emptyList()).also {
            chats.add(0, it)
            current = it
        }
        chat.msgs += Msg(true, text, image = image)
        reply(chat)
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
        val (model, system) = model to this["system"]
        job = scope.launch {
            try {
                OpenRouter.stream(model, system, history).collect { m.text = it }
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

object OpenRouter {
    private val url = URL("https://openrouter.ai/api/v1/chat/completions")

    /**
     * Streams an OpenAI-style chat completion and emits the full accumulated reply.
     * Conflated so the UI renders at most one update per frame; cancelling disconnects the socket immediately.
     */
    fun stream(model: String, system: String, history: List<Turn>) = callbackFlow {
        val conn = url.openConnection() as HttpURLConnection
        launch(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
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
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENROUTER_KEY}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Title", "Coffee")
                conn.outputStream.use { it.write(JSONObject().put("model", model).put("stream", true).put("messages", messages).toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException(errorOf(conn.errorStream?.bufferedReader()?.readText(), conn.responseCode))
                val sb = StringBuilder()
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data: ")) continue // blank separators and ": OPENROUTER PROCESSING" keep-alives
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
            401 -> "Invalid OpenRouter key."
            429 -> "Free model is rate limited. Try again in a moment."
            else -> "API error${if (code > 0) " $code" else ""}: ${msg ?: body ?: "unknown"}"
        }
    }
}
