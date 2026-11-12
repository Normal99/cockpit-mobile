package dev.normal99.cockpit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Superuser
import dev.normal99.cockpit.cockpit.friendlyMessage
import dev.normal99.cockpit.cockpit.lines
import dev.normal99.cockpit.cockpit.spawn
import dev.normal99.cockpit.cockpit.spawnResult
import dev.normal99.cockpit.cockpit.spawnStream
import dev.normal99.cockpit.data.HostConnection
import dev.normal99.cockpit.system.Container
import dev.normal99.cockpit.system.JournalEntry
import dev.normal99.cockpit.system.PRIORITY_NAMES
import dev.normal99.cockpit.system.PackageUpdate
import dev.normal99.cockpit.system.ServiceUnit
import dev.normal99.cockpit.system.SystemCommands
import dev.normal99.cockpit.system.parseCheckUpdate
import dev.normal99.cockpit.system.parseContainers
import dev.normal99.cockpit.system.parseJournalLine
import dev.normal99.cockpit.system.parseUnitFiles
import dev.normal99.cockpit.system.parseUnits
import kotlinx.coroutines.launch

private fun pad(p: PaddingValues) = PaddingValues(start = 12.dp, end = 12.dp, top = p.calculateTopPadding() + 8.dp, bottom = p.calculateBottomPadding() + 16.dp)

/* ------------------------------------------------------------------ System hub */

@Composable
fun SystemHub(contentPadding: PaddingValues, open: (String) -> Unit) {
    data class Entry(val route: String, val title: String, val text: String, val icon: ImageVector)
    val entries = listOf(
        Entry("services", "Services", "Start, stop, restart and enable systemd services", Icons.Default.Settings),
        Entry("logs", "Logs", "The journal, live, filtered by priority, service or text", Icons.AutoMirrored.Filled.ListAlt),
        Entry("containers", "Containers", "Podman containers (yours and system)", Icons.Default.Inventory2),
        Entry("updates", "Software updates", "Check what dnf would update", Icons.Default.SystemUpdateAlt),
        Entry("terminal", "Terminal", "Cockpit's own terminal", Icons.Default.Terminal),
        Entry("run", "Run a command", "One command, its output, optionally as root", Icons.AutoMirrored.Filled.ArrowForward),
        Entry("pages", "All Cockpit pages", "Every page Cockpit and your mods provide", Icons.Default.Apps),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad(contentPadding)) {
        items(entries) { e ->
            ListItem(
                headlineContent = { Text(e.title) },
                supportingContent = { Text(e.text) },
                leadingContent = { Icon(e.icon, null) },
                modifier = Modifier.clickable { open(e.route) },
            )
        }
    }
}

/* ------------------------------------------------------------------ Services */

