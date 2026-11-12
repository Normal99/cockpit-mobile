package dev.normal99.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Superuser
import dev.normal99.cockpit.cockpit.friendlyMessage
import dev.normal99.cockpit.cockpit.spawn
import dev.normal99.cockpit.cockpit.spawnResult
import dev.normal99.cockpit.cockpit.spawnStream
import dev.normal99.cockpit.data.HostConnection
import dev.normal99.cockpit.system.Filesystem
import dev.normal99.cockpit.system.Metrics
import dev.normal99.cockpit.system.ProcSample
import dev.normal99.cockpit.system.SystemCommands
import dev.normal99.cockpit.system.SystemInfo
import dev.normal99.cockpit.system.metricsBetween
import dev.normal99.cockpit.system.parseDf
import dev.normal99.cockpit.system.parseProcSample
import dev.normal99.cockpit.system.parseSystemInfo
import dev.normal99.cockpit.system.parseUnits
import kotlinx.coroutines.delay

private const val HISTORY = 60

@Composable
fun OverviewScreen(conn: HostConnection, actions: Actions, contentPadding: PaddingValues, onOpenServices: () -> Unit) {
    val session = conn.session
    val state by session.state.collectAsState()
    var info by remember { mutableStateOf<SystemInfo?>(null) }
    var metrics by remember { mutableStateOf<Metrics?>(null) }
    var disks by remember { mutableStateOf<List<Filesystem>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cpu = remember { mutableStateListOf<Float>() }
    val mem = remember { mutableStateListOf<Float>() }
    val admin by conn.admin.collectAsState()

    LaunchedEffect(session, state) {
        if (state != CockpitSession.State.Ready) return@LaunchedEffect
        runCatching { info = parseSystemInfo(session.spawn(SystemCommands.INFO)) }.onFailure { error = it.friendlyMessage() }
        runCatching { failed = parseUnits(session.spawn(SystemCommands.UNITS)).filter { it.failed }.map { it.name } }
    }
    LaunchedEffect(session, state) {
        if (state != CockpitSession.State.Ready) return@LaunchedEffect
        while (true) {
            runCatching { disks = parseDf(session.spawnResult(SystemCommands.DF).output) }
            delay(60_000)
        }
    }
    // Live counters from one long-running sampler; stops when the screen leaves.
    LaunchedEffect(session, state) {
        if (state != CockpitSession.State.Ready) return@LaunchedEffect
        var last: ProcSample? = null
        val buf = StringBuilder()
        runCatching {
            session.spawnStream(SystemCommands.sampler(2)).collect { chunk ->
                buf.append(chunk)
                var end = buf.indexOf("@@\n")
                while (end >= 0) {
                    val sample = parseProcSample(buf.substring(0, end), System.currentTimeMillis())
                    buf.delete(0, end + 3)
                    if (sample != null) {
                        last?.let { a ->
                            val m = metricsBetween(a, sample)
                            metrics = m
                            cpu.add(m.cpuPct.toFloat() / 100f); if (cpu.size > HISTORY) cpu.removeAt(0)
                            mem.add((m.memPct / 100).toFloat()); if (mem.size > HISTORY) mem.removeAt(0)
                        }
                        last = sample
                    }
                    end = buf.indexOf("@@\n")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { Banner("Couldn't read system information", it, Tone.RED) } }
        item {
            Section(info?.hostname ?: conn.host.name, trailing = { metrics?.let { Chip("up ${duration(it.uptimeS)}", Tone.GREEN) } }) {
                val i = info
                if (i == null) CenteredLoading() else {
                    KeyValue("System", i.os)
                    KeyValue("Kernel", i.kernel)
                    KeyValue("CPU", "${i.cpuModel.trim()} · ${i.cores} cores")
                    KeyValue("Access", when (admin) { null -> "…"; "none" -> "Limited"; "init" -> "Starting admin…"; else -> "Administrative" })
                }
            }
        }
        failed?.takeIf { it.isNotEmpty() }?.let { units ->
            item {
                Banner("${units.size} failed service${if (units.size > 1) "s" else ""}", units.take(5).joinToString(", ") + if (units.size > 5) "…" else "", Tone.RED) {
                    ActionButton("Show services", kind = Kind.TEXT, onClick = onOpenServices)
                }
            }
        }
        item {
            Section("Usage", trailing = { if (metrics == null) Muted("sampling…") }) {
                val m = metrics
                Usage("CPU", m?.let { "%.0f%%".format(it.cpuPct) } ?: "—", cpu, MaterialTheme.colorScheme.primary)
                Muted(m?.let { "Load ${"%.2f".format(it.load.first)} · ${"%.2f".format(it.load.second)} · ${"%.2f".format(it.load.third)}" } ?: "")
                Usage("Memory", m?.let { "${bytes(it.memUsed)} of ${bytes(it.memTotal)}" } ?: "—", mem, MaterialTheme.colorScheme.tertiary)
                if (m != null && m.swapTotal > 0) Muted("Swap ${bytes(m.swapUsed)} of ${bytes(m.swapTotal)}")
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Muted("Network in"); Text(m?.let { rate(it.rxBps) } ?: "—", fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f)) { Muted("Network out"); Text(m?.let { rate(it.txBps) } ?: "—", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        item {
            Section("Disks") {
                if (disks.isEmpty()) Empty("…")
                disks.forEach { d ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(d.target, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Muted("${bytes(d.avail)} free of ${bytes(d.size)}")
                        }
                        Meter(d.pct / 100f, label = "${d.target} ${d.pct}% used")
                        Muted("${d.source} · ${d.fstype}")
                    }
                }
            }
        }
        item {
            Section("Power") {
                Muted("Restarting drops this connection; the app reconnects when the host is back.")
                ButtonRow {
                    ConfirmActionButton("Restart", "Restart ${info?.hostname ?: conn.host.name} now? Everything running on it stops.",
                        busy = actions.isBusy("reboot"), enabled = admin != "none") {
                        actions.run("reboot", "Restarting…") { session.spawn(listOf("shutdown", "--reboot", "now"), Superuser.REQUIRE); null }
                    }
                    ConfirmActionButton("Shut down", "Shut down ${info?.hostname ?: conn.host.name}? You'll need physical access (or Wake-on-LAN) to start it again.",
                        busy = actions.isBusy("poweroff"), enabled = admin != "none") {
                        actions.run("poweroff", "Shutting down…") { session.spawn(listOf("shutdown", "--poweroff", "now"), Superuser.REQUIRE); null }
                    }
                }
            }
        }
    }
}

@Composable
private fun Usage(label: String, value: String, history: List<Float>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold)
        }
        Sparkline(history, color, Modifier.fillMaxWidth().height(48.dp))
    }
}

/** A small area chart of the last two minutes (0..1 values). */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.padding(vertical = 2.dp)) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        if (values.size < 2) return@Canvas
        val step = size.width / (HISTORY - 1)
        val x0 = size.width - (values.size - 1) * step
        val line = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(x0 + i * step, size.height * (1 - v.coerceIn(0f, 1f)))
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(x0, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.18f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
    }
}
