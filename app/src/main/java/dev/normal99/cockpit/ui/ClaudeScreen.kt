package dev.normal99.cockpit.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.normal99.cockpit.claude.Broker
import dev.normal99.cockpit.claude.ClaudeRepo
import dev.normal99.cockpit.claude.ClaudeState
import dev.normal99.cockpit.claude.EVENT_NAMES
import dev.normal99.cockpit.claude.GitHub
import dev.normal99.cockpit.claude.PullRequest
import dev.normal99.cockpit.claude.RootRequest
import dev.normal99.cockpit.claude.Status
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.claude.ciTone
import dev.normal99.cockpit.claude.requestStatusTone
import dev.normal99.cockpit.data.HostConnection
import kotlinx.coroutines.launch

/** The Claude page (the "claude" Cockpit package) as a native phone screen. */
@Composable
fun ClaudeScreen(conn: HostConnection, actions: Actions, contentPadding: PaddingValues, onTurnOnAdmin: () -> Unit) {
    val repo = remember(conn.session) { ClaudeRepo(conn.session) }
    val state by remember(repo) { repo.state }.collectAsState(initial = ClaudeState())
    val admin by conn.admin.collectAsState()
    val canAct = admin != "none"
    val now = rememberNow()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // item indexes for the summary chips to jump to
    val idx = mapOf("requests" to 2, "sessions" to 3, "branches" to 4, "rig" to 5, "storage" to 6)

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Muted(state.status?.let { "Status ${ago(it.updated, now)}" } ?: "Waiting for status…", Modifier.padding(horizontal = 4.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (admin == "none") {
                    Banner("Limited access: buttons are turned off",
                        "Approving requests and running actions needs Cockpit's administrative access.") {
                        Button(onTurnOnAdmin) { Text("Turn on admin access") }
                    }
                }
                Summary(state) { key -> idx[key]?.let { scope.launch { list.animateScrollToItem(it) } } }
            }
        }
        item { Requests(state.broker, repo, actions, canAct, now) }
        item { Sessions(state.status, repo, actions, canAct, now) }
        item { Branches(state.status, state.github, now) }
        item { RigPanel(state, repo, actions, canAct, now) }
        item { StoragePanel(state, repo, actions, canAct, now) }
        item { SnapshotsPanel(state, repo, actions, canAct) }
        item { ActivityPanel(state, now) }
    }
}

@Composable
private fun Summary(st: ClaudeState, jump: (String) -> Unit) {
    val chips = buildList {
        val waiting = st.broker?.pending?.size ?: 0
        add(Triple("requests", if (waiting > 0) "$waiting root request${if (waiting > 1) "s" else ""} waiting" else "No root requests", if (waiting > 0) Tone.ORANGE else Tone.GREEN))
        st.status?.let { s ->
            val ok = s.sessions.count { it.up && it.rc == "connected" }
            add(Triple("sessions", "Claude $ok/${s.sessions.size} connected", if (ok == s.sessions.size && s.sessions.isNotEmpty()) Tone.GREEN else Tone.RED))
        }
        st.rig?.let { r ->
            add(Triple("rig", "Rig " + if (r.state == "booting") "waking" else r.state, when (r.state) { "up" -> Tone.GREEN; "booting" -> Tone.ORANGE; else -> Tone.NEUTRAL }))
        }
        st.storage?.let { s ->
            val p = s.disk.pct
            add(Triple("storage", "Disk $p%", if (p >= 90) Tone.RED else if (p >= 80) Tone.ORANGE else Tone.GREEN))
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips) { (key, text, tone) -> Chip(text, tone, Modifier.clickable { jump(key) }) }
    }
}

/* ------------------------------------------------------------------ root requests */

