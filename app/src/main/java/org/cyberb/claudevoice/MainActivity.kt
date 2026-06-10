package org.cyberb.claudevoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

data class Agent(val id: Int, val name: String, val dir: String, val branch: String?, val dirty: Boolean, val exists: Boolean)

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
    private lateinit var branch: TextView
    private lateinit var bridgeUrl: EditText
    private lateinit var agentList: ListView
    private lateinit var serverStatus: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var talk: FloatingActionButton
    private val voices = mutableListOf<Voice>()
    private var chatJob: Job? = null
    @Volatile private var currentCall: okhttp3.Call? = null
    private var usePiper = false
    private var piperVoice: String? = null
    private var player: MediaPlayer? = null
    private var toneGen: ToneGenerator? = null
    private var speakStatus = true
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val text = intent.getStringExtra("text") ?: ""
            when (type) {
                "you" -> appendYou(text)
                "reply" -> appendReply(text)
                "status" -> {
                    setStatus(text)
                    val busyNow = text == "listening…" || text == "thinking…" || text == "speaking…"
                    if (busyNow != busy) setBusy(busyNow)
                }
            }
        }
    }
    private var busy = false
    private var tokIn = 0
    private var tokOut = 0
    private val ctxByAgent = mutableMapOf<Int, Pair<Int, Int>>()
    private var thinkingStart = 0L
    private var statusWord = "ready"
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateStatusLine(); ticker.postDelayed(this, 1000) }
    }
    private val poller = Handler(Looper.getMainLooper())
    private val pollRun = object : Runnable {
        override fun run() { healthCheck(); poller.postDelayed(this, 4000) }
    }
    private var agentsSig = ""
    private lateinit var audioManager: AudioManager
    private val audioCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateStatusLine() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateStatusLine() }
    }

    private val agents = mutableListOf<Agent>()
    private var currentAgentId: Int? = null
    private val transcripts = mutableMapOf<Int, SpannableStringBuilder>()
    private var loadingHistory = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        status = findViewById(R.id.status)
        workdir = findViewById(R.id.workdir)
        branch = findViewById(R.id.branch)
        bridgeUrl = findViewById(R.id.bridgeUrl)
        agentList = findViewById(R.id.agentList)
        serverStatus = findViewById(R.id.serverStatus)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        talk = findViewById(R.id.talk)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioCb, ticker)
        ContextCompat.registerReceiver(this, eventReceiver, IntentFilter("org.cyberb.claudevoice.EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)

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
            showTranscript()
            drawer.closeDrawers()
        }

        findViewById<Button>(R.id.addAgent).setOnClickListener { openDirPicker() }
        findViewById<TextView>(R.id.clearBtn).setOnClickListener {
            val id = currentAgentId ?: return@setOnClickListener
            ui.launch {
                post("${base()}/agents/$id/clear", "{}".toRequestBody(jsonType))
                transcripts.remove(id)
                ctxByAgent.remove(id)
                tokIn = 0; tokOut = 0
                showTranscript()
                updateBottom()
                setStatus("cleared")
                drawer.closeDrawers()
            }
        }
        findViewById<TextView>(R.id.compactBtn).setOnClickListener {
            val id = currentAgentId ?: return@setOnClickListener
            drawer.closeDrawers()
            tokIn = 0; tokOut = 0
            setBusy(true)
            startTimer("compacting…")
            chatJob = ui.launch {
                val s = post("${base()}/agents/$id/compact", "{}".toRequestBody(jsonType))
                stopThinking()
                setBusy(false)
                if (s == null) { setStatus("compact failed"); return@launch }
                appendSpan(colored("— conversation compacted —\n\n", R.color.action_text, italic = true))
                ctxByAgent.remove(id)
                tokIn = 0; tokOut = 0
                updateBottom()
                setStatus("compacted")
            }
        }
        findViewById<CheckBox>(R.id.piperSwitch).setOnCheckedChangeListener { _, checked ->
            usePiper = checked
            savePrefs()
            if (checked) loadPiperVoices() else loadVoices()
        }
        findViewById<CheckBox>(R.id.bgMode).setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("cv", MODE_PRIVATE).edit().putBoolean("running", checked).apply()
            if (checked) {
                savePrefs()
                if (Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START))
            } else {
                startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
        }
        findViewById<CheckBox>(R.id.speakStatusBox).setOnCheckedChangeListener { _, checked -> speakStatus = checked }
        findViewById<RadioGroup>(R.id.triggerGroup).setOnCheckedChangeListener { _, id ->
            val t = when (id) {
                R.id.trigMsVol -> "msvolume"
                R.id.trigMedia -> "mediabutton"
                else -> "accessibility"
            }
            getSharedPreferences("cv", MODE_PRIVATE).edit().putString("trigger", t).apply()
            if (getSharedPreferences("cv", MODE_PRIVATE).getBoolean("running", false)) {
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_RECONFIG))
            }
        }
        findViewById<Button>(R.id.keySetup).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { }
        }

        refreshAgents()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == "reply") runOnUiThread { setBusy(false); setStatus("ready") } }
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

    private fun loadPiperVoices() {
        ui.launch {
            val s = httpGet("${base()}/voices") ?: return@launch
            val arr = try { JSONArray(s) } catch (e: Exception) { return@launch }
            val names = (0 until arr.length()).map { arr.getString(it) }
            if (names.isEmpty()) { setStatus("no piper voices installed"); return@launch }
            piperVoice = names[0]
            voiceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { piperVoice = names.getOrNull(pos); savePrefs() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
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

    private fun beep(tone: Int) {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            toneGen?.startTone(tone, 150)
        } catch (e: Exception) { }
    }

    private fun speakCue(word: String) {
        if (speakStatus) tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "cue")
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
        stopThinking()
        currentCall?.cancel()
        chatJob?.cancel()
        recording.set(false)
        setBusy(false)
        setStatus("stopped")
        if (getSharedPreferences("cv", MODE_PRIVATE).getBoolean("running", false)) {
            try { startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_CANCEL)) } catch (e: Exception) { }
        }
    }

    private fun refreshAgents() = ui.launch {
        val s = httpGet("${base()}/agents") ?: run { setStatus("no bridge at ${base()}"); return@launch }
        setAgents(parseAgents(s))
        if (currentAgentId == null || agents.none { it.id == currentAgentId }) {
            currentAgentId = agents.firstOrNull()?.id
        }
        updateBottom()
    }

    private fun startPolling() { poller.removeCallbacks(pollRun); poller.post(pollRun) }
    private fun stopPolling() { poller.removeCallbacks(pollRun) }

    private fun setServerUp(up: Boolean) {
        serverStatus.text = if (up) "● bridge" else "● bridge down"
        serverStatus.setTextColor(ContextCompat.getColor(this, if (up) R.color.dot_ok else R.color.dot_down))
    }

    private fun healthCheck() = ui.launch {
        val ok = httpGet("${base()}/health") == "ok"
        setServerUp(ok)
        if (!ok) return@launch
        val s = httpGet("${base()}/agents") ?: return@launch
        val list = parseAgents(s)
        val sig = list.joinToString("|") { "${it.id}:${it.branch}:${it.dirty}:${it.exists}" }
        if (sig != agentsSig) {
            agentsSig = sig
            setAgents(list)
            if (currentAgentId == null || agents.none { it.id == currentAgentId }) {
                currentAgentId = agents.firstOrNull()?.id
            }
            updateBottom()
        }
    }

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

    private fun setAgents(list: List<Agent>) {
        agents.clear()
        agents.addAll(list)
        agentList.adapter = AgentAdapter(agents.toList())
    }

    private fun removeAgent(a: Agent) {
        AlertDialog.Builder(this)
            .setTitle("Remove agent")
            .setMessage("Remove “${a.name}”? This just stops tracking it — the directory and its files are untouched.")
            .setPositiveButton("Remove") { _, _ ->
                ui.launch {
                    val s = httpDelete("${base()}/agents/${a.id}") ?: run { setStatus("remove failed"); return@launch }
                    transcripts.remove(a.id)
                    ctxByAgent.remove(a.id)
                    setAgents(parseAgents(s))
                    agentsSig = ""
                    if (currentAgentId == a.id) {
                        currentAgentId = agents.firstOrNull()?.id
                        showTranscript()
                    }
                    updateBottom()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class AgentAdapter(private val items: List<Agent>) :
        ArrayAdapter<Agent>(this, R.layout.item_agent, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_agent, parent, false)
            val a = items[position]
            v.findViewById<TextView>(R.id.agentName).text = a.name
            val b = v.findViewById<TextView>(R.id.agentBranch)
            if (a.branch != null) {
                b.visibility = View.VISIBLE
                b.text = a.branch + if (a.dirty) "  ✗" else ""
                b.setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (a.dirty) R.color.branch_dirty else R.color.branch_text))
            } else {
                b.visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.agentDot).setTextColor(
                ContextCompat.getColor(this@MainActivity, if (a.exists) R.color.dot_ok else R.color.dot_down))
            v.findViewById<TextView>(R.id.agentClose).setOnClickListener { removeAgent(a) }
            return v
        }
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
                if (browseDir.isNotEmpty()) { dialog.dismiss(); openSessionPicker(browseDir) }
            }
        }
        load("")
        dialog.show()
    }

    private fun openSessionPicker(dir: String) {
        ui.launch {
            val s = httpGet("${base()}/sessions?dir=" + Uri.encode(dir))
            val ids = ArrayList<String>()
            val labels = ArrayList<String>()
            labels.add("✨  New session")
            if (s != null) {
                try {
                    val arr = JSONArray(s)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        ids.add(o.getString("id"))
                        val prev = o.optString("preview").ifBlank { o.getString("id").take(8) }
                        labels.add("⟳  $prev")
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(shortPath(dir))
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) addAgentDir(dir, null) else addAgentDir(dir, ids[which - 1])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addAgentDir(dir: String, session: String?) {
        ui.launch {
            val payload = JSONObject().put("dir", dir)
            if (session != null) payload.put("session", session)
            val s = post("${base()}/agents", payload.toString().toRequestBody(jsonType))
            if (s == null) { setStatus("add agent failed"); return@launch }
            setAgents(parseAgents(s))
            agentsSig = ""
            val id = agents.firstOrNull { it.dir == dir }?.id ?: agents.maxByOrNull { it.id }?.id
            currentAgentId = id
            if (session != null && id != null) {
                val buf = SpannableStringBuilder()
                transcripts[id] = buf
                val h = httpGet("${base()}/history?dir=" + Uri.encode(dir) + "&id=" + Uri.encode(session))
                if (h != null) {
                    try {
                        val arr = JSONArray(h)
                        loadingHistory = true
                        for (i in 0 until arr.length()) renderEvent(arr.getJSONObject(i))
                        loadingHistory = false
                    } catch (e: Exception) { loadingHistory = false }
                }
            }
            updateBottom()
            showTranscript()
            drawer.closeDrawers()
        }
    }

    private fun renderEvent(o: JSONObject) {
        when (o.optString("t")) {
            "you" -> appendYou(o.optString("text"))
            "action" -> appendAction(o.optString("label"))
            "diff" -> appendDiff(o.optString("file"), o.optString("patch"))
            "reply" -> appendReply(o.optString("text"))
        }
    }

    private fun updateBottom() {
        savePrefs()
        val a = agents.firstOrNull { it.id == currentAgentId }
        if (a == null) {
            workdir.text = getString(R.string.no_agent)
            branch.visibility = View.GONE
            return
        }
        workdir.text = shortPath(a.dir)
        if (a.branch != null) {
            branch.visibility = View.VISIBLE
            branch.text = a.branch + if (a.dirty) " ✗" else ""
            branch.setTextColor(ContextCompat.getColor(this,
                if (a.dirty) R.color.branch_dirty else R.color.branch_text))
        } else {
            branch.visibility = View.GONE
        }
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
        beep(ToneGenerator.TONE_PROP_BEEP)
        btInputDevice()?.let { try { record.setPreferredDevice(it) } catch (e: Exception) { } }
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
        beep(ToneGenerator.TONE_PROP_BEEP2)
        val audio = synchronized(pcm) { pcm.toByteArray() }
        if (audio.isEmpty()) { setStatus("nothing recorded"); setBusy(false); return }
        val aid = currentAgentId
        if (aid == null) { setStatus("no agent selected — swipe right to add one"); setBusy(false); return }
        val body = wav(audio).toRequestBody("audio/wav".toMediaType())
        setBusy(true)
        setStatus("transcribing…")
        speakCue("transcribing")
        chatJob = ui.launch {
            val said = post("${base()}/stt", body)
            if (said.isNullOrBlank()) { setStatus("speech-to-text failed"); setBusy(false); return@launch }
            appendYou(said)
            startThinking()
            speakCue("thinking")
            streamChat(aid, said)
        }
    }

    private suspend fun streamChat(aid: Int, text: String) {
        val payload = JSONObject().put("text", text).put("agent", aid).toString().toRequestBody(jsonType)
        var sawReply = false
        withContext(Dispatchers.IO) {
            val call = http.newCall(Request.Builder().url("${base()}/chat").post(payload).build())
            currentCall = call
            try {
                call.execute().use { r ->
                    val src = r.body?.source()
                    if (!r.isSuccessful || src == null) {
                        withContext(Dispatchers.Main) { stopThinking(); setStatus("agent failed"); setBusy(false) }
                        return@use
                    }
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val o = try { JSONObject(line) } catch (e: Exception) { continue }
                        if (o.optString("t") == "reply") sawReply = true
                        withContext(Dispatchers.Main) { handleEvent(o) }
                    }
                }
            } catch (e: Exception) {
                // cancelled or dropped connection
            } finally { currentCall = null }
        }
        if (!sawReply && busy) { stopThinking(); setStatus("agent failed"); setBusy(false) }
    }

    private fun handleEvent(o: JSONObject) {
        when (o.optString("t")) {
            "action" -> appendAction(o.optString("label"))
            "diff" -> appendDiff(o.optString("file"), o.optString("patch"))
            "usage" -> {
                tokIn = o.optInt("in", tokIn)
                tokOut = o.optInt("out", tokOut)
                val id = currentAgentId ?: -1
                val max = o.optInt("max", ctxByAgent[id]?.second ?: 0)
                ctxByAgent[id] = Pair(tokIn, max)
                updateStatusLine()
                updateBottom()
            }
            "reply" -> {
                stopThinking()
                val text = o.optString("text")
                appendReply(text)
                setStatus("speaking…")
                val narration = o.optString("speech", "")
                val speech = if (narration.isNotBlank()) narration else forSpeech(text)
                if (usePiper) speakPiper(speech) else tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
        }
    }

    private fun speakPiper(fullText: String) {
        val sentences = splitSentences(fullText)
        if (sentences.isEmpty()) { setBusy(false); setStatus("ready"); return }
        chatJob = ui.launch {
            var next = async(Dispatchers.IO) { ttsBytes(sentences[0]) }
            for (i in sentences.indices) {
                val wav = next.await()
                if (i + 1 < sentences.size) next = async(Dispatchers.IO) { ttsBytes(sentences[i + 1]) }
                if (wav == null || wav.isEmpty()) {
                    if (i == 0) { tts.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "reply"); return@launch }
                    continue
                }
                playWavAwait(wav)
            }
            setBusy(false); setStatus("ready")
        }
    }

    private fun splitSentences(t: String): List<String> {
        val parts = Regex("(?<=[.!?。！？])\\s+").split(t.trim())
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in parts) {
            if (p.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(p.trim())
            if (sb.length >= 40) { out.add(sb.toString()); sb.clear() }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private suspend fun ttsBytes(text: String): ByteArray? {
        val o = JSONObject().put("text", text)
        piperVoice?.let { o.put("voice", it) }
        return postBytes("${base()}/tts", o.toString().toRequestBody(jsonType))
    }

    private suspend fun playWavAwait(wav: ByteArray) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            val f = File(cacheDir, "reply.wav")
            f.writeBytes(wav)
            stopPlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                it.release(); if (player === it) player = null
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            mp.setOnErrorListener { _, _, _ ->
                if (cont.isActive) cont.resumeWith(Result.success(Unit)); true
            }
            mp.prepare()
            mp.start()
            cont.invokeOnCancellation {
                try { mp.stop() } catch (e: Exception) { }
                mp.release(); if (player === mp) player = null
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
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

    private suspend fun httpDelete(url: String): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).delete().build()).execute().use { r ->
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

    private fun buffer(): SpannableStringBuilder =
        transcripts.getOrPut(currentAgentId ?: -1) { SpannableStringBuilder() }

    private fun appendSpan(cs: CharSequence) {
        buffer().append(cs)
        if (loadingHistory) return
        transcript.setText(buffer(), TextView.BufferType.SPANNABLE)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showTranscript() {
        val buf = transcripts.getOrPut(currentAgentId ?: -1) { SpannableStringBuilder() }
        transcript.setText(buf, TextView.BufferType.SPANNABLE)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun colored(text: String, colorRes: Int, mono: Boolean = false, italic: Boolean = false): SpannableString {
        val s = SpannableString(text)
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, colorRes)), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (mono) s.setSpan(TypefaceSpan("monospace"), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) s.setSpan(StyleSpan(Typeface.ITALIC), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun appendYou(text: String) {
        appendSpan(colored("you  ", R.color.branch_text))
        appendSpan("$text\n\n")
    }

    private fun stripInline(t: String): String {
        var s = t
        s = Regex("`([^`]*)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s, "$1")
        s = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE).replace(s, "• ")
        s = Regex("(\\*\\*|\\*|__|_|#+|>|~~|~)").replace(s, "")
        return s
    }

    private fun appendReply(text: String) {
        val parts = text.split("```")
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 0) {
                val t = stripInline(part).trim()
                if (t.isNotEmpty()) appendSpan("$t\n")
            } else {
                var code = part
                val nl = code.indexOf('\n')
                if (nl >= 0) {
                    val first = code.substring(0, nl).trim()
                    if (first.isNotEmpty() && !first.contains(' ') && first.length < 15) {
                        code = code.substring(nl + 1)
                    }
                }
                appendSpan(codeBlock(code))
            }
        }
        appendSpan("\n")
    }

    private fun col(res: Int) = ContextCompat.getColor(this, res)

    private val codeKeywords = setOf(
        "val", "var", "fun", "def", "class", "interface", "object", "return", "if", "else", "for",
        "while", "do", "when", "switch", "case", "break", "continue", "import", "package", "public",
        "private", "protected", "static", "final", "void", "new", "this", "super", "try", "catch",
        "finally", "throw", "throws", "func", "let", "const", "type", "struct", "enum", "defer",
        "range", "map", "chan", "select", "async", "await", "yield", "lambda", "in", "is", "as",
        "and", "or", "not", "true", "false", "null", "nil", "none", "int", "string", "bool",
        "boolean", "float", "double", "long", "char", "byte", "echo", "print", "println", "with",
        "from", "global", "pass", "raise", "except", "elif", "using", "namespace", "template",
        "unsigned", "virtual", "override", "suspend", "data", "sealed", "companion", "init", "by"
    )

    private fun codeBlock(code: String): CharSequence {
        val body = code.trimEnd('\n') + "\n"
        val sb = SpannableStringBuilder(body)
        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(ForegroundColorSpan(col(R.color.code_text)), 0, sb.length, flag)
        sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, flag)
        sb.setSpan(CodeBlockBg(col(R.color.code_bg)), 0, sb.length, flag)
        highlightInto(sb, body)
        return sb
    }

    private fun highlightInto(sb: SpannableStringBuilder, code: String) {
        val n = code.length
        var i = 0
        fun span(s: Int, e: Int, c: Int) =
            sb.setSpan(ForegroundColorSpan(c), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val kw = col(R.color.code_kw); val str = col(R.color.code_str)
        val com = col(R.color.code_com); val num = col(R.color.code_num)
        while (i < n) {
            val c = code[i]
            when {
                c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val s = i; while (i < n && code[i] != '\n') i++; span(s, i, com)
                }
                c == '#' -> { val s = i; while (i < n && code[i] != '\n') i++; span(s, i, com) }
                c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val s = i; i += 2
                    while (i + 1 < n && !(code[i] == '*' && code[i + 1] == '/')) i++
                    i = minOf(n, i + 2); span(s, i, com)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val q = c; val s = i; i++
                    while (i < n && code[i] != q) { if (code[i] == '\\') i++; i++ }
                    i = minOf(n, i + 1); span(s, i, str)
                }
                c.isDigit() -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.')) i++
                    span(s, i, num)
                }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    if (code.substring(s, i) in codeKeywords) span(s, i, kw)
                }
                else -> i++
            }
        }
    }

    private class CodeBlockBg(private val bg: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
        ) {
            val orig = paint.color
            paint.color = bg
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = orig
        }
    }

    private fun appendAction(label: String) {
        appendSpan(colored("▸ $label\n", R.color.action_text, italic = true))
    }

    private fun appendDiff(file: String, patch: String) {
        appendSpan(colored("✎ $file\n", R.color.action_text, italic = true))
        for (line in patch.split("\n")) {
            val color = when {
                line.startsWith("+") -> R.color.diff_add
                line.startsWith("-") -> R.color.diff_del
                else -> R.color.action_text
            }
            appendSpan(colored("$line\n", color, mono = true))
        }
        appendSpan("\n")
    }

    private fun setStatus(s: String) { statusWord = s; updateStatusLine() }

    private fun updateStatusLine() {
        val sb = StringBuilder(statusWord)
        if (statusWord == "thinking…" || statusWord == "compacting…") {
            sb.append("  ").append((System.currentTimeMillis() - thinkingStart) / 1000).append("s")
        }
        val ctx = ctxByAgent[currentAgentId ?: -1]
        if (ctx != null && ctx.first > 0) {
            sb.append("   ctx ").append(fmtTok(ctx.first))
            if (ctx.second > 0) sb.append("/").append(fmtTok(ctx.second))
        }
        if (tokIn > 0 || tokOut > 0) {
            sb.append("   ↑").append(fmtTok(tokIn)).append(" ↓").append(fmtTok(tokOut))
        }
        sb.append("   🎙 ").append(micLabel())
        status.text = sb.toString()
    }

    private fun btInputDevice(): AudioDeviceInfo? {
        if (!::audioManager.isInitialized) return null
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (e: Exception) { null }
    }

    private fun micLabel() = if (btInputDevice() != null) "buds" else "phone"

    private fun savePrefs() {
        getSharedPreferences("cv", MODE_PRIVATE).edit()
            .putString("bridge", base())
            .putInt("agent", currentAgentId ?: -1)
            .putBoolean("piper", usePiper)
            .putString("voice", piperVoice ?: "")
            .apply()
    }

    private fun fmtTok(n: Int) = if (n >= 1000) "${n / 1000}k" else "$n"

    private fun startTimer(label: String) {
        thinkingStart = System.currentTimeMillis()
        setStatus(label)
        ticker.removeCallbacks(tick); ticker.post(tick)
    }

    private fun startThinking() {
        tokIn = 0; tokOut = 0
        startTimer("thinking…")
    }

    private fun stopThinking() { ticker.removeCallbacks(tick) }

    override fun onDestroy() {
        super.onDestroy()
        recording.set(false)
        stopPlayer()
        toneGen?.release()
        if (::audioManager.isInitialized) audioManager.unregisterAudioDeviceCallback(audioCb)
        try { unregisterReceiver(eventReceiver) } catch (e: Exception) { }
        tts.shutdown()
    }
}
