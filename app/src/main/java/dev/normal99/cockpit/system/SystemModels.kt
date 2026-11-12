package dev.normal99.cockpit.system

import dev.normal99.cockpit.cockpit.arr
import dev.normal99.cockpit.cockpit.json
import dev.normal99.cockpit.cockpit.long
import dev.normal99.cockpit.cockpit.obj
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.prim
import dev.normal99.cockpit.cockpit.str
import dev.normal99.cockpit.cockpit.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull

/* ---------------------------------------------------------------- overview */

data class SystemInfo(
    val hostname: String,
    val os: String,
    val kernel: String,
    val cpuModel: String,
    val cores: Int,
    val bootId: String,
)

/** Output of [SystemCommands.INFO]: one value per line. */
fun parseSystemInfo(out: String): SystemInfo {
    val l = out.lines().map { it.trim() }
    return SystemInfo(
        hostname = l.getOrElse(0) { "" },
        os = l.getOrElse(1) { "" },
        kernel = l.getOrElse(2) { "" },
        cpuModel = l.getOrElse(3) { "" },
        cores = l.getOrNull(4)?.toIntOrNull() ?: 0,
        bootId = l.getOrElse(5) { "" },
    )
}

/** Raw counters from one sample of /proc; rates come from two consecutive samples. */
data class ProcSample(
    val cpuBusy: Long,
    val cpuTotal: Long,
    val memTotal: Long,
    val memAvailable: Long,
    val swapTotal: Long,
    val swapFree: Long,
    val load1: Double,
    val load5: Double,
    val load15: Double,
    val uptimeS: Double,
    val netRx: Long,
    val netTx: Long,
    val atMs: Long,
)

data class Metrics(
    val cpuPct: Double,
    val memUsed: Long,
    val memTotal: Long,
    val swapUsed: Long,
    val swapTotal: Long,
    val load: Triple<Double, Double, Double>,
    val uptimeS: Double,
    val rxBps: Double,
    val txBps: Double,
) {
    val memPct get() = if (memTotal > 0) memUsed * 100.0 / memTotal else 0.0
}

/**
 * Parses one block of "cat /proc/stat /proc/meminfo /proc/loadavg /proc/uptime /proc/net/dev".
 * Lines are told apart by their shape, so the order of files doesn't matter.
 */
fun parseProcSample(block: String, atMs: Long): ProcSample? {
    var busy = -1L
    var total = 0L
    val mem = HashMap<String, Long>()
    var load: List<Double>? = null
    var uptime = 0.0
    var rx = 0L
    var tx = 0L
    for (line in block.lineSequence()) {
        val t = line.trim()
        when {
            t.startsWith("cpu ") -> {
                val v = t.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                // user nice system idle iowait irq softirq steal (guest is already counted in user)
                val f = v.take(8)
                total = f.sum()
                busy = total - f.getOrElse(3) { 0 } - f.getOrElse(4) { 0 }
            }
            t.matches(Regex("^[A-Za-z_()]+:\\s+\\d+( kB)?$")) -> {
                val (k, rest) = t.split(":", limit = 2)
                mem[k] = (rest.trim().split(" ")[0].toLongOrNull() ?: 0) * if (rest.trim().endsWith("kB")) 1024 else 1
            }
            t.matches(Regex("^[\\d.]+ [\\d.]+ [\\d.]+ \\d+/\\d+ \\d+$")) -> load = t.split(" ").take(3).map { it.toDouble() }
            t.matches(Regex("^[\\d.]+ [\\d.]+$")) -> uptime = t.split(" ")[0].toDouble()
            t.contains(":") && Regex("^[\\w.@-]+:").containsMatchIn(t) && t.substringAfter(":").trim().split(Regex("\\s+")).size >= 16 -> {
                val name = t.substringBefore(":")
                if (name != "lo") {
                    val v = t.substringAfter(":").trim().split(Regex("\\s+"))
                    rx += v[0].toLongOrNull() ?: 0
                    tx += v[8].toLongOrNull() ?: 0
                }
            }
        }
    }
    if (busy < 0 || mem["MemTotal"] == null) return null
    val l = load ?: listOf(0.0, 0.0, 0.0)
    return ProcSample(busy, total, mem["MemTotal"] ?: 0, mem["MemAvailable"] ?: 0, mem["SwapTotal"] ?: 0, mem["SwapFree"] ?: 0,
        l[0], l[1], l[2], uptime, rx, tx, atMs)
}