@Composable
private fun Requests(br: Broker?, repo: ClaudeRepo, actions: Actions, canAct: Boolean, now: Long) {
    val open = br?.open.orEmpty()
    Section("Root requests", trailing = { if (open.isNotEmpty()) Chip("${open.size} waiting", Tone.ORANGE) }) {
        if (br == null) {
            Empty("Broker status not available yet.")
            return@Section
        }
        if (open.isEmpty()) Empty("Nothing waiting for you.")
        open.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider()
            RequestCard(r, repo, actions, canAct, now)
        }
        val hist = br.history.take(6)
        if (hist.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            hist.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(r.status, requestStatusTone(r.status))
                        Mono(r.command, Modifier.weight(1f), maxLines = 1)
                    }
                    Muted(listOfNotNull(r.reason.takeIf { it.isNotBlank() }, ago(r.created, now), r.exit?.let { "exit $it" }, r.decidedVia?.let { "via $it" }).joinToString(" · "), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun RequestCard(r: RootRequest, repo: ClaudeRepo, actions: Actions, canAct: Boolean, now: Long) {
    val k = "req:${r.id}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Mono("$ ${r.command}", Modifier.padding(10.dp))
        }
        if (r.reason.isNotBlank()) Text(r.reason, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.isPending) Chip("waiting for you", Tone.ORANGE) else Chip("approved, Claude runs it next", Tone.BLUE)
        }
        Muted("${r.id} · ${ago(r.created, now)} · in ${r.cwd}" + (r.detail?.let { "\n$it" } ?: ""))
        if (r.isPending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton("Approve", actions.isBusy("$k:a"), Kind.PRIMARY, canAct && !actions.isBusy("$k:d"), Modifier.weight(1f)) {
                    actions.run("$k:a", "Approved ${r.id}. It runs now; the result appears under Recent.") { repo.approve(r.id) }
                }
                ActionButton("Deny", actions.isBusy("$k:d"), Kind.DANGER, canAct && !actions.isBusy("$k:a"), Modifier.weight(1f)) {
                    actions.run("$k:d", "Denied ${r.id}. Claude gets an error.") { repo.deny(r.id) }
                }
            }
            ConfirmActionButton(
                "Always allow", "Approve now and let Claude run this exact command without asking from now on?\n\n$ ${r.command}",
                confirmText = "Yes, always allow", busy = actions.isBusy("$k:always"), kind = Kind.TEXT, confirmKind = Kind.PRIMARY, enabled = canAct,
            ) { actions.run("$k:always", "Approved and added to the Auto tier.") { repo.alwaysAllow(r.id) } }
        }
    }
}

/* ------------------------------------------------------------------ sessions */

@Composable
private fun RcChip(rc: String) = when (rc) {
    "connected" -> Chip("RC connected", Tone.GREEN)
    "down" -> Chip("RC down", Tone.RED)
    else -> Chip("RC unknown", Tone.ORANGE)
}

@Composable
private fun Sessions(st: Status?, repo: ClaudeRepo, actions: Actions, canAct: Boolean, now: Long) {
    val ss = st?.sessions.orEmpty()
    val up = ss.count { it.up }
    Section("Sessions", trailing = { if (ss.isNotEmpty()) Chip("$up/${ss.size} up", if (up == ss.size) Tone.GREEN else Tone.RED) }) {
        if (st == null) {
            Empty("No status yet (updated every minute).")
            return@Section
        }
        if (ss.isEmpty()) Banner("The tmux session \"claude\" is not running", "The watchdog recreates it within 5 minutes, or restart it now.", Tone.RED)
        // uptime keeps counting from the file's own timestamp, like the web page
        val extra = st.updated?.let { ((now - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0
        ss.forEach { s ->
            val k = "win:${s.window}"
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.window, fontWeight = FontWeight.SemiBold)
                    if (s.up) Chip("up", Tone.GREEN) else Chip("down", Tone.RED)
                    RcChip(s.rc)
                }
                Muted((if (s.up) "running ${duration(s.uptimeS + extra)}" else "not running") +
                    " · ${s.restartsToday} restart${if (s.restartsToday == 1) "" else "s"} today")
                ButtonRow {
                    if (s.rc != "connected") ActionButton("Reconnect RC", actions.isBusy("$k:rc"), enabled = canAct) {
                        actions.run("$k:rc") { repo.reconnect(s.window) }
                    }
                    ConfirmActionButton("Restart", "Restart Claude in \"${s.window}\"? Whatever it is doing right now stops.",
                        busy = actions.isBusy(k), enabled = canAct) {
                        actions.run(k, "Restarted ${s.window}; it reconnects in about 20 s.") { repo.restartWindow(s.window) }
                    }
                }
            }
            HorizontalDivider()
        }
        ButtonRow {
            ConfirmActionButton("New session",
                "Start a new Claude session in ~/projects? It appears in the Claude app, where you tell it which project to work on.",
                confirmText = "Yes, start it", busy = actions.isBusy("new"), kind = Kind.PRIMARY, confirmKind = Kind.PRIMARY, enabled = canAct) {
                actions.run("new") { repo.newSession().let { "Started session $it" } }
            }
            if (ss.isNotEmpty()) {
                ConfirmActionButton("Restart all", "Restart every Claude session on this box?", busy = actions.isBusy("all"), enabled = canAct) {
                    actions.run("all", "Restarting all sessions.") { repo.restartAll() }
                }
            } else {
                ActionButton("Start sessions", actions.isBusy("all"), enabled = canAct) { actions.run("all", "Starting sessions.") { repo.restartAll() } }
            }
        }
    }
}