@Composable
fun ServicesScreen(conn: HostConnection, actions: Actions, contentPadding: PaddingValues, openLogs: (String) -> Unit) {
    val session = conn.session
    val state by session.state.collectAsState()
    var units by remember { mutableStateOf<List<ServiceUnit>?>(null) }
    var enabled by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var filter by remember { mutableStateOf("") }
    var which by remember { mutableStateOf("active") }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ServiceUnit?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            units = parseUnits(session.spawn(SystemCommands.UNITS))
            enabled = parseUnitFiles(session.spawnResult(SystemCommands.UNIT_FILES).output)
            error = null
        } catch (e: Throwable) {
            error = e.friendlyMessage()
        }
    }
    LaunchedEffect(session, state) { if (state == CockpitSession.State.Ready) load() }

    val shown = units.orEmpty().filter { u ->
        (filter.isBlank() || u.name.contains(filter, true) || u.description.contains(filter, true)) &&
            when (which) { "active" -> u.running; "failed" -> u.failed; "inactive" -> !u.running && !u.failed; else -> true }
    }.sortedWith(compareBy({ !it.failed }, { it.name }))

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad(contentPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Filter services") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val failedCount = units.orEmpty().count { it.failed }
                    listOf("active" to "Running", "failed" to "Failed ($failedCount)", "inactive" to "Inactive", "all" to "All").forEach { (k, l) ->
                        FilterChip(which == k, { which = k }, label = { Text(l) })
                    }
                }
            }
            error?.let { item { Banner("Couldn't list services", it, Tone.RED) } }
            if (units == null && error == null) item { CenteredLoading() }
            items(shown, key = { it.name }) { u ->
                ListItem(
                    headlineContent = { Text(u.name.removeSuffix(".service"), maxLines = 1) },
                    supportingContent = { Text(u.description, maxLines = 1) },
                    trailingContent = {
                        Chip(if (u.failed) "failed" else u.sub, when { u.failed -> Tone.RED; u.running -> Tone.GREEN; else -> Tone.NEUTRAL })
                    },
                    modifier = Modifier.clickable { selected = u },
                )
            }
        }
    }

    selected?.let { u ->
        ServiceSheet(conn, u, enabled[u.name], actions, onDismiss = { selected = null }, openLogs = openLogs, reload = { scope.launch { load() } })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ServiceSheet(conn: HostConnection, u: ServiceUnit, fileState: String?, actions: Actions, onDismiss: () -> Unit, openLogs: (String) -> Unit, reload: () -> Unit) {
    val session = conn.session
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(u.name) {
        status = runCatching { session.spawnResult(listOf("systemctl", "status", "--no-pager", "--lines=15", "--full", u.name), Superuser.TRY).output }
            .getOrElse { it.friendlyMessage() }
    }
    fun act(verb: String, ok: String) = actions.run("${u.name}:$verb", ok) {
        session.spawn(listOf("systemctl", verb, u.name), Superuser.REQUIRE)
        reload()
        null
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(u.name, style = MaterialTheme.typography.titleLarge)
            Text(u.description)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("${u.active} (${u.sub})", when { u.failed -> Tone.RED; u.running -> Tone.GREEN; else -> Tone.NEUTRAL })
                fileState?.let { Chip(it, if (it == "enabled") Tone.BLUE else Tone.NEUTRAL) }
            }
            ButtonRow {
                if (!u.running) ActionButton("Start", actions.isBusy("${u.name}:start"), Kind.PRIMARY) { act("start", "Started ${u.name}") }
                if (u.running) ConfirmActionButton("Stop", "Stop ${u.name}?", busy = actions.isBusy("${u.name}:stop")) { act("stop", "Stopped ${u.name}") }
                ConfirmActionButton("Restart", "Restart ${u.name}?", busy = actions.isBusy("${u.name}:restart"), confirmKind = Kind.PRIMARY) { act("restart", "Restarted ${u.name}") }
                when (fileState) {
                    "enabled" -> ConfirmActionButton("Disable", "Stop ${u.name} from starting at boot?", busy = actions.isBusy("${u.name}:disable")) { act("disable", "Disabled ${u.name}") }
                    "disabled" -> ActionButton("Enable", actions.isBusy("${u.name}:enable")) { act("enable", "Enabled ${u.name}") }
                }
                ActionButton("Logs", kind = Kind.TEXT) { onDismiss(); openLogs(u.name) }
            }
            SelectionContainer {
                Mono(status ?: "Loading status…", Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

/* ------------------------------------------------------------------ Logs */

@Composable
fun LogsScreen(conn: HostConnection, initialUnit: String?, contentPadding: PaddingValues) {
    val session = conn.session
    val state by session.state.collectAsState()
    var priority by remember { mutableIntStateOf(if (initialUnit == null) 4 else 7) }
    var unit by remember { mutableStateOf(initialUnit) }
    var grep by remember { mutableStateOf("") }
    var appliedGrep by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val entries = remember { mutableStateListOf<JournalEntry>() }
    var expanded by remember { mutableStateOf<JournalEntry?>(null) }
    val list = rememberLazyListState()

    LaunchedEffect(session, state, priority, unit, appliedGrep, follow) {
        if (state != CockpitSession.State.Ready) return@LaunchedEffect
        entries.clear()
        error = null
        val args = SystemCommands.journal(200, priority, unit, follow, appliedGrep)
        runCatching {
            session.spawnStream(args, Superuser.TRY).lines().collect { line ->
                parseJournalLine(line)?.let {
                    entries.add(0, it) // newest first
                    if (entries.size > 2000) entries.removeAt(entries.size - 1)
                }
            }
        }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.friendlyMessage() }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = pad(contentPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3 to "Errors", 4 to "Warnings", 5 to "Notices", 7 to "Everything").forEach { (p, l) ->
                        FilterChip(priority == p, { priority = p }, label = { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(grep, { grep = it }, Modifier.weight(1f), placeholder = { Text("Search messages") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { appliedGrep = grep }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                        Switch(follow, { follow = it })
                        Muted("Live")
                    }
                }
                unit?.let { u -> FilterChip(true, { unit = null }, label = { Text("$u  ✕") }) }
                error?.let { Banner("Couldn't read the journal", it, Tone.RED) }
                if (entries.isEmpty() && error == null) Muted("No entries yet for this filter…")
            }
        }
        items(entries) { e ->
            val tone = when { e.priority <= 3 -> Tone.RED; e.priority == 4 -> Tone.ORANGE; else -> Tone.NEUTRAL }
            Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == e) null else e }.padding(vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted(shortTime(e.timeMs), Modifier.width(78.dp))
                    Text(e.identifier, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                    if (e.priority <= 4) Chip(PRIORITY_NAMES.getOrElse(e.priority) { "?" }, tone)
                }
                if (expanded == e) {
                    SelectionContainer { Mono(e.message) }
                    Muted(listOfNotNull(fullTime(e.timeMs), e.unit, e.pid?.let { "PID $it" }).joinToString(" · "))
                    e.unit?.let { u -> ActionButton("Only $u", kind = Kind.TEXT) { unit = u; priority = 7 } }
                } else {
                    Text(e.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            HorizontalDivider()
        }
    }
}

/* ------------------------------------------------------------------ Containers */

@Composable
fun ContainersScreen(conn: HostConnection, actions: Actions, contentPadding: PaddingValues) {
    val session = conn.session
    val state by session.state.collectAsState()
    var containers by remember { mutableStateOf<List<Container>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var logsOf by remember { mutableStateOf<Container?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val mine = session.spawnResult(SystemCommands.CONTAINERS)
            if (mine.problem == "not-found" || mine.message?.contains("not found") == true) throw IllegalStateException("Podman is not installed on this host.")
            val user = parseContainers(mine.orThrow(), system = false)
            val sys = if (conn.hasAdmin) runCatching { parseContainers(session.spawn(SystemCommands.CONTAINERS, Superuser.REQUIRE), system = true) }.getOrDefault(emptyList()) else emptyList()
            containers = user + sys
            error = null
        } catch (e: Throwable) {
            error = e.friendlyMessage()
        }
    }
    LaunchedEffect(session, state) { if (state == CockpitSession.State.Ready) load() }

    fun podman(c: Container, vararg args: String, ok: String) = actions.run("${c.id}:${args[0]}", ok) {
        session.spawn(listOf("podman", *args, c.id), if (c.system) Superuser.REQUIRE else Superuser.NONE)
        load()
        null
    }

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item { Banner("Couldn't list containers", it, Tone.RED) } }
            if (containers == null && error == null) item { CenteredLoading() }
            if (containers?.isEmpty() == true) item { Empty("No containers.") }
            if (!conn.hasAdmin) item { Muted("System (root) containers are listed when admin access is on.") }
            items(containers.orEmpty(), key = { it.id + it.system }) { c ->
                Section(c.name, trailing = {
                    if (c.system) Chip("system", Tone.PURPLE)
                    Chip(c.state, if (c.running) Tone.GREEN else Tone.NEUTRAL)
                }) {
                    Mono(c.image, maxLines = 1)
                    Muted(listOf(c.status, c.ports).filter { it.isNotBlank() }.joinToString(" · "))
                    ButtonRow {
                        if (!c.running) ActionButton("Start", actions.isBusy("${c.id}:start"), Kind.PRIMARY) { podman(c, "start", ok = "Started ${c.name}") }
                        if (c.running) ConfirmActionButton("Stop", "Stop ${c.name}?", busy = actions.isBusy("${c.id}:stop")) { podman(c, "stop", ok = "Stopped ${c.name}") }
                        if (c.running) ConfirmActionButton("Restart", "Restart ${c.name}?", busy = actions.isBusy("${c.id}:restart"), confirmKind = Kind.PRIMARY) { podman(c, "restart", ok = "Restarted ${c.name}") }
                        ActionButton("Logs", kind = Kind.TEXT) { logsOf = c }
                    }
                }
            }
        }
    }

    logsOf?.let { c ->
        var text by remember(c.id) { mutableStateOf("Loading…") }
        LaunchedEffect(c.id) {
            text = runCatching {
                val r = session.spawnResult(listOf("podman", "logs", "--tail", "300", c.id), if (c.system) Superuser.REQUIRE else Superuser.NONE)
                // podman writes the container's stderr to ours, which Cockpit hands back as the message
                (r.output + (r.message ?: "")).ifBlank { "(no output)" }
            }.getOrElse { it.friendlyMessage() }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { logsOf = null },
            title = { Text("${c.name} logs") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { SelectionContainer { Mono(text, Modifier.horizontalScroll(rememberScrollState())) } }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ logsOf = null }) { Text("Close") } },
        )
    }
}