fun metricsBetween(a: ProcSample, b: ProcSample): Metrics {
    val dt = ((b.atMs - a.atMs) / 1000.0).coerceAtLeast(0.001)
    val dTotal = (b.cpuTotal - a.cpuTotal).coerceAtLeast(1)
    return Metrics(
        cpuPct = ((b.cpuBusy - a.cpuBusy).coerceAtLeast(0) * 100.0 / dTotal).coerceIn(0.0, 100.0),
        memUsed = b.memTotal - b.memAvailable, memTotal = b.memTotal,
        swapUsed = b.swapTotal - b.swapFree, swapTotal = b.swapTotal,
        load = Triple(b.load1, b.load5, b.load15), uptimeS = b.uptimeS,
        rxBps = (b.netRx - a.netRx).coerceAtLeast(0) / dt, txBps = (b.netTx - a.netTx).coerceAtLeast(0) / dt,
    )
}

data class Filesystem(val target: String, val source: String, val fstype: String, val size: Long, val used: Long, val avail: Long) {
    val pct get() = if (size > 0) (used * 100 / size).toInt() else 0
}

/** "df -B1 --output=target,source,fstype,size,used,avail" without tmpfs-like mounts; one entry per device. */
fun parseDf(out: String): List<Filesystem> = out.lines().drop(1).mapNotNull { line ->
    val p = line.trim().split(Regex("\\s+"))
    if (p.size < 6) return@mapNotNull null
    val n = p.size
    Filesystem(p.subList(0, n - 5).joinToString(" "), p[n - 5], p[n - 4], p[n - 3].toLongOrNull() ?: 0, p[n - 2].toLongOrNull() ?: 0, p[n - 1].toLongOrNull() ?: 0)
}.distinctBy { it.source }.sortedBy { it.target.length }

/* ---------------------------------------------------------------- services */

data class ServiceUnit(val name: String, val load: String, val active: String, val sub: String, val description: String) {
    val failed get() = active == "failed"
    val running get() = active == "active"
}

/** systemctl list-units --output=json (systemd 2xx+). */
fun parseUnits(out: String): List<ServiceUnit> = runCatching { json.parseToJsonElement(out) as JsonArray }.getOrElse { JsonArray(emptyList()) }
    .mapNotNull { it.obj() }
    .map { ServiceUnit(it.str("unit") ?: "", it.str("load") ?: "", it.str("active") ?: "", it.str("sub") ?: "", it.str("description") ?: "") }
    .filter { it.load != "not-found" }

fun parseUnitFiles(out: String): Map<String, String> = runCatching { json.parseToJsonElement(out) as JsonArray }.getOrElse { JsonArray(emptyList()) }
    .mapNotNull { it.obj() }
    .associate { (it.str("unit_file") ?: "").substringAfterLast('/') to (it.str("state") ?: "") }

/* ---------------------------------------------------------------- logs */

data class JournalEntry(
    val timeMs: Long,
    val priority: Int,
    val identifier: String,
    val unit: String?,
    val message: String,
    val cursor: String?,
    val pid: String?,
)

/** One line of journalctl -o json. MESSAGE can be a byte array when it isn't valid UTF-8. */
fun parseJournalLine(line: String): JournalEntry? {
    val o = parseObject(line) ?: return null
    val msg = o["MESSAGE"].let { m ->
        m.prim()?.content ?: (m as? JsonArray)?.mapNotNull { it.prim()?.intOrNull?.toByte() }?.toByteArray()?.toString(Charsets.UTF_8) ?: ""
    }
    return JournalEntry(
        timeMs = (o.str("__REALTIME_TIMESTAMP")?.toLongOrNull() ?: 0) / 1000,
        priority = o.str("PRIORITY")?.toIntOrNull() ?: 6,
        identifier = o.str("SYSLOG_IDENTIFIER") ?: o.str("_COMM") ?: o.str("_SYSTEMD_UNIT") ?: "kernel".takeIf { o.str("_TRANSPORT") == "kernel" } ?: "?",
        unit = o.str("_SYSTEMD_UNIT") ?: o.str("_SYSTEMD_USER_UNIT"),
        message = msg,
        cursor = o.str("__CURSOR"),
        pid = o.str("_PID"),
    )
}