/* ------------------------------------------------------------------ branches and PRs */

@Composable
private fun Branches(st: Status?, gh: GitHub?, now: Long) {
    val prs = gh?.prs.orEmpty()
    val byBranch = prs.associateBy { it.branch }
    val branches = st?.branches.orEmpty()
    val ctx = LocalContext.current
    val open = { url: String -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }
    Section("Branches and PRs", trailing = { if (prs.isNotEmpty()) Chip("${prs.size} open PR${if (prs.size > 1) "s" else ""}", Tone.BLUE) }) {
        gh?.error?.let { Banner("GitHub data is stale", it.lineSequence().first()) }
        if (branches.isEmpty() && prs.isEmpty()) Empty("No claude/* branches or open PRs.")
        branches.forEach { b ->
            val pr = byBranch[b.branch]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mono(b.branch, maxLines = 1)
                Muted("+${b.ahead} / −${b.behind} vs main · ${ago(b.lastCommit, now)}")
                if (b.subject.isNotBlank()) Text(b.subject, style = MaterialTheme.typography.bodyMedium)
                pr?.let { Checks(it); TextButton({ open(it.url) }) { Text("PR #${it.number} on GitHub ↗") } }
            }
        }
        prs.filter { p -> branches.none { it.branch == p.branch } }.forEach { pr ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${pr.number} ${pr.title}", fontWeight = FontWeight.SemiBold)
                Mono(pr.branch, maxLines = 1)
                Checks(pr)
                TextButton({ open(pr.url) }) { Text("PR #${pr.number} on GitHub ↗") }
            }
        }
        gh?.updated?.let { Muted("GitHub checked ${ago(it, now)}") }
    }
}

@Composable
private fun Checks(pr: PullRequest) {
    if (pr.checks.isEmpty()) Muted("PR #${pr.number} · no CI checks yet")
    else ButtonRow { pr.checks.forEach { Chip("${it.name}: ${it.state}", ciTone(it.state)) } }
}

/* ------------------------------------------------------------------ main rig */

@Composable
private fun RigPanel(st: ClaudeState, repo: ClaudeRepo, actions: Actions, canAct: Boolean, now: Long) {
    val r = st.rig
    val ctx = LocalContext.current
    Section("Main rig", trailing = {
        when (r?.state) {
            null -> Unit
            "up" -> Chip("up", Tone.GREEN)
            "booting" -> Chip("waking up…", Tone.ORANGE)
            "off" -> Chip("off")
            else -> Chip(r.state, Tone.ORANGE)
        }
    }) {
        if (r == null) {
            Empty("Main rig control is not set up.")
            return@Section
        }
        fun yes(v: Boolean?, a: String, b: String) = when (v) { true -> a; false -> b; null -> "—" }
        KeyValue("Tailscale", yes(r.tailscale, "reachable", "not reachable"))
        KeyValue("Apollo", yes(r.apollo, "running", "not running"))
        if (r.state == "up") KeyValue("Screen", yes(r.locked, "locked", "unlocked"))
        r.uptimeS?.let { u -> KeyValue("Uptime", duration(u + (r.updated?.let { ((now - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0))) }
        KeyValue("Checked", ago(r.updated, now))
        if (r.sessions.isNotEmpty()) {
            Text("Claude on the rig", style = MaterialTheme.typography.labelLarge)
            r.sessions.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.window, fontWeight = FontWeight.SemiBold)
                    if (s.up) Chip("up", Tone.GREEN) else Chip("down", Tone.RED)
                    RcChip(s.rc)
                }
            }
        }
        ButtonRow {
            if (r.state != "up") {
                ActionButton(if (r.wakePending) "Waking…" else "Wake", actions.isBusy("rig:wake"), Kind.PRIMARY, canAct && !r.wakePending) {
                    actions.run("rig:wake", "Magic packet sent. You get \"Main rig is up\" on the phone within 3 minutes.") { repo.rig("wake") }
                }
            } else {
                ConfirmActionButton("Suspend", "Suspend the main rig? Streams and its Claude session pause.", busy = actions.isBusy("rig:suspend"), enabled = canAct) {
                    actions.run("rig:suspend", "Suspending.") { repo.rig("suspend") }
                }
                ConfirmActionButton("Power off", "Power off the main rig? Its Claude session stops.", busy = actions.isBusy("rig:off"), enabled = canAct) {
                    actions.run("rig:off", "Powering off.") { repo.rig("poweroff") }
                }
            }
            r.moonlightUrl?.let { url ->
                ActionButton("Open Moonlight Web ↗") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }
            }
        }
    }
}

