package org.cyberb.claudevoice

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainView(private val host: VoiceHost, root: View) {

    private val activity = host.activity
    private val ui = host.scope
    private val http get() = host.http
    private fun prefs() = host.prefs()

    private val transcript: TextView = root.findViewById(R.id.transcript)
    private val scroll: ScrollView = root.findViewById(R.id.scroll)
    private val bottombar: View = root.findViewById(R.id.bottombar)
    private val status: TextView = root.findViewById(R.id.status)
    private val workdir: TextView = root.findViewById(R.id.workdir)
    private val branch: TextView = root.findViewById(R.id.branch)
    private val talk: FloatingActionButton = root.findViewById(R.id.talk)
    private val scrollDown: FloatingActionButton = root.findViewById(R.id.scrollDown)

    private val fmt = TextFormat(activity)

    private class ModelOption(val value: String, val label: String)

    private val MODELS = listOf(
        ModelOption("", "default"),
        ModelOption("opus", "opus-latest"),
        ModelOption("claude-opus-4-8", "opus-4.8"),
        ModelOption("claude-opus-4-6", "opus-4.6"),
        ModelOption("sonnet", "sonnet-latest"),
        ModelOption("claude-sonnet-4-6", "sonnet-4.6"),
        ModelOption("haiku", "haiku-latest"),
        ModelOption("claude-haiku-4-5", "haiku-4.5"),
    )

    private val audio = AudioEngine(
        host,
        onReady = { setBusy(false); setStatus("ready") },
        onDevicesChanged = { updateBottom() },
    )

    private var chatJob: Job? = null
    private var busy = false
    private var tokIn = 0
    private var tokOut = 0
    private var thinkingStart = 0L
    private var statusWord = "ready"
    private var loadingHistory = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateStatusLine(); ticker.postDelayed(this, 1000) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTalk() {
        talk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (busy) interrupt() else startRecording(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (audio.isRecording()) stopAndSend(); true }
                else -> false
            }
        }
    }

    init {
        bindTalk()
        root.findViewById<TextView>(R.id.overflowBtn).setOnClickListener { showActions() }
        scrollDown.setOnClickListener { scroll.post { scroll.smoothScrollTo(0, transcript.bottom) } }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollDown() }
    }

    private fun updateScrollDown() {
        val child = scroll.getChildAt(0) ?: return
        val atBottom = child.bottom - (scroll.height + scroll.scrollY) <= 24
        scrollDown.visibility = if (atBottom) View.GONE else View.VISIBLE
    }

    private fun showActions() {
        val items = arrayOf(
            activity.getString(R.string.action_clear),
            activity.getString(R.string.action_compact),
            activity.getString(R.string.action_model, modelLabel()),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.actions_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> clearAgent()
                    1 -> compactAgent()
                    2 -> showModelPicker()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModelPicker() {
        val id = host.currentAgentId ?: return
        val labels = MODELS.map { it.label }.toTypedArray()
        val checked = MODELS.indexOfFirst { it.value == model(id) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.model_title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                prefs().edit().putString("model_$id", MODELS[which].value).apply()
                host.modelByAgent.remove(id)
                updateBottom()
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun model(id: Int) = prefs().getString("model_$id", "") ?: ""

    private fun modelLabel(): String {
        val id = host.currentAgentId ?: return "default"
        host.modelByAgent[id]?.let { return it }
        val v = model(id)
        return MODELS.firstOrNull { it.value == v }?.label ?: v.ifBlank { "default" }
    }

    private fun fmtModel(raw: String): String =
        Regex("-\\d{8}$").replace(raw.removePrefix("claude-"), "")

    fun onServiceEvent(type: String, text: String) {
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

    fun onResume() {
        audio.applyVoice()
        applyBarPosition()
    }

    fun destroy() {
        audio.destroy()
    }

    private fun applyBarPosition() {
        val top = prefs().getBoolean("statusbarTop", false)
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
        val id = host.currentAgentId ?: return
        ui.launch {
            http.clear(id)
            host.transcripts.remove(id)
            host.ctxByAgent.remove(id)
            tokIn = 0; tokOut = 0
            showTranscript()
            updateBottom()
            setStatus("cleared")
            host.drawerView.close()
        }
    }

    private fun compactAgent() {
        val id = host.currentAgentId ?: return
        host.drawerView.close()
        tokIn = 0; tokOut = 0
        setBusy(true)
        startTimer("compacting…")
        chatJob = ui.launch {
            val ok = http.compact(id)
            stopThinking()
            setBusy(false)
            if (!ok) { setStatus("compact failed"); return@launch }
            appendSpan(fmt.colored("— conversation compacted —\n\n", R.color.action_text, italic = true))
            host.ctxByAgent.remove(id)
            tokIn = 0; tokOut = 0
            updateBottom()
            setStatus("compacted")
        }
    }

    private fun micColor(res: Int) {
        talk.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, res))
    }

    fun setBusy(b: Boolean) {
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
        audio.stopSpeaking()
        stopThinking()
        http.cancel()
        chatJob?.cancel()
        audio.abortCapture()
        setBusy(false)
        setStatus("stopped")
        if (prefs().getBoolean("running", false)) {
            try { activity.startService(Intent(activity, VoiceService::class.java).setAction(VoiceService.ACTION_CANCEL)) } catch (e: Exception) { }
        }
    }

    fun updateBottom() {
        savePrefs()
        val a = host.agents.firstOrNull { it.id == host.currentAgentId }
        if (a == null) {
            workdir.text = activity.getString(R.string.no_agent)
            branch.visibility = View.GONE
            return
        }
        workdir.text = shortPath(a.dir)
        val b = a.branch?.let { it + if (a.dirty) " ✗" else "" } ?: ""
        branch.visibility = View.VISIBLE
        branch.text = (b + "   🎙 " + audio.micLabel() + "   ⚙ " + modelLabel()).trim()
        branch.setTextColor(ContextCompat.getColor(activity,
            if (a.dirty) R.color.branch_dirty else R.color.branch_text))
    }

    private fun startRecording() {
        if (audio.isRecording()) return
        if (!audio.hasMic()) { setStatus("grant microphone permission"); return }
        if (!audio.startCapture()) { setStatus("microphone unavailable"); return }
        micColor(R.color.mic_recording)
        setStatus("listening…")
    }

    private fun stopAndSend() {
        val wavBytes = audio.stopCapture() ?: run { setStatus("nothing recorded"); setBusy(false); return }
        val aid = host.currentAgentId
        if (aid == null) { setStatus("no agent selected — swipe right to add one"); setBusy(false); return }
        setBusy(true)
        setStatus("transcribing…")
        audio.beginTurn()
        audio.speakCue("transcribing")
        chatJob = ui.launch {
            val said = http.stt(wavBytes)
            if (said.isNullOrBlank()) { audio.endTurn(); setStatus("speech-to-text failed"); setBusy(false); return@launch }
            appendYou(said)
            startThinking()
            audio.speakCue("thinking")
            streamChat(aid, said)
        }
    }

    private suspend fun streamChat(aid: Int, text: String) {
        val narrate = prefs().getBoolean("narrate_$aid", false)
        var sawReply = false
        val ok = http.chat(text, aid, narrate, model(aid).ifBlank { null }) { event ->
            if (event is ChatEvent.Reply) sawReply = true
            withContext(Dispatchers.Main) { handleEvent(event) }
        }
        if ((!ok || !sawReply) && busy) { audio.endTurn(); stopThinking(); setStatus("agent failed"); setBusy(false) }
    }

    private fun handleEvent(e: ChatEvent) {
        when (e) {
            is ChatEvent.Action -> appendAction(e.label)
            is ChatEvent.Diff -> appendDiff(e.file, e.patch)
            is ChatEvent.Working -> {
                appendAction(e.text)
                audio.speakWorking(e.text)
            }
            is ChatEvent.Model -> {
                if (e.name.isNotBlank()) {
                    host.modelByAgent[host.currentAgentId ?: -1] = fmtModel(e.name)
                    updateBottom()
                }
            }
            is ChatEvent.Usage -> {
                e.tokIn?.let { tokIn = it }
                e.tokOut?.let { tokOut = it }
                val id = host.currentAgentId ?: -1
                val max = e.max ?: (host.ctxByAgent[id]?.second ?: 0)
                host.ctxByAgent[id] = Pair(tokIn, max)
                updateStatusLine()
                updateBottom()
            }
            is ChatEvent.Reply -> {
                stopThinking()
                appendReply(e.text)
                setStatus("speaking…")
                audio.speakReply(e.text, e.speech)
            }
            else -> {}
        }
    }

    private fun buffer(): SpannableStringBuilder =
        host.transcripts.getOrPut(host.currentAgentId ?: -1) { SpannableStringBuilder() }

    private fun appendSpan(cs: CharSequence) {
        buffer().append(cs)
        if (loadingHistory) return
        transcript.setText(buffer(), TextView.BufferType.SPANNABLE)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun showTranscript() {
        val buf = host.transcripts.getOrPut(host.currentAgentId ?: -1) { SpannableStringBuilder() }
        transcript.setText(buf, TextView.BufferType.SPANNABLE)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun renderHistory(events: List<ChatEvent>) {
        loadingHistory = true
        for (e in events) renderEvent(e)
        loadingHistory = false
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

    private fun appendYou(text: String) = appendSpan(fmt.you(text))

    private fun appendReply(text: String) = appendSpan(fmt.reply(text))

    private fun appendAction(label: String) = appendSpan(fmt.action(label))

    private fun appendDiff(file: String, patch: String) = appendSpan(fmt.diff(file, patch))

    fun setStatus(s: String) { statusWord = s; updateStatusLine() }

    private fun updateStatusLine() {
        val sb = StringBuilder(statusWord)
        if (statusWord == "thinking…" || statusWord == "compacting…") {
            sb.append("  ").append((System.currentTimeMillis() - thinkingStart) / 1000).append("s")
        }
        val ctx = host.ctxByAgent[host.currentAgentId ?: -1]
        if (ctx != null && ctx.first > 0) {
            sb.append("   ctx ").append(fmt.fmtTok(ctx.first))
            if (ctx.second > 0) sb.append("/").append(fmt.fmtTok(ctx.second))
        }
        if (tokIn > 0 || tokOut > 0) {
            sb.append("   ↑").append(fmt.fmtTok(tokIn)).append(" ↓").append(fmt.fmtTok(tokOut))
        }
        status.text = sb.toString()
    }

    private fun savePrefs() {
        prefs().edit().putInt("agent", host.currentAgentId ?: -1).apply()
    }

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
}
