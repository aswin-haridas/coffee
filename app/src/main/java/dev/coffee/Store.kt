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
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * One block of the chat: a [type] of "message", "image" (a photo), "reply" (a quote of block [ref], shown above what
 * comes next) or "status" (a step Coffee took on the way to a reply). The server keeps every block; the app holds the
 * pages it has loaded, oldest first. [key] is the app's id for a block it made, kept until the server echoes it back
 * with an [id]. [typed] is what the user typed once the server rephrased [text]; [extra] goes to the model, never on
 * screen. [path] is the photo on this phone, when there is one. [err] is a failed reply, shown here and never sent.
 */
data class Block(
    val id: Long, val t: Long, val user: Boolean, val type: String, val text: String = "", val typed: String = "",
    val ref: Long = 0, val key: String = "", val extra: String = "", val path: String = "", val err: Boolean = false,
) {
    val message get() = type == "message"

    fun json(): JSONObject = JSONObject().put("id", id).put("t", t).put("role", if (user) "user" else "coffee").put("type", type)
        .put("text", text).put("typed", typed).put("ref", ref).put("key", key).put("extra", extra).put("path", path).put("err", err)

    companion object {
        fun of(o: JSONObject) = Block(
            o.optLong("id"), o.optLong("t"), o.optString("role") == "user", o.optString("type"), o.optString("text"), o.optString("typed"),
            o.optLong("ref"), o.optString("key"), o.optString("extra"), o.optString("path"), o.optBoolean("err"),
        )

        fun list(a: JSONArray) = List(a.length()) { of(a.getJSONObject(it)) }
    }
}

object Store {
    /** The loaded part of the one ongoing chat, oldest first. Check-ins from the backend agent are Coffee's messages in it. */
    val blocks = mutableStateListOf<Block>()
    /** Whether the server has older blocks than the first one loaded. */
    var more by mutableStateOf(true)
        private set
    var job by mutableStateOf<Job?>(null)
        private set
    /** Coffee is "typing" its next text: the app holds each one back a little, the way a person takes time to type. */
    var typing by mutableStateOf(false)
        private set
    private var loading = false
    private val scope = MainScope()
    private val io = Dispatchers.IO.limitedParallelism(1) // serialises file writes
    private val settings = mutableStateMapOf<String, String>()
    private lateinit var prefs: SharedPreferences
    private lateinit var file: AtomicFile
    private lateinit var images: File
    private lateinit var app: Context

