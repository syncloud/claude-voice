package org.cyberb.claudevoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import java.io.File
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
    private lateinit var agentList: ListView
    private lateinit var voiceSpinner: Spinner
    private lateinit var talk: FloatingActionButton
    private val voices = mutableListOf<Voice>()
    private var chatJob: Job? = null
    @Volatile private var currentCall: okhttp3.Call? = null
    private var usePiper = false
    private var player: MediaPlayer? = null
    private var busy = false

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
        agentList = findViewById(R.id.agentList)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        talk = findViewById(R.id.talk)

        tts = TextToSpeech(this, this)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        talk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (busy) interrupt() else startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording.get()) stopAndSend()
                    true
                }
                else -> false
            }
        }

        agentList.setOnItemClickListener { _, _, pos, _ ->
            currentAgentId = agents[pos].id
            updateBottom()
            drawer.closeDrawers()
        }

        findViewById<Button>(R.id.addAgent).setOnClickListener { openDirPicker() }
        findViewById<CheckBox>(R.id.piperSwitch).setOnCheckedChangeListener { _, checked -> usePiper = checked }

        refreshAgents()
    }

    override fun onResume() {
        super.onResume()
        refreshAgents()
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { runOnUiThread { setBusy(false); setStatus("ready") } }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) { runOnUiThread { setBusy(false) } }
        })
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

    private fun setBusy(b: Boolean) {
        busy = b
        if (b) {
            talk.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            micColor(R.color.mic_busy)
        } else {
            talk.setImageResource(android.R.drawable.ic_btn_speak_now)
            micColor(R.color.mic_idle)
        }
    }

    private fun interrupt() {
        tts.stop()
        stopPlayer()
        currentCall?.cancel()
        chatJob?.cancel()
        recording.set(false)
        setBusy(false)
        setStatus("stopped")
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

    private fun openDirPicker() {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0)
        }
        val pathView = TextView(this).apply { setPadding(0, 0, 0, px(8)); textSize = 12f }
        val list = ListView(this)
        container.addView(pathView)
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(360)))

        var browseDir = ""
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add agent — pick a folder")
            .setView(container)
            .setPositiveButton("Use this folder", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun load(dir: String) {
            ui.launch {
                val s = httpGet("${base()}/ls?dir=" + Uri.encode(dir)) ?: run { setStatus("cannot list folder"); return@launch }
                val o = JSONObject(s)
                browseDir = o.getString("dir")
                pathView.text = browseDir
                val parent = if (o.isNull("parent")) null else o.getString("parent")
                val arr = o.getJSONArray("dirs")
                val rows = ArrayList<String>()
                val targets = ArrayList<String>()
                if (parent != null) { rows.add("⬆  .."); targets.add(parent) }
                for (i in 0 until arr.length()) {
                    val n = arr.getString(i)
                    rows.add("📁  $n")
                    targets.add(browseDir.trimEnd('/') + "/" + n)
                }
                list.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, rows)
                list.setOnItemClickListener { _, _, pos, _ -> load(targets[pos]) }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (browseDir.isNotEmpty()) { addAgentDir(browseDir); dialog.dismiss() }
            }
        }
        load("")
        dialog.show()
    }

    private fun addAgentDir(dir: String) {
        ui.launch {
            val s = post("${base()}/agents", JSONObject().put("dir", dir).toString().toRequestBody(jsonType))
            if (s == null) { setStatus("add agent failed"); return@launch }
            setAgents(parseAgents(s))
            currentAgentId = agents.maxByOrNull { it.id }?.id
            updateBottom()
            drawer.closeDrawers()
        }
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
        micColor(R.color.mic_recording)
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
        if (audio.isEmpty()) { setStatus("nothing recorded"); setBusy(false); return }
        val aid = currentAgentId
        if (aid == null) { setStatus("no agent selected — swipe right to add one"); setBusy(false); return }
        val body = wav(audio).toRequestBody("audio/wav".toMediaType())
        setBusy(true)
        setStatus("transcribing…")
        chatJob = ui.launch {
            val said = post("${base()}/stt", body)
            if (said.isNullOrBlank()) { setStatus("speech-to-text failed"); setBusy(false); return@launch }
            append("you", said)
            setStatus("thinking…")
            val payload = JSONObject().put("text", said).put("agent", aid).toString().toRequestBody(jsonType)
            val reply = post("${base()}/chat", payload)
            if (reply.isNullOrBlank()) { setStatus("agent failed"); setBusy(false); return@launch }
            append("agent", forDisplay(reply))
            setStatus("speaking…")
            val speech = forSpeech(reply)
            if (usePiper) speakPiper(speech) else tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private fun speakPiper(text: String) {
        chatJob = ui.launch {
            val wav = postBytes("${base()}/tts", JSONObject().put("text", text).toString().toRequestBody(jsonType))
            if (wav == null || wav.isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
                return@launch
            }
            playWav(wav)
        }
    }

    private fun playWav(wav: ByteArray) {
        try {
            val f = File(cacheDir, "reply.wav")
            f.writeBytes(wav)
            stopPlayer()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null; setBusy(false); setStatus("ready") }
                prepare()
                start()
            }
        } catch (e: Exception) {
            setStatus("playback failed"); setBusy(false)
        }
    }

    private fun stopPlayer() {
        player?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        player = null
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
        var s = speakTables(t)
        s = stripMd(s)
        s = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b").replace(s, "an ID")
        s = Regex("\\b[0-9a-f]{12,}\\b", RegexOption.IGNORE_CASE).replace(s, "an ID")
        s = Regex("https?://\\S+").replace(s, "link")
        s = s.replace("|", " ")
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }

    private fun speakTables(t: String): String {
        val lines = t.lines()
        fun cells(line: String) = line.trim().trim('|').split("|").map { it.trim() }
        fun isSep(line: String): Boolean {
            val c = line.trim().trim('|').trim()
            return c.isNotEmpty() && c.all { it == '-' || it == ':' || it == '|' || it == ' ' } && c.contains('-')
        }
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("|") && i + 1 < lines.size && isSep(lines[i + 1])) {
                val header = cells(line)
                i += 2
                while (i < lines.size && lines[i].contains("|")) {
                    val row = cells(lines[i])
                    val parts = ArrayList<String>()
                    for (j in row.indices) {
                        val v = row[j]
                        if (v.isEmpty()) continue
                        val h = header.getOrNull(j)?.takeIf { it.isNotEmpty() }
                        parts.add(if (h != null) "$h: $v" else v)
                    }
                    if (parts.isNotEmpty()) out.append(parts.joinToString(", ")).append(". ")
                    i++
                }
            } else {
                out.append(line).append("\n")
                i++
            }
        }
        return out.toString()
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

    private suspend fun postBytes(url: String, body: RequestBody): ByteArray? = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).post(body).build())
        currentCall = call
        try {
            call.execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
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
        stopPlayer()
        tts.shutdown()
    }
}