/* ------------------------------------------------------------------ storage and snapshots */

@Composable
private fun StoragePanel(st: ClaudeState, repo: ClaudeRepo, actions: Actions, canAct: Boolean, now: Long) {
    val s = st.storage
    Section("Storage", trailing = { s?.let { val p = it.disk.pct; Chip("$p% used", if (p >= 90) Tone.RED else if (p >= 80) Tone.ORANGE else Tone.NEUTRAL) } }) {
        if (s == null) {
            Empty("No storage data yet (updated every 10 minutes).")
            return@Section
        }
        Text("${bytes(s.disk.avail)} free of ${bytes(s.disk.size)}")
        Meter(s.disk.pct / 100f, label = "Disk used")
        s.compression?.let { KeyValue("Compression", "$it of original (zstd:3)") }
        KeyValue("Cargo target", bytes(s.cargoTarget))
        KeyValue("Podman", bytes(s.podman))
        KeyValue("All of ~/.cache", bytes(s.cache))
        KeyValue("Checked", ago(s.updated, now))
        ButtonRow {
            ActionButton("Run cargo-sweep", actions.isBusy("sweep"), enabled = canAct) { actions.run("sweep", "cargo-sweep started.") { repo.sweep() } }
            ActionButton("Prune Podman", actions.isBusy("prune"), enabled = canAct) { actions.run("prune", "Podman prune started.") { repo.prune() } }
        }
    }
}

@Composable
private fun SnapshotsPanel(st: ClaudeState, repo: ClaudeRepo, actions: Actions, canAct: Boolean) {
    val s = st.storage?.snapshots
    var asking by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf("") }
    Section("Snapshots", trailing = { s?.let { Chip("${it.count} total") } }) {
        if (s == null) Empty("No snapshot data yet.")
        else {
            Muted("${bytes(s.exclusiveBytes)} held only by snapshots · hourly, kept 24 h / 7 d / 2 weeks")
            if (s.latest.isEmpty()) Empty("No snapshots yet.")
            s.latest.forEach { x ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${x.number}", fontWeight = FontWeight.SemiBold)
                    Column {
                        Text(x.label)
                        Muted(x.date)
                    }
                }
            }
        }
        ActionButton("Take snapshot now", actions.isBusy("snap"), enabled = canAct) { desc = ""; asking = true }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Take snapshot") },
            text = {
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, placeholder = { Text("e.g. before big refactor") }, singleLine = true)
            },
            confirmButton = {
                Button({
                    asking = false
                    val d = desc.trim().ifEmpty { "from Cockpit Mobile" }
                    actions.run("snap", "Snapshot taken: $d") { repo.snapshot(d) }
                }) { Text("Take snapshot") }
            },
            dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
        )
    }
}

/* ------------------------------------------------------------------ activity */

@Composable
private fun ActivityPanel(st: ClaudeState, now: Long) {
    var all by remember { mutableStateOf(false) }
    val ev = st.activity
    Section("Activity") {
        if (ev.isEmpty()) Empty("No events yet.")
        ev.take(if (all) 60 else 15).forEach { e ->
            val (name, tone) = EVENT_NAMES[e.kind] ?: (e.kind to Tone.NEUTRAL)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(name, tone)
                    Muted(ago(e.time, now))
                }
                if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ev.size > 15) TextButton({ all = !all }) { Text(if (all) "Show less" else "Show more") }
    }
}
