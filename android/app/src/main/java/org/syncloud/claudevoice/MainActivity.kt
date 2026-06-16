package org.syncloud.claudevoice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable

@Serializable
data class Agent(
    val id: Int,
    val name: String = "",
    val dir: String = "",
    val branch: String? = null,
    val dirty: Boolean = false,
    val exists: Boolean = true,
)

fun shortPath(dir: String): String {
    val parts = dir.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) dir else "…/" + parts.takeLast(2).joinToString("/")
}

interface VoiceHost {
    val activity: AppCompatActivity
    val scope: CoroutineScope
    val http: BridgeHttp
    val agents: MutableList<Agent>
    var currentAgentId: Int?
    val transcripts: MutableMap<Int, SpannableStringBuilder>
    val ctxByAgent: MutableMap<Int, Pair<Int, Int>>
    val modelByAgent: MutableMap<Int, String>
    val mainView: MainView
    val drawerView: AgentDrawer
    fun prefs(): SharedPreferences
    fun base(): String
}

class MainActivity : AppCompatActivity(), VoiceHost {

    override val activity get() = this
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override var http = BridgeHttp({ base() })
    override val agents = mutableListOf<Agent>()
    override var currentAgentId: Int? = null
    override val transcripts = mutableMapOf<Int, SpannableStringBuilder>()
    override val ctxByAgent = mutableMapOf<Int, Pair<Int, Int>>()
    override val modelByAgent = mutableMapOf<Int, String>()
    override lateinit var mainView: MainView
    override lateinit var drawerView: AgentDrawer

    override fun prefs(): SharedPreferences = getSharedPreferences("cv", MODE_PRIVATE)
    override fun base() = (prefs().getString("bridge", "http://127.0.0.1:8765") ?: "").trim().trimEnd('/')

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val text = intent.getStringExtra("text") ?: ""
            mainView.onServiceEvent(type, text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.drawer)
        drawerView = AgentDrawer(this, root)
        mainView = MainView(this, root)
        ContextCompat.registerReceiver(this, eventReceiver, IntentFilter("org.syncloud.claudevoice.EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        drawerView.refresh()
    }

    override fun onResume() {
        super.onResume()
        drawerView.startPolling()
        mainView.onResume()
    }

    override fun onPause() {
        super.onPause()
        drawerView.stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainView.destroy()
        try { unregisterReceiver(eventReceiver) } catch (e: Exception) { }
    }
}
