package dev.normal99.cockpit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.normal99.cockpit.BuildConfig
import dev.normal99.cockpit.CockpitApp
import dev.normal99.cockpit.Demo
import dev.normal99.cockpit.claude.Broker
import dev.normal99.cockpit.claude.ClaudeFiles
import dev.normal99.cockpit.claude.ClaudeRepo
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.friendlyMessage
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.watchFile
import dev.normal99.cockpit.cockpit.untrustedCertificate
import dev.normal99.cockpit.data.CockpitPage
import dev.normal99.cockpit.data.Host
import dev.normal99.cockpit.data.HostConnection
import dev.normal99.cockpit.notify.RootRequestWorker
import dev.normal99.cockpit.web.CockpitWebPage
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.launch

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String?) = s?.takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }

@Composable
fun AppRoot(app: CockpitApp, startHost: String?, startTab: String?) {
    val hosts by app.store.hosts.collectAsState()
    val currentId by app.store.current.collectAsState()
    LaunchedEffect(startHost) { startHost?.let { if (app.store.host(it) != null) app.store.select(it) } }

    if (hosts.isEmpty()) {
        Scaffold { pad ->
            Box(Modifier.padding(pad)) {
                HostEditScreen(app.store, null, onSaved = { app.store.select(it.id) }, onDeleted = null,
                    onDemo = if (Demo.available) ({
                        Demo.ensureStarted()
                        app.store.save(Host(name = "demo-box", url = Demo.URL, user = "demo"), "demo")
                    }) else null)
            }
        }
        return
    }
    val host = hosts.firstOrNull { it.id == currentId } ?: hosts.first()
    val conn = remember(host) { app.connections.get(host) }

    // Connected while the app is in front; notifications cover the rest.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    DisposableEffect(conn, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> scope.launch { runCatching { conn.connect() } }
                Lifecycle.Event.ON_STOP -> { app.connections.pauseAll(); RootRequestWorker.checkNow(app) }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val nav = rememberNavController()
    fun opt(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
    NavHost(nav, startDestination = "home") {
        composable("home") { HostHome(app, conn, hosts, startTab ?: "overview", nav) }
        composable("edit?id={id}", listOf(opt("id"))) { entry ->
            val id = entry.arguments?.getString("id")
            val existing = app.store.host(id?.takeIf { it.isNotEmpty() })
            Sub(nav, if (existing == null) "Add host" else "Edit host") {
                Box(Modifier.padding(it)) {
                    HostEditScreen(app.store, existing,
                        onSaved = { h -> app.connections.drop(h.id); app.store.select(h.id); nav.popBackStack() },
                        onDeleted = { app.connections.drop(id ?: ""); nav.popBackStack() })
                }
            }
        }
        composable("settings") { Sub(nav, "Settings") { SettingsScreen(app, it, nav) } }
        composable("services") { HostSub(nav, conn, "Services") { a, p -> ServicesScreen(conn, a, p) { u -> nav.navigate("logs?unit=${enc(u)}") } } }
        composable("logs?unit={unit}", listOf(opt("unit"))) { e -> HostSub(nav, conn, "Logs") { _, p -> LogsScreen(conn, dec(e.arguments?.getString("unit")), p) } }
        composable("containers") { HostSub(nav, conn, "Containers") { a, p -> ContainersScreen(conn, a, p) } }
        composable("updates") {
            val pages by conn.pages.collectAsState()
            val updates = pages.firstOrNull { it.pkg == "packagekit" }
            HostSub(nav, conn, "Software updates") { _, p ->
                UpdatesScreen(conn, p, updates?.let { u -> { nav.navigate("web?path=${enc(u.path)}&title=${enc(u.label)}") } })
            }
        }
        composable("run") { HostSub(nav, conn, "Run a command") { _, p -> RunCommandScreen(conn, p) } }
        composable("pages") { HostSub(nav, conn, "Cockpit pages") { _, p -> PagesList(conn, p) { pg -> nav.navigate("web?path=${enc(pg.path)}&title=${enc(pg.label)}") } } }
        composable("web?path={path}&title={title}", listOf(opt("path"), opt("title"))) { e ->
            var title by remember { mutableStateOf(dec(e.arguments?.getString("title")) ?: "Cockpit") }
            val path = dec(e.arguments?.getString("path")) ?: "cockpit/@localhost/system/index.html"
            HostSub(nav, conn, title) { _, p ->
                Box(Modifier.padding(p)) { CockpitWebPage(conn, path) { title = it.substringBefore(" - ") } }
            }
        }
    }
}

/* ------------------------------------------------------------------ home with tabs */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostHome(app: CockpitApp, conn: HostConnection, hosts: List<Host>, initialTab: String, nav: NavHostController) {
    val state by conn.state.collectAsState()
    val admin by conn.admin.collectAsState()
    val manifests by conn.manifests.collectAsState()
    val hasClaude = ClaudeRepo.isInstalled(manifests)
    var tab by rememberSaveable(conn.host.id) { mutableStateOf(initialTab) }
    var hostMenu by remember { mutableStateOf(false) }
    var adminDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar) { adminDialog = true }
    val pending = rememberPendingCount(conn, hasClaude)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.clickable { hostMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(state)
                        Text(conn.host.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, "Switch host")
                    }
                    DropdownMenu(hostMenu, { hostMenu = false }) {
                        hosts.forEach { h ->
                            DropdownMenuItem(text = { Text(h.name) }, onClick = { hostMenu = false; app.store.select(h.id) },
                                leadingIcon = { Icon(Icons.Default.Dns, null) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Add host") }, onClick = { hostMenu = false; nav.navigate("edit?id=") },
                            leadingIcon = { Icon(Icons.Default.Add, null) })
                        DropdownMenuItem(text = { Text("Edit ${conn.host.name}") }, onClick = { hostMenu = false; nav.navigate("edit?id=${conn.host.id}") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) })
                    }
                },
                actions = {
                    IconButton({ adminDialog = true }) {
                        val on = admin != null && admin != "none" && admin != "init"
                        Icon(if (on) Icons.Default.AdminPanelSettings else Icons.Default.RemoveModerator,
                            if (on) "Administrative access" else "Limited access",
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == "overview", { tab = "overview" }, { Icon(Icons.Default.Dashboard, null) }, label = { Text("Overview") })
                if (hasClaude) {
                    NavigationBarItem(tab == "claude", { tab = "claude" }, {
                        BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) { Icon(Icons.Default.SmartToy, null) }
                    }, label = { Text("Claude") })
                }
                NavigationBarItem(tab == "system", { tab = "system" }, { Icon(Icons.Default.Dns, null) }, label = { Text("System") })
                NavigationBarItem(tab == "pages", { tab = "pages" }, { Icon(Icons.Default.Apps, null) }, label = { Text("Pages") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when (val st = state) {
            is CockpitSession.State.Failed -> ConnectionProblem(conn, st.error, pad, onEdit = { nav.navigate("edit?id=${conn.host.id}") })
            CockpitSession.State.Ready -> when (if (tab == "claude" && !hasClaude && manifests.isNotEmpty()) "overview" else tab) {
                "claude" -> ClaudeScreen(conn, actions, pad) { adminDialog = true }
                "system" -> SystemHub(pad) { route ->
                    when (route) {
                        "terminal" -> nav.navigate("web?path=${enc("cockpit/@localhost/system/terminal.html")}&title=${enc("Terminal")}")
                        "logs" -> nav.navigate("logs?unit=")
                        else -> nav.navigate(route)
                    }
                }
                "pages" -> PagesList(conn, pad) { pg -> nav.navigate("web?path=${enc(pg.path)}&title=${enc(pg.label)}") }
                else -> OverviewScreen(conn, actions, pad) { nav.navigate("services") }
            }
            else -> Box(Modifier.padding(pad)) { CenteredLoading("Connecting to ${conn.host.name}…") }
        }
    }

    if (adminDialog) AdminDialog(conn, actions) { adminDialog = false }
}

/** The number of root requests waiting, for the Claude tab's badge. */
@Composable
private fun rememberPendingCount(conn: HostConnection, enabled: Boolean): Int {
    val state by conn.state.collectAsState()
    var n by remember { mutableStateOf(0) }
    LaunchedEffect(conn.session, enabled, state) {
        if (!enabled || state != CockpitSession.State.Ready) return@LaunchedEffect
        runCatching {
            conn.session.watchFile(ClaudeFiles.BROKER).collect { text ->
                n = text?.let { parseObject(it) }?.let { Broker.parse(it).pending.size } ?: 0
            }
        }
    }
    return n
}

@Composable
private fun StatusDot(state: CockpitSession.State) {
    val color = when (state) {
        CockpitSession.State.Ready -> Color(0xFF3E8635)
        CockpitSession.State.Connecting -> Color(0xFFF0AB00)
        is CockpitSession.State.Failed -> Color(0xFFC9190B)
        CockpitSession.State.Idle -> Color.Gray
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
}

@Composable
private fun ConnectionProblem(conn: HostConnection, error: Throwable, pad: PaddingValues, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cert = error.untrustedCertificate()
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Banner("Can't connect to ${conn.host.name}", error.friendlyMessage(), Tone.RED)
        Muted(conn.host.url)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cert != null) Button(onEdit) { Text("Review certificate") }
            else Button({ scope.launch { runCatching { conn.connect() } } }) { Text("Try again") }
            TextButton(onEdit) { Text("Edit host") }
        }
    }
}

