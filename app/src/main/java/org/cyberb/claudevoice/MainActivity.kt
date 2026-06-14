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
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.RelativeLayout
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class Agent(val id: Int, val name: String, val dir: String, val branch: String?, val dirty: Boolean, val exists: Boolean)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val sampleRate = 16000
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var http = BridgeHttp({ base() })

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    private lateinit var tts: TextToSpeech
    private lateinit var drawer: DrawerLayout
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bottombar: View
    private lateinit var status: TextView
    private lateinit var workdir: TextView
    private lateinit var branch: TextView
    private lateinit var agentList: ListView
    private lateinit var serverStatus: TextView
    private lateinit var talk: FloatingActionButton
    private var chatJob: Job? = null
    private var player: MediaPlayer? = null
    private var toneGen: ToneGenerator? = null

    private fun cvPrefs() = getSharedPreferences("cv", MODE_PRIVATE)
    private fun usePiper() = cvPrefs().getBoolean("piper", false)
    private fun speakStatusOn() = cvPrefs().getBoolean("speakStatus", true)
    private fun piperVoice(): String? = cvPrefs().getString("voice", null)?.ifBlank { null }
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
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateBottom() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateBottom() }
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
        bottombar = findViewById(R.id.bottombar)
        status = findViewById(R.id.status)
        workdir = findViewById(R.id.workdir)
        branch = findViewById(R.id.branch)
        agentList = findViewById(R.id.agentList)
        serverStatus = findViewById(R.id.serverStatus)
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
        findViewById<TextView>(R.id.overflowBtn).setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add(0, 1, 0, R.string.clear_short)
            pm.menu.add(0, 2, 1, R.string.compact_short)
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { clearAgent(); true }
                    2 -> { compactAgent(); true }
                    else -> false
                }
            }
            pm.show()
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshAgents()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        applyTtsVoice()
        applyBarPosition()
    }

    private fun applyBarPosition() {
        val top = cvPrefs().getBoolean("statusbarTop", false)
        val barLp = bottombar.layoutParams as RelativeLayout.LayoutParams
        val scrollLp = scroll.layoutParams as RelativeLayout.LayoutParams
        if (top) {
            barLp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            barLp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
            scrollLp.removeRule(RelativeLayout.ABOVE)
            scrollLp.addRule(RelativeLayout.BELOW, R.id.bottombar)
        } else {
            barLp.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
            barLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            scrollLp.removeRule(RelativeLayout.BELOW)
            scrollLp.addRule(RelativeLayout.ABOVE, R.id.bottombar)
        }
        bottombar.layoutParams = barLp
        scroll.layoutParams = scrollLp
    }

    private fun clearAgent() {
        val id = currentAgentId ?: return
        ui.launch {
            http.clear(id)
            transcripts.remove(id)
            ctxByAgent.remove(id)
            tokIn = 0; tokOut = 0
            showTranscript()
            updateBottom()
            setStatus("cleared")
            drawer.closeDrawers()
        }
    }

    private fun compactAgent() {
        val id = currentAgentId ?: return
        drawer.closeDrawers()
        tokIn = 0; tokOut = 0
        setBusy(true)
        startTimer("compacting…")
        chatJob = ui.launch {
            val ok = http.compact(id)
            stopThinking()
            setBusy(false)
            if (!ok) { setStatus("compact failed"); return@launch }
            appendSpan(colored("— conversation compacted —\n\n", R.color.action_text, italic = true))
            ctxByAgent.remove(id)
            tokIn = 0; tokOut = 0
            updateBottom()
            setStatus("compacted")
        }
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
        runOnUiThread { applyTtsVoice() }
    }

    private fun applyTtsVoice() {
        if (!::tts.isInitialized) return
        val name = cvPrefs().getString("ttsVoice", null) ?: return
        try { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } } catch (e: Exception) { }
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
        if (speakStatusOn()) tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "cue")
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
        http.cancel()
        chatJob?.cancel()
        recording.set(false)
        setBusy(false)
        setStatus("stopped")
        if (getSharedPreferences("cv", MODE_PRIVATE).getBoolean("running", false)) {
            try { startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_CANCEL)) } catch (e: Exception) { }
        }
    }

    private fun refreshAgents() = ui.launch {
        val list = http.agents() ?: run { setStatus("no bridge at ${base()}"); return@launch }
        setAgents(list)
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
        val ok = http.health()
        setServerUp(ok)
        if (!ok) return@launch
        val list = http.agents() ?: return@launch
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
                    val list = http.removeAgent(a.id) ?: run { setStatus("remove failed"); return@launch }
                    transcripts.remove(a.id)
                    ctxByAgent.remove(a.id)
                    setAgents(list)
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
            val nar = v.findViewById<TextView>(R.id.agentNarrate)
            nar.alpha = if (cvPrefs().getBoolean("narrate_${a.id}", false)) 1f else 0.35f
            nar.setOnClickListener {
                val cur = cvPrefs().getBoolean("narrate_${a.id}", false)
                cvPrefs().edit().putBoolean("narrate_${a.id}", !cur).apply()
                nar.alpha = if (!cur) 1f else 0.35f
            }
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
                val listing = http.listDir(dir) ?: run { setStatus("cannot list folder"); return@launch }
                browseDir = listing.dir
                pathView.text = browseDir
                val rows = ArrayList<String>()
                val targets = ArrayList<String>()
                if (listing.parent != null) { rows.add("⬆  .."); targets.add(listing.parent) }
                for (n in listing.dirs) {
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
            val sessions = http.sessions(dir)
            val ids = ArrayList<String>()
            val labels = ArrayList<String>()
            labels.add("✨  New session")
            for (s in sessions) {
                ids.add(s.id)
                val prev = s.preview.ifBlank { s.id.take(8) }
                labels.add("⟳  $prev")
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
            val list = http.addAgent(dir, session) ?: run { setStatus("add agent failed"); return@launch }
            setAgents(list)
            agentsSig = ""
            val id = agents.firstOrNull { it.dir == dir }?.id ?: agents.maxByOrNull { it.id }?.id
            currentAgentId = id
            if (session != null && id != null) {
                transcripts[id] = SpannableStringBuilder()
                val events = http.history(dir, session)
                if (events != null) {
                    loadingHistory = true
                    for (e in events) renderEvent(e)
                    loadingHistory = false
                }
            }
            updateBottom()
            showTranscript()
            drawer.closeDrawers()
        }
    }

    private fun renderEvent(e: ChatEvent) {
        when (e) {
            is ChatEvent.You -> appendYou(e.text)
            is ChatEvent.Action -> appendAction(e.label)
            is ChatEvent.Diff -> appendDiff(e.file, e.patch)
            is ChatEvent.Reply -> appendReply(e.text)
            else -> {}
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
        val b = a.branch?.let { it + if (a.dirty) " ✗" else "" } ?: ""
        branch.visibility = View.VISIBLE
        branch.text = (b + "   🎙 " + micLabel()).trim()
        branch.setTextColor(ContextCompat.getColor(this,
            if (a.dirty) R.color.branch_dirty else R.color.branch_text))
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
        val wavBytes = wav(audio)
        setBusy(true)
        setStatus("transcribing…")
        speakCue("transcribing")
        chatJob = ui.launch {
            val said = http.stt(wavBytes)
            if (said.isNullOrBlank()) { setStatus("speech-to-text failed"); setBusy(false); return@launch }
            appendYou(said)
            startThinking()
            speakCue("thinking")
            streamChat(aid, said)
        }
    }

    private suspend fun streamChat(aid: Int, text: String) {
        val narrate = cvPrefs().getBoolean("narrate_$aid", false)
        var sawReply = false
        val ok = http.chat(text, aid, narrate) { event ->
            if (event is ChatEvent.Reply) sawReply = true
            withContext(Dispatchers.Main) { handleEvent(event) }
        }
        if ((!ok || !sawReply) && busy) { stopThinking(); setStatus("agent failed"); setBusy(false) }
    }

    private fun handleEvent(e: ChatEvent) {
        when (e) {
            is ChatEvent.Action -> appendAction(e.label)
            is ChatEvent.Diff -> appendDiff(e.file, e.patch)
            is ChatEvent.Working -> {
                appendAction(e.text)
                if (speakStatusOn()) tts.speak(e.text, TextToSpeech.QUEUE_ADD, null, "working")
            }
            is ChatEvent.Usage -> {
                e.tokIn?.let { tokIn = it }
                e.tokOut?.let { tokOut = it }
                val id = currentAgentId ?: -1
                val max = e.max ?: (ctxByAgent[id]?.second ?: 0)
                ctxByAgent[id] = Pair(tokIn, max)
                updateStatusLine()
                updateBottom()
            }
            is ChatEvent.Reply -> {
                stopThinking()
                appendReply(e.text)
                setStatus("speaking…")
                val speech = if (e.speech.isNotBlank()) e.speech else forSpeech(e.text)
                if (usePiper()) speakPiper(speech) else tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
            else -> {}
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

    private suspend fun ttsBytes(text: String): ByteArray? = http.tts(text, piperVoice())

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

    private fun base() = (cvPrefs().getString("bridge", "http://127.0.0.1:8765") ?: "").trim().trimEnd('/')

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
            .putInt("agent", currentAgentId ?: -1)
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
