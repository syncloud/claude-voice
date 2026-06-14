package org.cyberb.claudevoice

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DirListing(val dir: String, val parent: String?, val dirs: List<String>)

data class Session(val id: String, val preview: String)

sealed class ChatEvent {
    data class You(val text: String) : ChatEvent()
    data class Reply(val text: String, val speech: String) : ChatEvent()
    data class Action(val label: String) : ChatEvent()
    data class Diff(val file: String, val patch: String) : ChatEvent()
    data class Working(val text: String) : ChatEvent()
    data class Usage(val tokIn: Int?, val tokOut: Int?, val max: Int?) : ChatEvent()
    object Unknown : ChatEvent()
}

class BridgeHttp(
    private val baseUrl: () -> String,
    timeoutSeconds: Long = 200,
) {

    private val jsonType = "application/json".toMediaType()
    private val wavType = "audio/wav".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentCall: Call? = null

    fun cancel() { currentCall?.cancel() }

    suspend fun health(): Boolean = get("/health") == "ok"

    suspend fun agents(): List<Agent>? = get("/agents")?.let { parseAgents(it) }

    suspend fun voices(): List<String> {
        val s = get("/voices") ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun clear(id: Int): Boolean =
        post("/agents/$id/clear", "{}".json()) != null

    suspend fun compact(id: Int): Boolean =
        post("/agents/$id/compact", "{}".json()) != null

    suspend fun addAgent(dir: String, session: String?): List<Agent>? {
        val body = JSONObject().put("dir", dir)
        if (session != null) body.put("session", session)
        return post("/agents", body.toString().json())?.let { parseAgents(it) }
    }

    suspend fun removeAgent(id: Int): List<Agent>? =
        delete("/agents/$id")?.let { parseAgents(it) }

    suspend fun listDir(dir: String): DirListing? {
        val s = get("/ls?dir=" + Uri.encode(dir)) ?: return null
        return try {
            val o = JSONObject(s)
            DirListing(
                o.getString("dir"),
                if (o.isNull("parent")) null else o.getString("parent"),
                o.getJSONArray("dirs").let { a -> (0 until a.length()).map { a.getString(it) } },
            )
        } catch (e: Exception) { null }
    }

    suspend fun sessions(dir: String): List<Session> {
        val s = get("/sessions?dir=" + Uri.encode(dir)) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Session(o.getString("id"), o.optString("preview"))
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun history(dir: String, session: String): List<ChatEvent>? {
        val s = get("/history?dir=" + Uri.encode(dir) + "&id=" + Uri.encode(session)) ?: return null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { parseEvent(a.getJSONObject(it)) }
        } catch (e: Exception) { null }
    }

    suspend fun stt(wav: ByteArray): String? = post("/stt", wav.toRequestBody(wavType))

    suspend fun chat(text: String, agentId: Int, narrate: Boolean, onEvent: suspend (ChatEvent) -> Unit): Boolean {
        val payload = JSONObject().put("text", text).put("agent", agentId).put("narrate", narrate).toString().json()
        return stream("/chat", payload) { line ->
            val o = try { JSONObject(line) } catch (e: Exception) { return@stream }
            parseEvent(o)?.let { onEvent(it) }
        }
    }

    suspend fun tts(text: String, voice: String?): ByteArray? {
        val body = JSONObject().put("text", text)
        if (voice != null) body.put("voice", voice)
        return postBytes("/tts", body.toString().json())
    }

    private fun String.json(): RequestBody = toRequestBody(jsonType)

    private fun url(path: String) = baseUrl() + path

    private fun parseAgents(s: String): List<Agent> {
        val out = mutableListOf<Agent>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Agent(o.getInt("id"), o.optString("name"), o.optString("dir"),
                    if (o.isNull("branch")) null else o.optString("branch"),
                    o.optBoolean("dirty", false), o.optBoolean("exists", true)))
            }
        } catch (e: Exception) { /* ignore malformed */ }
        return out
    }

    private fun parseEvent(o: JSONObject): ChatEvent? = when (o.optString("t")) {
        "you" -> ChatEvent.You(o.optString("text"))
        "reply" -> ChatEvent.Reply(o.optString("text"), o.optString("speech", ""))
        "action" -> ChatEvent.Action(o.optString("label"))
        "diff" -> ChatEvent.Diff(o.optString("file"), o.optString("patch"))
        "working" -> ChatEvent.Working(o.optString("text"))
        "usage" -> ChatEvent.Usage(
            if (o.has("in")) o.optInt("in") else null,
            if (o.has("out")) o.optInt("out") else null,
            if (o.has("max")) o.optInt("max") else null,
        )
        else -> ChatEvent.Unknown
    }

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
