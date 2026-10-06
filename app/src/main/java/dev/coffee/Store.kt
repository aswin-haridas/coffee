package dev.coffee

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.jvm.optionals.getOrNull

const val DEFAULT_MODEL = "claude-opus-5-5"

/** Text is snapshot state so a streaming token only invalidates its own Text, not the whole list. */
class Msg(val user: Boolean, text: String, err: Boolean = false) {
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

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> settings[k] = v.toString() }
        file = AtomicFile(File(ctx.filesDir, "chats.json"))
        // ponytail: whole history read on main thread at startup, move to IO if it ever grows past a few MB
        runCatching {
            val arr = JSONArray(String(file.readFully()))
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val m = c.getJSONArray("m")
                chats += Chat(c.getLong("id"), c.getString("t"), List(m.length()) {
                    val o = m.getJSONObject(it)
                    Msg(o.getBoolean("u"), o.getString("x"), o.optBoolean("e"))
                })
            }
        }
        key.takeIf { it.isNotBlank() }?.let { k -> scope.launch(Dispatchers.IO) { Claude.warm(k) } }
    }

    operator fun get(k: String) = settings[k].orEmpty()
    operator fun set(k: String, v: String) {
        settings[k] = v
        prefs.edit().putString(k, v).apply()
    }
    val key get() = this["key"]
    val model get() = this["model"].ifBlank { DEFAULT_MODEL }

    fun send(text: String) {
        val chat = current ?: Chat(System.currentTimeMillis(), text.take(60), emptyList()).also {
            chats.add(0, it)
            current = it
        }
        chat.msgs += Msg(true, text)
        reply(chat)
    }

    fun regenerate(chat: Chat) {
        if (chat.msgs.lastOrNull()?.user == false) chat.msgs.removeAt(chat.msgs.lastIndex)
        reply(chat)
    }

    fun stop() = job?.cancel()

    fun delete(chat: Chat) {
        chats.remove(chat)
        if (current === chat) current = null
        save()
    }

    private fun reply(chat: Chat) {
        val history = chat.msgs.filter { !it.err && it.text.isNotBlank() }.map { it.user to it.text }
        val m = Msg(false, "")
        chat.msgs += m
        val (key, model, system) = Triple(key, model, this["system"])
        job = scope.launch {
            try {
                Claude.stream(key, model, system, history).collect { m.text = it }
            } catch (_: CancellationException) {
                // stopped by user; keep the partial text
            } catch (e: Exception) {
                m.text += (if (m.text.isEmpty()) "" else "\n\n") + when (e) {
                    is UnauthorizedException -> "Invalid API key. Update it in settings."
                    is AnthropicServiceException -> "API error ${e.statusCode()}: ${e.message}"
                    else -> e.message ?: "Network error"
                }
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
                    c.msgs.forEach { put(JSONObject().put("u", it.user).put("x", it.text).put("e", it.err)) }
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
}

object Claude {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun client(key: String): AnthropicClient {
        if (key != clientKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    /** Builds the client and loads the JSON/param classes off the main thread so the first send is instant. */
    fun warm(key: String) {
        client(key)
        params(DEFAULT_MODEL, "", listOf(true to "hi"))
    }

    private fun params(model: String, system: String, history: List<Pair<Boolean, String>>) =
        MessageCreateParams.builder().model(model).maxTokens(64000L).apply {
            // Fallbacks + effort are only accepted by the current Opus/Fable/Sonnet 5.5 line.
            if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") || model == "claude-sonnet-5-5") {
                addBeta("server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                putAdditionalBodyProperty("output_config", JsonValue.from(mapOf("effort" to "low")))
            }
            if (system.isNotBlank()) system(system)
            history.forEach { (user, text) -> if (user) addUserMessage(text) else addAssistantMessage(text) }
        }.build()

    /** Emits the full accumulated reply; conflated so the UI renders at most one update per frame. */
    fun stream(key: String, model: String, system: String, history: List<Pair<Boolean, String>>) = flow {
        val sb = StringBuilder()
        client(key).beta().messages().createStreaming(params(model, system, history)).use { s ->
            for (e in s.stream().iterator()) {
                e.contentBlockDelta().getOrNull()?.delta()?.text()?.getOrNull()?.let {
                    sb.append(it.text())
                    emit(sb.toString())
                }
                if (e.messageDelta().getOrNull()?.delta()?.stopReason()?.getOrNull() == BetaStopReason.REFUSAL) {
                    sb.append(if (sb.isEmpty()) "Declined to answer." else "\n\n(Response stopped.)")
                    emit(sb.toString())
                }
            }
        }
    }.flowOn(Dispatchers.IO).conflate()
}