    private const val CACHED = 100 // newest blocks kept on the phone, so the chat opens instantly and offline

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        app = ctx.applicationContext
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> settings[k] = v.toString() }
        file = AtomicFile(File(ctx.filesDir, "blocks.json"))
        images = File(ctx.filesDir, "images").apply { mkdirs() }
        runCatching { blocks += Block.list(JSONArray(String(file.readFully()))) }
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

    /** Where an image block's photo is, downloading it the first time. Call off the main thread. */
    fun photo(b: Block): File? {
        File(b.path).takeIf { b.path.isNotEmpty() && it.exists() }?.let { return it }
        val f = File(images, "b${b.id}.jpg")
        if (!f.exists() && b.id > 0) runCatching { Backend.image(b.id, f) }
        return f.takeIf { it.exists() }
    }

    /** On launch: moves the history from before blocks to the server once, then fetches what's new since the last block. */
    fun sync() = scope.launch {
        try {
            if (withContext(Dispatchers.IO) { migrate() }) blocks.clear() // the newest page comes from the server next
            val newest = blocks.lastOrNull { it.id > 0 }?.id ?: 0L
            if (newest == 0L) {
                val (page, older) = withContext(Dispatchers.IO) { Backend.blocks("") }
                blocks.removeAll { it.err }
                blocks.addAll(0, page)
                more = older
            } else {
                do {
                    val after = blocks.last { it.id > 0 }.id
                    val (page, newer) = withContext(Dispatchers.IO) { Backend.blocks("after=$after&limit=200") }
                    page.forEach(::upsert)
                } while (newer)
            }
            save()
        } catch (e: Exception) {
            if (e is CancellationException) throw e // offline: the cached blocks stay as they are
        }
    }

    /** The page before the first loaded block, for scrolling back through the chat. */
    fun loadOlder() {
        val first = blocks.firstOrNull { it.id > 0 }?.id ?: return // nothing from the server yet: sync brings the first page
        if (loading || !more) return
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Backend.blocks("before=$first") } }.onSuccess { (page, older) ->
                blocks.addAll(0, page)
                more = older
            }
            loading = false
        }
    }

    /** A block from the server: the one this phone made (same key) or already has (same id) is replaced, else it joins the end. */
    private fun upsert(b: Block) {
        val i = blocks.indexOfFirst { b.key.isNotEmpty() && it.key == b.key || b.id > 0 && it.id == b.id }
        if (i < 0) blocks += b else blocks[i] = b.copy(path = blocks[i].path)
    }

    /**
     * The new blocks go to the server, which stamps their time and knows where they are; the app adds what only the
     * phone knows. A text while Coffee is still replying drops that reply: the server answers both messages together.
     */
    fun send(text: String, images: List<String> = emptyList(), quote: String = "", ref: Long = 0) {
        val now = System.currentTimeMillis()
        val hours = blocks.lastOrNull()?.let { (now - it.t) / 3_600_000 } ?: 0L
        val bm = app.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val extra = listOfNotNull(
            if (hours >= 48) "${hours / 24} days since the last message" else if (hours >= 1) "${hours}h since the last message" else null,
            "battery $battery%".takeIf { battery in 0..20 && !bm.isCharging }, // only when it should change the reply
        ).let { if (it.isEmpty()) "" else it.joinToString(", ", "[", "]") }
        val new = buildList {
            if (quote.isNotEmpty()) add(Block(0, now, true, "reply", quote, ref = ref, key = UUID.randomUUID().toString()))
            images.forEach { add(Block(0, now, true, "image", key = UUID.randomUUID().toString(), path = it)) }
            if (text.isNotBlank()) add(Block(0, now, true, "message", text, key = UUID.randomUUID().toString()))
        }
        if (new.isEmpty()) return
        job?.cancel()
        blocks += new.dropLast(1) + new.last().copy(extra = extra)
        reply(redo = false)
    }

    /** A check-in from the backend agent, pushed: Coffee texting first, in the same chat. */
    fun nudge(ctx: Context, b: Block) {
        init(ctx)
        upsert(b)
        save()
    }

    fun regenerate() {
        while (blocks.lastOrNull()?.user == false) blocks.removeAt(blocks.lastIndex)
        // Retrying a failed send: if the server never got the user's blocks, it's a first reply, not a redo
        reply(redo = blocks.none { it.id == 0L && it.user })
    }

    fun stop() = job?.cancel()

    /** The persona and reply rules live on the server; the app only sends the user's own settings and its unsent blocks. */
    private fun reply(redo: Boolean) {
        blocks.removeAll { it.err } // a failed reply goes once there's a new try
        val pending = blocks.filter { it.id == 0L && it.user }
        val system = listOfNotNull(
            this["name"].takeIf { it.isNotBlank() }?.let { "The user's name is $it." },
            this["system"].takeIf { it.isNotBlank() }?.let { "Their own instructions for you:\n$it" },
        ).joinToString("\n\n")
        job = scope.launch {
            val me = coroutineContext[Job]
            var said: Block? = null
            var held: Block? = null
            try {
                Backend.chat(system, pending, redo).collect { b ->
                    if (!b.user && b.message) {
                        held = b
                        typing = true
                        delay((600L + 40L * b.text.length).coerceAtMost(4_000)) // about a fast thumb's typing speed
                        typing = false
                        said = b
                    }
                    upsert(b)
                    held = null
                }
            } catch (_: CancellationException) {
                // stopped by the user, or a new text: whatever came stays, the text being typed too
                // ponytail: texts still queued behind it are on the server and show on the next sync, not now
                held?.let(::upsert)
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Couldn't reach your Coffee server."
                    else -> e.message ?: "Something went wrong."
                }
                blocks += Block(0, System.currentTimeMillis(), false, "message", msg, key = UUID.randomUUID().toString(), err = true)
                sync() // the server may have stored more than reached the phone
            } finally {
                if (job === me) { job = null; typing = false } // a double text may have started the next reply already
                save()
                said?.let { if (it in blocks) PushService.show(app, it.text) } // only if they left the app while it was replying
            }
        }
    }

    private fun save() {
        val json = JSONArray().apply { blocks.takeLast(CACHED).forEach { put(it.json()) } }.toString()
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
     * Before blocks, the whole chat lived on the phone (thread.json) and went to the server with every message. It's
     * sent over once, a batch at a time with keys so a retry adds nothing, then kept as thread.json.bak. True once done.
     * Blocking; throws when offline, and runs again on the next launch.
     */
    private fun migrate(): Boolean {
        val old = File(app.filesDir, "thread.json")
        if (!old.exists()) return false
        val m = runCatching { JSONArray(old.readText()) }.getOrElse { JSONArray() }
        val all = (0 until m.length()).flatMap { n ->
            val o = m.getJSONObject(n)
            val user = o.getBoolean("u")
            val t = o.optLong("t")
            val pics = when (val v = o.opt("i")) { is JSONArray -> List(v.length()) { v.getString(it) }; is String -> listOf(v); else -> emptyList() }
            if (o.optBoolean("e")) emptyList() else buildList {
                o.optString("r").takeIf { it.isNotBlank() }?.let { add(Block(0, t, user, "reply", it, key = "m$n-r")) }
                pics.filter { File(it).exists() }.forEachIndexed { j, p -> add(Block(0, t, user, "image", key = "m$n-$j", path = p)) }
                o.optString("x").takeIf { it.isNotBlank() }?.let { add(Block(0, t, user, "message", it, o.optString("o"), key = "m$n", extra = o.optString("c"))) }
            }
        }
        all.chunked(20).forEach(Backend::import) // at most 20 photos a request, ~3 MB
        return old.renameTo(File(app.filesDir, "thread.json.bak"))
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

/** coffee-backend: keeps the chat's blocks, holds the model keys, the prompt and the notes, and replies. */
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

    /** Blocking; call off the main thread. The visits and path of [date] (YYYY-MM-DD); throws on failure. */
    fun timeline(date: String): Day {
        val conn = URL("https://coffee.aswinharidas.uk/v1/timeline?date=$date").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val v = o.getJSONArray("visits")
            val p = o.getJSONArray("path")
            return Day(
                List(v.length()) {
                    val x = v.getJSONObject(it)
                    Visit(x.getString("name"), x.getString("kind"), x.optString("near"), x.getLong("arrived"), x.getLong("left"),
                        x.optDouble("lat").takeIf { !it.isNaN() }, x.optDouble("lon").takeIf { !it.isNaN() })
                },
                List(p.length()) { p.getJSONArray(it).let { a -> doubleArrayOf(a.getDouble(0), a.getDouble(1), a.getDouble(2)) } },
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. A page of blocks for [query] (before=, after=, limit=) and whether there are more that way. */
    fun blocks(query: String): Pair<List<Block>, Boolean> {
        val conn = URL("https://coffee.aswinharidas.uk/v1/blocks?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
        try {
            if (conn.responseCode !in 200..299) throw IOException("blocks: HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            return Block.list(o.getJSONArray("blocks")) to o.optBoolean("more")
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. Saves an image block's photo to [to]; throws on failure. */
    fun image(id: Long, to: File) {
        val conn = URL("https://coffee.aswinharidas.uk/v1/blocks/$id/image").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
        try {
            if (conn.responseCode !in 200..299) throw IOException("image: HTTP ${conn.responseCode}")
            val part = File(to.path + ".part")
            conn.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
            part.renameTo(to)
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. Stores blocks as they are, times and roles included; throws on failure. */
    fun import(blocks: List<Block>) {
        val conn = URL("https://coffee.aswinharidas.uk/v1/blocks").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("blocks", JSONArray(blocks.map(::outgoing))).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException(errorOf(conn.errorStream?.bufferedReader()?.readText(), conn.responseCode))
        } finally {
            conn.disconnect()
        }
    }

    /** A block as the server takes it: an image block carries its photo as a data URL. */
    private fun outgoing(b: Block) = JSONObject().put("key", b.key).put("t", b.t).put("role", if (b.user) "user" else "coffee").put("type", b.type)
        .put("text", b.text).put("typed", b.typed).put("ref", b.ref).put("extra", b.extra).apply {
            if (b.type == "image") put("image", "data:image/jpeg;base64," + Base64.encodeToString(File(b.path).readBytes(), Base64.NO_WRAP))
        }

    /**
     * Sends the new blocks and emits every block the server streams back, one per SSE line: those blocks with their ids,
     * a status block per step Coffee takes, rephrasings of the user's messages, then Coffee's reply. Cancelling
     * disconnects the socket immediately.
     */
    fun chat(system: String, blocks: List<Block>, redo: Boolean) = callbackFlow {
        val conn = url.openConnection() as HttpURLConnection
        launch(Dispatchers.IO) {
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.CHAT_KEY}")
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("system", system).put("blocks", JSONArray(blocks.map(::outgoing))).put("redo", redo)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException(errorOf(conn.errorStream?.bufferedReader()?.readText(), conn.responseCode))
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data: ")) continue // blank separators
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") break
                        val o = JSONObject(data)
                        if (o.has("error")) throw IOException(errorOf(data, 0))
                        send(Block.of(o))
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { conn.disconnect() }
    }

    private fun errorOf(body: String?, code: Int): String {
        val msg = runCatching { JSONObject(body!!).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "Invalid chat key."
            429 -> "Rate limited. Try again in a moment."
            else -> "API error${if (code > 0) " $code" else ""}: ${msg ?: body ?: "unknown"}"
        }
    }
}