val PRIORITY_NAMES = listOf("Emergency", "Alert", "Critical", "Error", "Warning", "Notice", "Info", "Debug")

/* ---------------------------------------------------------------- containers */

data class Container(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: String,
    val createdS: Long?,
    val system: Boolean,
) {
    val running get() = state.equals("running", ignoreCase = true)
}

/** podman ps -a --format json */
fun parseContainers(out: String, system: Boolean): List<Container> =
    runCatching { json.parseToJsonElement(out) as JsonArray }.getOrElse { JsonArray(emptyList()) }.mapNotNull { it.obj() }.map { c ->
        val ports = c["Ports"].arr().mapNotNull { it.obj() }.joinToString(", ") { p ->
            val host = p.long("host_port") ?: p.long("hostPort")
            val cont = p.long("container_port") ?: p.long("containerPort")
            val proto = p.str("protocol") ?: "tcp"
            if (host != null) "$host→$cont/$proto" else "$cont/$proto"
        }
        Container(
            id = c.str("Id") ?: c.str("ID") ?: "",
            name = c.strings("Names").firstOrNull() ?: c.str("Names") ?: "",
            image = c.str("Image") ?: "",
            state = c.str("State") ?: "",
            status = c.str("Status") ?: "",
            ports = ports,
            createdS = c.long("Created"),
            system = system,
        )
    }

/* ---------------------------------------------------------------- updates */

data class PackageUpdate(val name: String, val arch: String, val version: String, val repo: String)

/** dnf check-update -q (exit 100 = updates available). Ignores "Obsoleting packages" and other headers. */
fun parseCheckUpdate(out: String): List<PackageUpdate> {
    val result = ArrayList<PackageUpdate>()
    for (line in out.lines()) {
        if (line.isBlank() || line.startsWith(" ")) continue
        if (line.startsWith("Obsoleting", ignoreCase = true) || line.startsWith("Security:")) break
        val p = line.trim().split(Regex("\\s+"))
        if (p.size != 3) continue
        val dot = p[0].lastIndexOf('.')
        if (dot <= 0) continue
        result += PackageUpdate(p[0].substring(0, dot), p[0].substring(dot + 1), p[1], p[2])
    }
    return result.distinctBy { it.name + "." + it.arch }
}

/* ---------------------------------------------------------------- shell commands used by the screens */

object SystemCommands {
    val INFO = listOf(
        "sh", "-c",
        "hostname; (. /etc/os-release && echo \"\$PRETTY_NAME\"); uname -r; " +
            "(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2-) || echo; nproc; cat /proc/sys/kernel/random/boot_id",
    )

    /** One block of counters every [intervalS] seconds, separated by a line "@@". */
    fun sampler(intervalS: Int = 2) = listOf(
        "sh", "-c",
        "while :; do cat /proc/stat /proc/meminfo /proc/loadavg /proc/uptime /proc/net/dev; echo @@; sleep $intervalS; done",
    )

    val DF = listOf(
        "df", "-B1", "--output=target,source,fstype,size,used,avail",
        "-x", "tmpfs", "-x", "devtmpfs", "-x", "overlay", "-x", "squashfs", "-x", "efivarfs", "-x", "fuse.portal",
    )

    val UNITS = listOf("systemctl", "list-units", "--type=service", "--all", "--no-pager", "--output=json")
    val UNIT_FILES = listOf("systemctl", "list-unit-files", "--type=service", "--no-pager", "--output=json")

    fun journal(lines: Int, priority: Int?, unit: String?, follow: Boolean, grep: String?): List<String> = buildList {
        addAll(listOf("journalctl", "--output=json", "--no-pager", "--lines=$lines"))
        if (priority != null) add("--priority=$priority")
        if (unit != null) add("--unit=$unit")
        if (!grep.isNullOrBlank()) addAll(listOf("--grep", grep, "--case-sensitive=false"))
        if (follow) add("--follow")
    }

    val CONTAINERS = listOf("podman", "ps", "--all", "--format", "json")
    val CHECK_UPDATE = listOf("dnf", "check-update", "-q")
}
