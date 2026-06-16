package org.syncloud.claudevoice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AudioEngine(
    private val host: VoiceHost,
    private val onReady: () -> Unit,
    private val onDevicesChanged: () -> Unit,
) : TextToSpeech.OnInitListener {

    private val activity = host.activity
    private val ui = host.scope
    private val http get() = host.http
    private fun prefs() = host.prefs()
    private fun usePiper() = prefs().getBoolean("piper", false)
    private fun speakStatusOn() = prefs().getBoolean("speakStatus", true)
    private fun piperVoice(): String? = prefs().getString("voice", null)?.ifBlank { null }

    private val sampleRate = 16000
    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    private val tts = TextToSpeech(activity, this)
    private var player: MediaPlayer? = null
    private var toneGen: ToneGenerator? = null
    private var speakJob: Job? = null

    private val wakeLock = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudevoice:speak")
        .apply { setReferenceCounted(false) }

    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    private var focusReq: AudioFocusRequest? = null
    private var turnActive = false

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(mediaAttrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusReq = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) { }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq?.let { audioManager.abandonAudioFocusRequest(it) }
                focusReq = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) { }
    }

    private fun holdWake() {
        try { wakeLock.acquire(16 * 60 * 1000L) } catch (e: Exception) { }
        if (!turnActive) {
            turnActive = true
            requestFocus()
            PlaybackService.start(activity)
        }
    }

    private fun releaseWake() {
        try { if (wakeLock.isHeld) wakeLock.release() } catch (e: Exception) { }
        if (turnActive) {
            turnActive = false
            abandonFocus()
            PlaybackService.stop(activity)
        }
    }

    private fun ready() { releaseWake(); onReady() }

    fun beginTurn() = holdWake()
    fun endTurn() = releaseWake()

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { onDevicesChanged() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { onDevicesChanged() }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioCb, Handler(Looper.getMainLooper()))
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        tts.setAudioAttributes(mediaAttrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == "reply") activity.runOnUiThread { ready() } }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) { activity.runOnUiThread { ready() } }
        })
        activity.runOnUiThread { applyVoice() }
    }

    fun applyVoice() {
        val name = prefs().getString("ttsVoice", null) ?: return
        try { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } } catch (e: Exception) { }
    }

    fun beep(tone: Int) {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            toneGen?.startTone(tone, 150)
        } catch (e: Exception) { }
    }

    fun speakCue(word: String) {
        if (speakStatusOn()) tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    fun speakWorking(text: String) {
        holdWake()
        if (speakStatusOn()) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "working")
    }

    fun speakReply(replyText: String, narration: String) {
        holdWake()
        val spoken = if (narration.isNotBlank()) narration else forSpeech(replyText)
        if (usePiper()) speakPiper(spoken) else tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stopSpeaking() {
        tts.stop()
        speakJob?.cancel()
        stopPlayer()
        releaseWake()
    }

    private fun speakPiper(fullText: String) {
        val sentences = splitSentences(fullText)
        if (sentences.isEmpty()) { ready(); return }
        speakJob = ui.launch {
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
            ready()
        }
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

    private fun stripMd(t: String): String {
        var s = t
        s = Regex("```[\\s\\S]*?```").replace(s, " code block ")
        s = Regex("`([^`]*)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s, "$1")
        s = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE).replace(s, "")
        s = Regex("(\\*\\*|\\*|__|_|#+|>|~~|~)").replace(s, "")
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
            val f = File(activity.cacheDir, "reply.wav")
            f.writeBytes(wav)
            stopPlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(mediaAttrs)
            mp.setWakeMode(activity, PowerManager.PARTIAL_WAKE_LOCK)
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

    fun isRecording() = recording.get()

    fun hasMic() = ContextCompat.checkSelfPermission(
        activity, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun startCapture(): Boolean {
        if (recording.get()) return true
        pcm.reset()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
        } catch (e: SecurityException) { return false }
        recording.set(true)
        beep(ToneGenerator.TONE_PROP_BEEP)
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
        return true
    }

    fun stopCapture(): ByteArray? {
        if (!recording.get()) return null
        recording.set(false)
        recordThread?.join()
        beep(ToneGenerator.TONE_PROP_BEEP2)
        val audio = synchronized(pcm) { pcm.toByteArray() }
        if (audio.isEmpty()) return null
        return wav(audio)
    }

    fun abortCapture() { recording.set(false) }

    fun micLabel() = if (btInputDevice() != null) "buds" else "phone"

    private fun btInputDevice(): AudioDeviceInfo? {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (e: Exception) { null }
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

    fun destroy() {
        recording.set(false)
        stopPlayer()
        releaseWake()
        toneGen?.release()
        audioManager.unregisterAudioDeviceCallback(audioCb)
        tts.shutdown()
    }
}