/* ------------------------------------------------------------------ Updates */

@Composable
fun UpdatesScreen(conn: HostConnection, contentPadding: PaddingValues, openUpdatesPage: (() -> Unit)?) {
    val session = conn.session
    val state by session.state.collectAsState()
    var updates by remember { mutableStateOf<List<PackageUpdate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun check() {
        checking = true
        try {
            val r = session.spawnResult(SystemCommands.CHECK_UPDATE, Superuser.TRY, timeoutMs = 600_000)
            when (r.exitStatus) {
                0 -> { updates = emptyList(); error = null }
                100 -> { updates = parseCheckUpdate(r.output); error = null }
                else -> error = r.problem ?: r.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "dnf failed (exit ${r.exitStatus})"
            }
        } catch (e: Throwable) {
            error = e.friendlyMessage()
        } finally {
            checking = false
        }
    }
    LaunchedEffect(session, state) { if (state == CockpitSession.State.Ready && updates == null) check() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Section("Software updates", trailing = { updates?.let { Chip(if (it.isEmpty()) "up to date" else "${it.size} available", if (it.isEmpty()) Tone.GREEN else Tone.BLUE) } }) {
                if (checking) CenteredLoading("Checking with dnf… (can take a minute)")
                error?.let { Banner("Couldn't check for updates", it, Tone.RED) }
                ButtonRow {
                    ActionButton("Check again", checking) { scope.launch { check() } }
                    if (openUpdatesPage != null) ActionButton("Install in Cockpit's Updates page", kind = Kind.PRIMARY, onClick = openUpdatesPage)
                }
                Muted("Installing goes through Cockpit's own Software updates page (PackageKit), the same as on the web.")
            }
        }
        val kernel = updates.orEmpty().filter { it.name.startsWith("kernel") }
        if (kernel.isNotEmpty()) item { Banner("Kernel update", "A restart is needed after installing ${kernel.first().version}.", Tone.BLUE) }
        items(updates.orEmpty()) { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(u.name, fontWeight = FontWeight.SemiBold)
                    Muted("${u.version} · ${u.arch}")
                }
                Muted(u.repo)
            }
            HorizontalDivider()
        }
    }
}

