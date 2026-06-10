package org.cyberb.claudevoice

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private lateinit var tts: TextToSpeech

    private lateinit var bridgeUrl: EditText
    private lateinit var usePiper: CheckBox
    private lateinit var speakStatus: CheckBox
    private lateinit var bgMode: CheckBox
    private lateinit var btMic: CheckBox
    private lateinit var triggerGroup: RadioGroup
    private lateinit var voiceSpinner: Spinner

    private val voices = mutableListOf<Voice>()
    private var piperNames = listOf<String>()

    private fun prefs() = getSharedPreferences("cv", MODE_PRIVATE)
    private fun base() = (prefs().getString("bridge", "http://127.0.0.1:8765") ?: "").trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings)

        bridgeUrl = findViewById(R.id.bridgeUrl)
        usePiper = findViewById(R.id.piperSwitch)
        speakStatus = findViewById(R.id.speakStatusBox)
        bgMode = findViewById(R.id.bgMode)
        btMic = findViewById(R.id.btMicBox)
        triggerGroup = findViewById(R.id.triggerGroup)
        voiceSpinner = findViewById(R.id.voiceSpinner)

        bridgeUrl.setText(prefs().getString("bridge", "http://127.0.0.1:8765"))
        usePiper.isChecked = prefs().getBoolean("piper", false)
        speakStatus.isChecked = prefs().getBoolean("speakStatus", true)
        bgMode.isChecked = prefs().getBoolean("running", false)
        btMic.isChecked = prefs().getBoolean("btmic", false)
        when (prefs().getString("trigger", "accessibility")) {
            "msvolume" -> triggerGroup.check(R.id.trigMsVol)
            "mediabutton" -> triggerGroup.check(R.id.trigMedia)
            else -> triggerGroup.check(R.id.trigAcc)
        }

        usePiper.setOnCheckedChangeListener { _, c ->
            prefs().edit().putBoolean("piper", c).apply()
            if (c) loadPiperVoices() else loadTtsVoices()
        }
        speakStatus.setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("speakStatus", c).apply() }
        bgMode.setOnCheckedChangeListener { _, c ->
            prefs().edit().putBoolean("running", c).apply()
            saveBridge()
            if (c) {
                if (Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START))
            } else {
                startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            }
        }
        triggerGroup.setOnCheckedChangeListener { _, id ->
            val t = when (id) {
                R.id.trigMsVol -> "msvolume"
                R.id.trigMedia -> "mediabutton"
                else -> "accessibility"
            }
            prefs().edit().putString("trigger", t).apply()
            if (prefs().getBoolean("running", false)) {
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_RECONFIG))
            }
        }
        findViewById<Button>(R.id.keySetup).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { }
        }
        btMic.setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("btmic", c).apply() }

        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        runOnUiThread { if (usePiper.isChecked) loadPiperVoices() else loadTtsVoices() }
    }

    private fun loadTtsVoices() {
        val all = try { tts.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val list = all.filter { it.locale.language == "en" }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toString() }.thenBy { it.name })
            .take(3)
        voices.clear(); voices.addAll(list)
        if (voices.isEmpty()) return
        val labels = voices.map { v -> "${v.locale.displayName}${if (v.isNetworkConnectionRequired) " (net)" else ""}" }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = prefs().getString("ttsVoice", null)
        voices.indexOfFirst { it.name == saved }.takeIf { it >= 0 }?.let { voiceSpinner.setSelection(it) }
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                voices.getOrNull(pos)?.let { prefs().edit().putString("ttsVoice", it.name).apply() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadPiperVoices() {
        ui.launch {
            val s = httpGet("${base()}/voices") ?: return@launch
            piperNames = try {
                val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }
            } catch (e: Exception) { emptyList() }
            if (piperNames.isEmpty()) return@launch
            voiceSpinner.adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, piperNames)
            val saved = prefs().getString("voice", null)
            piperNames.indexOf(saved).takeIf { it >= 0 }?.let { voiceSpinner.setSelection(it) }
            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    piperNames.getOrNull(pos)?.let { prefs().edit().putString("voice", it).apply() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null }
    }

    private fun saveBridge() {
        prefs().edit().putString("bridge", bridgeUrl.text.toString().trim()).apply()
    }

    override fun onPause() {
        super.onPause()
        saveBridge()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { tts.shutdown() } catch (e: Exception) { }
    }
}
