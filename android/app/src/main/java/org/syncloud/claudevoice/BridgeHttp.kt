package org.syncloud.claudevoice

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class DirListing(val dir: String = "", val parent: String? = null, val dirs: List<String> = emptyList())

@Serializable
data class Session(val id: String = "", val preview: String = "")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed class ChatEvent {
    @Serializable @SerialName("you") data class You(val text: String = "") : ChatEvent()
    @Serializable @SerialName("reply") data class Reply(val text: String = "", val speech: String = "") : ChatEvent()
    @Serializable @SerialName("action") data class Action(val label: String = "") : ChatEvent()
    @Serializable @SerialName("diff") data class Diff(val file: String = "", val patch: String = "") : ChatEvent()
    @Serializable @SerialName("working") data class Working(val text: String = "") : ChatEvent()
    @Serializable @SerialName("model") data class Model(val name: String = "") : ChatEvent()
    @Serializable @SerialName("usage") data class Usage(
        @SerialName("in") val tokIn: Int? = null,
        @SerialName("out") val tokOut: Int? = null,
        val max: Int? = null,
    ) : ChatEvent()
}

@Serializable private data class ChatRequest(val text: String, val agent: Int, val narrate: Boolean, val model: String? = null)
@Serializable private data class AddAgentRequest(val dir: String, val session: String? = null)
@Serializable private data class TtsRequest(val text: String, val voice: String? = null)

class BridgeHttp(
    private val baseUrl: () -> String,
    timeoutSeconds: Long = 200,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val wavType = "audio/wav".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentCall: Call? = null

    fun cancel() { currentCall?.cancel() }

    suspend fun health(): Boolean = get("/health") == "ok"

    suspend fun agents(): List<Agent>? = get("/agents")?.let { decode<List<Agent>>(it) }

    suspend fun voices(): List<String> = get("/voices")?.let { decode<List<String>>(it) } ?: emptyList()

    suspend fun clear(id: Int): Boolean = post("/agents/$id/clear", "{}".json()) != null

    suspend fun compact(id: Int): Boolean = post("/agents/$id/compact", "{}".json()) != null

    suspend fun addAgent(dir: String, session: String?): List<Agent>? =
        post("/agents", encode(AddAgentRequest(dir, session)).json())?.let { decode<List<Agent>>(it) }

    suspend fun removeAgent(id: Int): List<Agent>? = delete("/agents/$id")?.let { decode<List<Agent>>(it) }

    suspend fun listDir(dir: String): DirListing? =
        get("/ls?dir=" + Uri.encode(dir))?.let { decode<DirListing>(it) }

    suspend fun sessions(dir: String): List<Session> =
        get("/sessions?dir=" + Uri.encode(dir))?.let { decode<List<Session>>(it) } ?: emptyList()

    suspend fun history(dir: String, session: String): List<ChatEvent>? =
        get("/history?dir=" + Uri.encode(dir) + "&id=" + Uri.encode(session))?.let { decode<List<ChatEvent>>(it) }

    suspend fun stt(wav: ByteArray): String? = post("/stt", wav.toRequestBody(wavType))

    suspend fun chat(text: String, agentId: Int, narrate: Boolean, model: String?, onEvent: suspend (ChatEvent) -> Unit): Boolean {
        val payload = encode(ChatRequest(text, agentId, narrate, model)).json()
        return stream("/chat", payload) { line ->
            decode<ChatEvent>(line)?.let { onEvent(it) }
        }
    }

    suspend fun tts(text: String, voice: String?): ByteArray? =
        postBytes("/tts", encode(TtsRequest(text, voice)).json())

    private inline fun <reified T> decode(s: String): T? =
        try { json.decodeFromString<T>(s) } catch (e: Exception) { null }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    private fun String.json(): RequestBody = toRequestBody(jsonType)

    private fun url(path: String) = baseUrl() + path

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path)).get().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun delete(path: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path)).delete().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun post(path: String, body: RequestBody): String? = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url(path)).post(body).build())
        currentCall = call
        try {
            call.execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null } finally { currentCall = null }
    }

    private suspend fun postBytes(path: String, body: RequestBody): ByteArray? = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url(path)).post(body).build())
        currentCall = call
        try {
            call.execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } catch (e: Exception) { null } finally { currentCall = null }
    }

    private suspend fun stream(path: String, body: RequestBody, onLine: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url(path)).post(body).build())
        currentCall = call
        try {
            call.execute().use { r ->
                val src = r.body?.source()
                if (!r.isSuccessful || src == null) return@use false
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    onLine(line)
                }
                true
            }
        } catch (e: Exception) { false } finally { currentCall = null }
    }
}