@Composable
private fun AdminDialog(conn: HostConnection, actions: Actions, onDismiss: () -> Unit) {
    val admin by conn.admin.collectAsState()
    val on = admin != null && admin != "none" && admin != "init"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (on) "Administrative access" else "Limited access") },
        text = {
            Text(if (on) "You can approve root requests, manage services and run commands as root. " +
                "Turn it off to browse without being able to change anything by accident."
            else "Cockpit is running with your normal user's rights. Turning on administrative access uses your saved password with sudo, " +
                "the same as \"Limited access\" → \"Administrative access\" in Cockpit's top bar.")
        },
        confirmButton = {
            Button({
                onDismiss()
                actions.run("admin", if (on) "Switched to limited access." else "Administrative access is on.") { conn.setAdmin(!on); null }
            }) { Text(if (on) "Switch to limited" else "Turn on admin access") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/* ------------------------------------------------------------------ pages list (every Cockpit page, mods included) */

@Composable
fun PagesList(conn: HostConnection, pad: PaddingValues, open: (CockpitPage) -> Unit) {
    val pages by conn.pages.collectAsState()
    val known = setOf("system", "storaged", "networkmanager", "packagekit", "podman", "machines", "files", "selinux", "users", "kdump", "sosreport", "apps", "metrics", "shell", "updates")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        item { Muted("Pages run inside the app with your login. Add-on and custom pages (mods) show up here automatically.", Modifier.padding(16.dp)) }
        if (pages.isEmpty()) item { CenteredLoading("Reading Cockpit's page list…") }
        items(pages) { p ->
            ListItem(
                headlineContent = { Text(p.label) },
                supportingContent = { Text("${p.pkg}/${p.name}" + if (p.section == "tools") " · tool" else "") },
                trailingContent = { if (p.pkg !in known) Chip("mod", Tone.PURPLE) },
                modifier = Modifier.clickable { open(p) },
            )
        }
    }
}

/* ------------------------------------------------------------------ settings */

@Composable
private fun SettingsScreen(app: CockpitApp, pad: PaddingValues, nav: NavHostController) {
    val hosts by app.store.hosts.collectAsState()
    var lock by remember { mutableStateOf(app.store.appLock) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var canNotify by remember { mutableStateOf(RootRequestWorker.canNotify(ctx)) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item { Text("Hosts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)) }
        items(hosts) { h ->
            ListItem(headlineContent = { Text(h.name) }, supportingContent = { Text("${h.user} @ ${h.url}") },
                modifier = Modifier.clickable { nav.navigate("edit?id=${h.id}") })
        }
        item { ListItem(headlineContent = { Text("Add host") }, leadingContent = { Icon(Icons.Default.Add, null) }, modifier = Modifier.clickable { nav.navigate("edit?id=") }) }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text("Lock the app") },
                supportingContent = { Text("Ask for fingerprint, face or screen lock when opening the app") },
                trailingContent = { Switch(lock, { lock = it; app.store.appLock = it }) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text(if (canNotify) "Allowed. Hosts are checked every 15 minutes and when you leave the app." else "Not allowed yet") },
                trailingContent = {
                    if (!canNotify && Build.VERSION.SDK_INT >= 33) Button({ askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                    else TextButton({ RootRequestWorker.checkNow(app) }) { Text("Check now") }
                },
            )
        }
        item { ListItem(headlineContent = { Text("Version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) }) }
    }
}

/* ------------------------------------------------------------------ sub screens with a back arrow */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sub(nav: NavHostController, title: String, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { content(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSub(nav: NavHostController, conn: HostConnection, title: String, content: @Composable (Actions, PaddingValues) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var adminDialog by remember { mutableStateOf(false) }
    val actions = rememberActions(snackbar) { adminDialog = true }
    val state by conn.state.collectAsState()
    val current by nav.currentBackStackEntryAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { StatusDot(state); Box(Modifier.size(16.dp)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (current != null) {
            when (val st = state) {
                CockpitSession.State.Ready -> content(actions, pad)
                is CockpitSession.State.Failed -> ConnectionProblem(conn, st.error, pad) { nav.navigate("edit?id=${conn.host.id}") }
                else -> Box(Modifier.padding(pad)) { CenteredLoading("Connecting…") }
            }
        }
    }
    if (adminDialog) AdminDialog(conn, actions) { adminDialog = false }
}
