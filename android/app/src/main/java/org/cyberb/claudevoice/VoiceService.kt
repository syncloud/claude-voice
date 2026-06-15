package org.cyberb.claudevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_TALK_START = "talk_start"
        const val ACTION_TALK_STOP = "talk_stop"
        const val ACTION_ARM = "arm_toggle"
        const val ACTION_RECONFIG = "reconfig"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "voice"
        const val NOTIF = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = BridgeHttp({ base() }, timeoutSeconds = 0)
    private val sampleRate = 16000
    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var toneGen: ToneGenerator? = null
    private var mediaSession: MediaSession? = null
    private var silent: MediaPlayer? = null
    private var turnJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun holdWake() { try { wakeLock?.acquire(16 * 60 * 1000L) } catch (e: Exception) { } }
    private fun releaseWake() { try { val w = wakeLock; if (w != null && w.isHeld) w.release() } catch (e: Exception) { } }

    private fun trigger() = prefs().getString("trigger", "accessibility") ?: "accessibility"

    private fun toggleTalk() {
        when {
            recording.get() -> stopRecAndSend()
            starting.get() -> { handler.removeCallbacks(beginRunnable); starting.set(false); releaseBt(); notify("ready") }
            else -> startRec()
        }
    }

    private fun cancelTalk() {
        handler.removeCallbacks(beginRunnable)
        starting.set(false)
        recording.set(false)
        try { recordThread?.join() } catch (e: Exception) { }
        pcm.reset()
        releaseBt()
        http.cancel()
        turnJob?.cancel()
        tts?.stop()
        try { player?.stop() } catch (e: Exception) { }
        player?.release(); player = null
        releaseWake()
        beep(ToneGenerator.TONE_PROP_NACK)
        notify("cancelled")
    }

    private fun setupTrigger() {
        teardownTrigger()
        val t = trigger()
        if (t != "msvolume" && t != "mediabutton") return
        val s = MediaSession(this, "claudevoice")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { toggleTalk() }
            override fun onPause() { toggleTalk() }
            override fun onSkipToNext() { cancelTalk() }
            override fun onSkipToPrevious() { cancelTalk() }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
        )
        if (t == "msvolume") {
            s.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) { if (direction > 0) toggleTalk() }
            })
        }
        s.isActive = true
        mediaSession = s
        startSilent()
    }

    private fun teardownTrigger() {
        try { mediaSession?.isActive = false; mediaSession?.release() } catch (e: Exception) { }
        mediaSession = null
        stopSilent()
    }

    private fun startSilent() {
        try {
            val f = File(cacheDir, "silent.wav")
            f.writeBytes(wav(ByteArray(sampleRate)))
            silent?.release()
            silent = MediaPlayer().apply {
                setDataSource(f.absolutePath); isLooping = true; setVolume(0f, 0f); prepare(); start()
            }
        } catch (e: Exception) { }
    }

    private fun stopSilent() {
        try { silent?.release() } catch (e: Exception) { }
        silent = null
    }

    private fun beep(tone: Int) {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            toneGen?.startTone(tone, 150)
        } catch (e: Exception) { }
    }

    private fun prefs() = getSharedPreferences("cv", MODE_PRIVATE)
    private fun base() = (prefs().getString("bridge", "http://127.0.0.1:8765") ?: "").trimEnd('/')
    private fun agent() = prefs().getInt("agent", -1)
    private fun usePiper() = prefs().getBoolean("piper", false)
    private fun piperVoice(): String? = prefs().getString("voice", null)
    private fun armed() = prefs().getBoolean("armed", false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudevoice:turn")
            .apply { setReferenceCounted(false) }
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale.US
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == "reply") { releaseWake(); notify("ready") } }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) { if (utteranceId == "reply") { releaseWake(); notify("ready") } }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK_START -> startRec()
            ACTION_TALK_STOP -> stopRecAndSend()
            ACTION_CANCEL -> cancelTalk()
            ACTION_ARM -> { prefs().edit().putBoolean("armed", !armed()).apply(); notify("ready") }
            ACTION_RECONFIG -> setupTrigger()
            ACTION_STOP -> { teardownTrigger(); @Suppress("DEPRECATION") stopForeground(true); stopSelf(); return START_NOT_STICKY }
            else -> { startForeground(NOTIF, buildNotif("ready")); setupTrigger() }
        }
        return START_STICKY
    }

    private val handler = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)
    private var commDeviceSet = false
    private val beginRunnable = Runnable { starting.set(false); beginRecord(true) }
    private val heartbeat = object : Runnable {
        override fun run() {
            holdWake()
            if (prefs().getBoolean("speakStatus", true)) tts?.speak("still working", TextToSpeech.QUEUE_ADD, null, "cue")
            handler.postDelayed(this, 60000)
        }
    }

    private fun btMic() = prefs().getBoolean("btmic", false)

    private fun engageBt(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val dev = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return false
            commDeviceSet = am.setCommunicationDevice(dev)
            commDeviceSet
        } catch (e: Exception) { false }
    }

    private fun releaseBt() {
        if (commDeviceSet && Build.VERSION.SDK_INT >= 31) {
            try { (getSystemService(AUDIO_SERVICE) as AudioManager).clearCommunicationDevice() } catch (e: Exception) { }
        }
        commDeviceSet = false
    }

    private fun startRec() {
        if (recording.get() || starting.get()) return
        pcm.reset()
        if (btMic() && engageBt()) {
            starting.set(true)
            notify("connecting buds…")
            handler.postDelayed(beginRunnable, 1200)
        } else {
            beginRecord(false)
        }
    }

    private fun beginRecord(bt: Boolean) {
        val source = if (bt) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        } catch (e: Exception) { releaseBt(); notify("mic unavailable"); return }
        recording.set(true)
        beep(ToneGenerator.TONE_PROP_BEEP)
        notify("listening…")
        recordThread = Thread {
            val buf = ByteArray(minBuf)
            rec.startRecording()
            while (recording.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
            rec.stop(); rec.release()
        }.also { it.start() }
    }

    private fun stopRecAndSend() {
        if (!recording.get()) return
        recording.set(false)
        recordThread?.join()
        beep(ToneGenerator.TONE_PROP_BEEP2)
        releaseBt()
        val audio = synchronized(pcm) { pcm.toByteArray() }
        if (audio.isEmpty()) { notify("ready"); return }
        val aid = agent()
        if (aid < 0) { notify("no agent — open the app"); return }
        notify("thinking…")
        holdWake()
        tts?.speak("thinking", TextToSpeech.QUEUE_FLUSH, null, "cue")
        handler.postDelayed(heartbeat, 60000)
        turnJob = scope.launch {
            val said = http.stt(wav(audio))
            if (said.isNullOrBlank()) { handler.removeCallbacks(heartbeat); releaseWake(); notify("speech-to-text failed"); return@launch }
            broadcast("you", said)
            val narrate = prefs().getBoolean("narrate_$aid", false)
            var replyText = ""
            var speech = ""
            val model = prefs().getString("model_$aid", "")?.ifBlank { null }
            http.chat(said, aid, narrate, model) { ev ->
                if (ev is ChatEvent.Reply) { replyText = ev.text; speech = ev.speech }
            }
            handler.removeCallbacks(heartbeat)
            val toSpeak = if (speech.isNotBlank()) speech else clean(replyText)
            if (toSpeak.isBlank()) { releaseWake(); notify("no response"); return@launch }
            broadcast("reply", if (replyText.isNotBlank()) replyText else toSpeak)
            notify("speaking…")
            speakOut(toSpeak)
        }
    }

    private fun speakOut(text: String) {
        if (usePiper()) {
            scope.launch {
                val wav = http.tts(text, piperVoice())
                if (wav != null && wav.isNotEmpty()) { playWav(wav); return@launch }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    private fun playWav(wav: ByteArray) {
        try {
            val f = File(cacheDir, "svc-reply.wav")
            f.writeBytes(wav)
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setWakeMode(this@VoiceService, PowerManager.PARTIAL_WAKE_LOCK)
                setOnCompletionListener { it.release(); if (player === it) player = null; releaseWake(); notify("ready") }
                prepare(); start()
            }
        } catch (e: Exception) { releaseWake(); notify("ready") }
    }

    private fun clean(t: String): String {
        var s = t
        s = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL).replace(s, " code block ")
        s = Regex("`([^`]*)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s, "$1")
        s = Regex("https?://\\S+").replace(s, "link")
        s = Regex("[#*_>|~]").replace(s, " ")
        s = Regex("\\s+").replace(s, " ").trim()
        return s
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

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Claude Voice", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun action(a: String) = PendingIntent.getService(
        this, a.hashCode(), Intent(this, VoiceService::class.java).setAction(a),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotif(status: String): Notification {
        val armLabel = if (armed()) "Disarm" else "Arm vol-up"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Voice")
            .setContentText(status + (if (armed()) "  ·  vol-up armed" else ""))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, armLabel, action(ACTION_ARM)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP)).build())
        return b.build()
    }

    private fun notify(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF, buildNotif(status))
        broadcast("status", status)
    }

    private fun broadcast(type: String, text: String) {
        try {
            sendBroadcast(
                Intent("org.cyberb.claudevoice.EVENT").setPackage(packageName)
                    .putExtra("type", type).putExtra("text", text)
            )
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording.set(false)
        teardownTrigger()
        releaseWake()
        player?.release(); player = null
        toneGen?.release()
        tts?.shutdown()
    }
}
