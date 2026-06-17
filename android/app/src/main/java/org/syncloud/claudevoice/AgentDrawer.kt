package org.syncloud.claudevoice

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.launch

class AgentDrawer(private val host: VoiceHost, root: View) {

    private val activity = host.activity
    private val ui = host.scope
    private val http get() = host.http

    private val drawer: DrawerLayout = root.findViewById(R.id.drawer)
    private val agentList: ListView = root.findViewById(R.id.agentList)
    private val serverStatus: TextView = root.findViewById(R.id.serverStatus)

    private var agentsSig = ""
    private val poller = Handler(Looper.getMainLooper())
    private val pollRun = object : Runnable {
        override fun run() { healthCheck(); poller.postDelayed(this, 4000) }
    }

    init {
        agentList.setOnItemClickListener { _, _, pos, _ ->
            host.currentAgentId = host.agents[pos].id
            host.mainView.updateBottom()
            host.mainView.showTranscript()
            drawer.closeDrawers()
        }
        root.findViewById<Button>(R.id.addAgent).setOnClickListener { openDirPicker() }
        root.findViewById<Button>(R.id.openSettings).setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    fun close() { drawer.closeDrawers() }
    fun startPolling() { poller.removeCallbacks(pollRun); poller.post(pollRun) }
    fun stopPolling() { poller.removeCallbacks(pollRun) }

    fun refresh() = ui.launch {
        val list = http.agents() ?: run { host.mainView.setStatus("no bridge at ${host.base()}"); return@launch }
        setAgents(list)
        if (host.currentAgentId == null || host.agents.none { it.id == host.currentAgentId }) {
            host.currentAgentId = host.agents.firstOrNull()?.id
        }
        host.mainView.updateBottom()
    }

    private fun setServerUp(up: Boolean) {
        serverStatus.text = if (up) "● bridge" else "● bridge down"
        serverStatus.setTextColor(ContextCompat.getColor(activity, if (up) R.color.dot_ok else R.color.dot_down))
    }

    private fun healthCheck() = ui.launch {
        val ok = http.health()
        setServerUp(ok)
        if (!ok) return@launch
        host.mainView.bridgeUp()
        val list = http.agents() ?: return@launch
        val sig = list.joinToString("|") { "${it.id}:${it.branch}:${it.dirty}:${it.exists}" }
        if (sig != agentsSig) {
            agentsSig = sig
            setAgents(list)
            if (host.currentAgentId == null || host.agents.none { it.id == host.currentAgentId }) {
                host.currentAgentId = host.agents.firstOrNull()?.id
            }
            host.mainView.updateBottom()
        }
    }

    private fun setAgents(list: List<Agent>) {
        host.agents.clear()
        host.agents.addAll(list)
        agentList.adapter = AgentAdapter(host.agents.toList())
    }

    private fun removeAgent(a: Agent) {
        AlertDialog.Builder(activity)
            .setTitle("Remove agent")
            .setMessage("Remove “${a.name}”? This just stops tracking it — the directory and its files are untouched.")
            .setPositiveButton("Remove") { _, _ ->
                ui.launch {
                    val list = http.removeAgent(a.id) ?: run { host.mainView.setStatus("remove failed"); return@launch }
                    host.transcripts.remove(a.id)
                    host.ctxByAgent.remove(a.id)
                    setAgents(list)
                    agentsSig = ""
                    if (host.currentAgentId == a.id) {
                        host.currentAgentId = host.agents.firstOrNull()?.id
                        host.mainView.showTranscript()
                    }
                    host.mainView.updateBottom()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class AgentAdapter(private val items: List<Agent>) :
        ArrayAdapter<Agent>(activity, R.layout.item_agent, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_agent, parent, false)
            val a = items[position]
            v.findViewById<TextView>(R.id.agentName).text = a.name
            val b = v.findViewById<TextView>(R.id.agentBranch)
            if (a.branch != null) {
                b.visibility = View.VISIBLE
                b.text = a.branch + if (a.dirty) "  ✗" else ""
                b.setTextColor(ContextCompat.getColor(activity,
                    if (a.dirty) R.color.branch_dirty else R.color.branch_text))
            } else {
                b.visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.agentDot).setTextColor(
                ContextCompat.getColor(activity, if (a.exists) R.color.dot_ok else R.color.dot_down))
            val nar = v.findViewById<TextView>(R.id.agentNarrate)
            nar.alpha = if (host.prefs().getBoolean("narrate_${a.id}", false)) 1f else 0.35f
            nar.setOnClickListener {
                val cur = host.prefs().getBoolean("narrate_${a.id}", false)
                host.prefs().edit().putBoolean("narrate_${a.id}", !cur).apply()
                nar.alpha = if (!cur) 1f else 0.35f
            }
            v.findViewById<TextView>(R.id.agentClose).setOnClickListener { removeAgent(a) }
            return v
        }
    }

    private fun openDirPicker() {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0)
        }
        val pathView = TextView(activity).apply { setPadding(0, 0, 0, px(8)); textSize = 12f }
        val list = ListView(activity)
        container.addView(pathView)
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(360)))

        var browseDir = ""
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Add agent — pick a folder")
            .setView(container)
            .setPositiveButton("Use this folder", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun load(dir: String) {
            ui.launch {
                val listing = http.listDir(dir) ?: run { host.mainView.setStatus("cannot list folder"); return@launch }
                browseDir = listing.dir
                pathView.text = browseDir
                val rows = ArrayList<String>()
                val targets = ArrayList<String>()
                if (listing.parent != null) { rows.add("⬆  .."); targets.add(listing.parent) }
                for (n in listing.dirs) {
                    rows.add("📁  $n")
                    targets.add(browseDir.trimEnd('/') + "/" + n)
                }
                list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, rows)
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
            AlertDialog.Builder(activity)
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
            val list = http.addAgent(dir, session) ?: run { host.mainView.setStatus("add agent failed"); return@launch }
            setAgents(list)
            agentsSig = ""
            val id = host.agents.firstOrNull { it.dir == dir }?.id ?: host.agents.maxByOrNull { it.id }?.id
            host.currentAgentId = id
            if (session != null && id != null) {
                host.transcripts[id] = SpannableStringBuilder()
                val events = http.history(dir, session)
                if (events != null) host.mainView.renderHistory(events)
            }
            host.mainView.updateBottom()
            host.mainView.showTranscript()
            drawer.closeDrawers()
        }
    }
}
