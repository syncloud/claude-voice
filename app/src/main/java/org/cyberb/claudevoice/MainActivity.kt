package org.cyberb.claudevoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class Agent(val id: Int, val name: String, val dir: String, val branch: String?, val dirty: Boolean)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val sampleRate = 16000
    private val jsonType = "application/json".toMediaType()
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .callTimeout(200, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .build()

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    private lateinit var tts: TextToSpeech
    private lateinit var drawer: DrawerLayout
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var workdir: TextView
    private lateinit var bridgeUrl: EditText
    private lateinit var newDir: EditText
    private lateinit var agentList: ListView
    private lateinit var voiceSpinner: Spinner
    private lateinit var talk: FloatingActionButton
    private lateinit var stop: FloatingActionButton
    private val voices = mutableListOf<Voice>()
    private var chatJob: Job? = null
    @Volatile private var currentCall: okhttp3.Call? = null

    private val agents = mutableListOf<Agent>()
    private var currentAgentId: Int? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        status = findViewById(R.id.status)
        workdir = findViewById(R.id.workdir)
        bridgeUrl = findViewById(R.id.bridgeUrl)
        newDir = findViewById(R.id.newDir)
        agentList = findViewById(R.id.agentList)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        talk = findViewById(R.id.talk)
        stop = findViewById(R.id.stop)

        stop.setOnClickListener {
            tts.stop()
            currentCall?.cancel()
            chatJob?.cancel()
            recording.set(false)
            micColor(R.color.mic_idle)
            setStatus("stopped")
        }

        tts = TextToSpeech(this, this)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        talk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { micColor(R.color.mic_recording); startRecording(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { micColor(R.color.mic_idle); stopAndSend(); true }
                else -> false
            }
        }

        agentList.setOnItemClickListener { _, _, pos, _ ->
            currentAgentId = agents[pos].id
            updateBottom()
            drawer.closeDrawers()
        }

        findViewById<Button>(R.id.addAgent).setOnClickListener {
            val d = newDir.text.toString().trim()
            if (d.isEmpty()) return@setOnClickListener
            ui.launch {
                val s = post("${base()}/agents", JSONObject().put("dir", d).toString().toRequestBody(jsonType))
                if (s == null) { setStatus("add agent failed"); return@launch }
                setAgents(parseAgents(s))
                currentAgentId = agents.maxByOrNull { it.id }?.id
                updateBottom()
                newDir.setText("")
                drawer.closeDrawers()
            }
        }

        refreshAgents()
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        runOnUiThread { loadVoices() }
    }

    private fun loadVoices() {
        val all = try { tts.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val list = all.filter { it.locale.language == "en" }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toString() }.thenBy { it.name })
            .take(3)
        voices.clear(); voices.addAll(list)
        val labels = voices.map { v ->
            "${v.locale.displayName} ${stars(v.quality)}${if (v.isNetworkConnectionRequired) " (net)" else ""}"
        }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                voices.getOrNull(pos)?.let { tts.voice = it }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun stars(q: Int) = when {
        q >= Voice.QUALITY_VERY_HIGH -> "★★★"
        q >= Voice.QUALITY_HIGH -> "★★"
        q >= Voice.QUALITY_NORMAL -> "★"
        else -> "·"
    }

    private fun micColor(res: Int) {
        talk.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, res))
    }

    private fun refreshAgents() = ui.launch {
        val s = httpGet("${base()}/agents") ?: run { setStatus("no bridge at ${base()}"); return@launch }
        setAgents(parseAgents(s))
        if (currentAgentId == null || agents.none { it.id == currentAgentId }) {
            currentAgentId = agents.firstOrNull()?.id
        }
        updateBottom()
    }

    private fun parseAgents(s: String): List<Agent> {
        val out = mutableListOf<Agent>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Agent(o.getInt("id"), o.optString("name"), o.optString("dir"),
                    if (o.isNull("branch")) null else o.optString("branch"),
                    o.optBoolean("dirty", false)))
            }
        } catch (e: Exception) { /* ignore malformed */ }
        return out
    }

    private fun setAgents(list: List<Agent>) {
        agents.clear()
        agents.addAll(list)
        val rows = agents.map { a -> a.name + (a.branch?.let { "  (${it})" } ?: "") }
        agentList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun updateBottom() {
        val a = agents.firstOrNull { it.id == currentAgentId }
        if (a == null) { workdir.text = getString(R.string.no_agent); return }
        val b = a.branch?.let { " • $it${if (a.dirty) " ✗" else ""}" } ?: ""
        workdir.text = shortPath(a.dir) + b
    }

    private fun shortPath(dir: String): String {
        val parts = dir.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 2) dir else "…/" + parts.takeLast(2).joinToString("/")
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (recording.get()) return
        if (!hasMic()) { setStatus("grant microphone permission"); return }
        pcm.reset()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
        } catch (e: SecurityException) { setStatus("microphone unavailable"); return }
        recording.set(true)
        setStatus("listening…")
        recordThread = Thread {
            val buf = ByteArray(minBuf)
            record.startRecording()
            while (recording.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
            record.stop()
            record.release()
        }.also { it.start() }
    }

    private fun stopAndSend() {
        if (!recording.get()) return
        recording.set(false)
        recordThread?.join()
        val audio = synchronized(pcm) { pcm.toByteArray() }
        if (audio.isEmpty()) { setStatus("nothing recorded"); return }
        val aid = currentAgentId
        if (aid == null) { setStatus("no agent selected — swipe right to add one"); return }
        val body = wav(audio).toRequestBody("audio/wav".toMediaType())
        setStatus("transcribing…")
        chatJob = ui.launch {
            val said = post("${base()}/stt", body)
            if (said.isNullOrBlank()) { setStatus("speech-to-text failed"); return@launch }
            append("you", said)
            setStatus("thinking…")
            val payload = JSONObject().put("text", said).put("agent", aid).toString().toRequestBody(jsonType)
            val reply = post("${base()}/chat", payload)
            if (reply.isNullOrBlank()) { setStatus("agent failed"); return@launch }
            append("agent", forDisplay(reply))
            setStatus("ready")
            tts.speak(forSpeech(reply), TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private fun stripMd(t: String): String {
        var s = t
        s = Regex("```[\\s\\S]*?```").replace(s, " code block ")
        s = Regex("`([^`]*)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s, "$1")
        s = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE).replace(s, "")
        s = Regex("(\\*\\*|\\*|__|_|#+|>|~~|~)").replace(s, "")
        return s
    }

    private fun forDisplay(t: String) =
        stripMd(t).lines().joinToString("\n") { it.trimEnd() }.trim()

    private fun forSpeech(t: String): String {
        var s = stripMd(t)
        s = Regex("https?://\\S+").replace(s, "link")
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }

    private fun base() = bridgeUrl.text.toString().trim().trimEnd('/')

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun post(url: String, body: RequestBody): String? = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).post(body).build())
        currentCall = call
        try {
            call.execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null } finally { currentCall = null }
    }

    private fun wav(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val byteRate = sampleRate * 2
        fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
        fun le32(v: Int) {
            out.write(v and 0xff); out.write((v ushr 8) and 0xff)
            out.write((v ushr 16) and 0xff); out.write((v ushr 24) and 0xff)
        }
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(byteRate); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    private fun append(who: String, text: String) {
        transcript.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) { status.text = s }

    override fun onDestroy() {
        super.onDestroy()
        recording.set(false)
        tts.shutdown()
    }
}