/* ------------------------------------------------------------------ Run a command */

@Composable
fun RunCommandScreen(conn: HostConnection, contentPadding: PaddingValues) {
    val session = conn.session
    var cmd by remember { mutableStateOf("") }
    var asRoot by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Triple<String, Boolean, String>>() }
    val scope = rememberCoroutineScope()
    val admin by conn.admin.collectAsState()

    fun go() {
        val c = cmd.trim()
        if (c.isEmpty() || running) return
        running = true
        scope.launch {
            val out = runCatching {
                val r = session.spawnResult(listOf("bash", "-lc", c), if (asRoot) Superuser.REQUIRE else Superuser.NONE, timeoutMs = 300_000)
                if (r.problem != null) "⚠ ${dev.normal99.cockpit.cockpit.CockpitException(r.problem, r.message ?: r.problem).friendly()}"
                else buildString {
                    append(r.output)
                    r.message?.takeIf { it.isNotBlank() }?.let { append(it) }
                    if ((r.exitStatus ?: 0) != 0) append("\n[exit ${r.exitStatus}]")
                }.ifBlank { "(no output)" }
            }.getOrElse { "⚠ " + it.friendlyMessage() }
            history.add(0, Triple(c, asRoot, out))
            running = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                        autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(asRoot, { asRoot = it }, enabled = admin != "none")
                    Text("  Run as root (admin access)", Modifier.weight(1f))
                    ActionButton("Run", running, Kind.PRIMARY, cmd.isNotBlank()) { go() }
                }
                Muted("Runs with bash -lc as ${if (asRoot) "root" else conn.host.user}. For interactive programs use the Terminal.")
            }
        }
        items(history) { (c, root, out) ->
            Section(if (root) "# $c" else "$ $c") {
                SelectionContainer { Mono(out, Modifier.horizontalScroll(rememberScrollState())) }
                ActionButton("Run again", kind = Kind.TEXT) { cmd = c; asRoot = root; go() }
            }
        }
    }
}
