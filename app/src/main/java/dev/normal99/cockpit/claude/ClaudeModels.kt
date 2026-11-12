package dev.normal99.cockpit.claude

import dev.normal99.cockpit.cockpit.arrAt
import dev.normal99.cockpit.cockpit.bool
import dev.normal99.cockpit.cockpit.int
import dev.normal99.cockpit.cockpit.long
import dev.normal99.cockpit.cockpit.obj
import dev.normal99.cockpit.cockpit.objAt
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.prim
import dev.normal99.cockpit.cockpit.str
import dev.normal99.cockpit.cockpit.strings
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * The data behind the Claude Cockpit page (/usr/share/cockpit/claude): status files written by timers on the host.
 * Field names follow claude.js, so the app and the web page always show the same thing.
 */
object ClaudeFiles {
    const val STATUS = "/var/lib/claude-status/status.json"
    const val GITHUB = "/var/lib/claude-status/github.json"
    const val STORAGE = "/var/lib/claude-status-root/storage.json"
    const val BROKER = "/var/lib/claude-status-root/broker.json"
    const val RIG = "/var/lib/claude-status-root/mainrig.json"
    val LOGS = mapOf(
        "broker" to "/var/lib/claude-status-root/broker-activity.log",
        "cockpit" to "/var/lib/claude-status-root/cockpit-activity.log",
        "rig" to "/var/lib/claude-status-root/rig-activity.log",
    )
    const val CTL = "/usr/local/bin/claude-ctl"
}

/** Epoch milliseconds from either epoch seconds (broker) or ISO text with or without an offset. */
fun parseTime(v: JsonElement?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val p = v.prim() ?: return null
    p.doubleOrNull?.let { if (!p.isString) return (it * 1000).toLong() }
    return parseIso(p.content, zone)
}

fun parseIso(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
    runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }.getOrNull()

data class ClaudeSession(val window: String, val up: Boolean, val uptimeS: Long, val restartsToday: Int, val rc: String)
data class StatusEvent(val time: Long?, val kind: String, val text: String)
data class Branch(val branch: String, val ahead: Int, val behind: Int, val lastCommit: Long?, val subject: String)

data class Status(val updated: Long?, val sessions: List<ClaudeSession>, val branches: List<Branch>, val events: List<StatusEvent>) {
    companion object {
        fun parse(o: JsonObject) = Status(
            updated = parseTime(o["updated"]),
            sessions = o.arrAt("sessions").mapNotNull { it.obj() }.map {
                ClaudeSession(it.str("window") ?: "?", it.bool("up") == true, it.long("uptime_s") ?: 0, it.int("restarts_today") ?: 0, it.str("rc") ?: "unknown")
            },
            branches = o.arrAt("branches").mapNotNull { it.obj() }.map {
                Branch(it.str("branch") ?: "?", it.int("ahead") ?: 0, it.int("behind") ?: 0, parseTime(it["last_commit"]), it.str("subject") ?: "")
            },
            events = o.arrAt("events").mapNotNull { it.obj() }.map { StatusEvent(parseTime(it["time"]), it.str("kind") ?: "", it.str("text") ?: "") },
        )
    }
}

data class RootRequest(
    val id: String,
    val argv: List<String>,
    val reason: String,
    val cwd: String,
    val status: String,
    val tier: String?,
    val detail: String?,
    val created: Long?,
    val decidedVia: String?,
    val exit: Int?,
) {
    val command get() = argv.joinToString(" ")
    val isPending get() = status == "pending"

    companion object {
        fun parse(o: JsonObject) = RootRequest(
            id = o.str("id") ?: "?",
            argv = o.strings("argv"),
            reason = o.str("reason") ?: "",
            cwd = o.str("cwd") ?: "",
            status = o.str("status") ?: "unknown",
            tier = o.str("tier"),
            detail = o.str("detail"),
            created = parseTime(o["created"]),
            decidedVia = o.str("decided_via"),
            exit = o.objAt("result").int("exit"),
        )
    }
}

data class Broker(val updated: Long?, val pending: List<RootRequest>, val approvedNotRun: List<RootRequest>, val recent: List<RootRequest>) {
    /** Waiting for a decision or approved and about to run: the "open" list on the web page. */
    val open get() = pending + approvedNotRun
    /** Finished requests for the "Recent" list (the web page shows 6). */
    val history get() = recent.filter { it.status !in setOf("pending", "approved", "running") }

    companion object {
        private fun list(o: JsonObject, k: String) = o.arrAt(k).mapNotNull { it.obj() }.map(RootRequest::parse)
        fun parse(o: JsonObject) = Broker(parseTime(o["updated"]), list(o, "pending"), list(o, "approved_not_run"), list(o, "recent"))
    }
}

data class Check(val name: String, val state: String)
data class PullRequest(val number: Int, val title: String, val branch: String, val url: String, val checks: List<Check>)
data class GitHub(val updated: Long?, val prs: List<PullRequest>, val error: String?) {
    companion object {
        fun parse(o: JsonObject) = GitHub(
            updated = parseTime(o["updated"]),
            prs = o.arrAt("prs").mapNotNull { it.obj() }.map { p ->
                PullRequest(p.int("number") ?: 0, p.str("title") ?: "", p.str("branch") ?: "", p.str("url") ?: "",
                    p.arrAt("checks").mapNotNull { it.obj() }.map { Check(it.str("name") ?: "", it.str("state") ?: "") })
            },
            error = o.str("error"),
        )
    }
}

data class Snapshot(val number: Int, val date: String, val description: String?, val cleanup: String?, val type: String?) {
    val label get() = description?.takeIf { it.isNotBlank() } ?: cleanup ?: type ?: ""
}
data class Snapshots(val count: Int, val exclusiveBytes: Long, val latest: List<Snapshot>)
data class Disk(val size: Long, val used: Long, val avail: Long, val pct: Int)
data class Storage(
    val updated: Long?, val disk: Disk, val compression: String?, val cargoTarget: Long?, val podman: Long?, val cache: Long?,
    val snapshots: Snapshots?,
) {
    companion object {
        fun parse(o: JsonObject): Storage {
            val d = o.objAt("disk")
            val s = o.objAt("snapshots")
            return Storage(
                updated = parseTime(o["updated"]),
                disk = Disk(d.long("size") ?: 0, d.long("used") ?: 0, d.long("avail") ?: 0, d.int("pct") ?: 0),
                compression = o.str("compression"),
                cargoTarget = o.long("cargo_target"), podman = o.long("podman"), cache = o.long("cache"),
                snapshots = s?.let {
                    Snapshots(it.int("count") ?: 0, it.long("exclusive_bytes") ?: 0, it.arrAt("latest").mapNotNull { x -> x.obj() }.map { x ->
                        Snapshot(x.int("number") ?: 0, x.str("date") ?: "", x.str("description"), x.str("cleanup"), x.str("type"))
                    })
                },
            )
        }
    }
}

data class RigSession(val window: String, val up: Boolean, val rc: String)
data class Rig(
    val updated: Long?, val state: String, val tailscale: Boolean?, val apollo: Boolean?, val uptimeS: Long?, val locked: Boolean?,
    val sessions: List<RigSession>, val moonlightUrl: String?, val wakePending: Boolean,
) {
    companion object {
        fun parse(o: JsonObject) = Rig(
            updated = parseTime(o["updated"]), state = o.str("state") ?: "unknown", tailscale = o.bool("tailscale"),
            apollo = o.bool("apollo"), uptimeS = o.long("uptime_s"), locked = o.bool("locked"),
            sessions = o.arrAt("sessions").mapNotNull { it.obj() }.map { RigSession(it.str("window") ?: "?", it.bool("up") == true, it.str("rc") ?: "unknown") },
            moonlightUrl = o.str("moonlight_url"), wakePending = o.bool("wake_pending") == true,
        )
    }
}

/** One line of the Activity feed, from status.json events and the tab-separated activity logs. */
data class Activity(val time: Long?, val kind: String, val text: String)

fun parseActivityLog(source: String, text: String?): List<Activity> =
    (text ?: "").lineSequence().toList().takeLast(60).mapNotNull { line ->
        val parts = line.split('\t')
        val t = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val kind = parts.getOrElse(1) { "" }
        val extra = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
        Activity(parseIso(t), if (source == "broker") "root $kind" else kind, parts.getOrElse(2) { "" } + (extra?.let { " · exit $it" } ?: ""))
    }

/** Display names and colours for activity kinds, the same table as claude.js. */
enum class Tone { NEUTRAL, GREEN, RED, ORANGE, BLUE, PURPLE }

val EVENT_NAMES: Map<String, Pair<String, Tone>> = mapOf(
    "permission_prompt" to ("Claude asked for permission" to Tone.ORANGE),
    "idle_prompt" to ("Claude is waiting" to Tone.BLUE),
    "restart" to ("Claude restarted" to Tone.ORANGE),
    "crashloop" to ("Crash loop" to Tone.RED),
    "start" to ("Session started" to Tone.NEUTRAL),
    "watchdog" to ("Watchdog" to Tone.ORANGE),
    "notify-failed" to ("Notification failed" to Tone.RED),
    "root auto" to ("Root command (auto)" to Tone.NEUTRAL),
    "root decided" to ("Root request answered" to Tone.PURPLE),
    "cockpit" to ("Cockpit action" to Tone.NEUTRAL),
    "wake" to ("Rig wake" to Tone.BLUE), "up" to ("Rig is up" to Tone.GREEN), "fail" to ("Rig didn't wake" to Tone.RED),
    "state" to ("Rig state" to Tone.NEUTRAL), "suspend" to ("Rig suspend" to Tone.NEUTRAL), "poweroff" to ("Rig power off" to Tone.NEUTRAL),
)

fun requestStatusTone(status: String) = when (status) {
    "done" -> Tone.GREEN
    "denied", "refused", "error" -> Tone.RED
    else -> Tone.NEUTRAL
}

fun ciTone(state: String) = when (state) {
    "success" -> Tone.GREEN
    "failure", "timed_out", "error" -> Tone.RED
    "cancelled" -> Tone.NEUTRAL
    else -> Tone.ORANGE
}

data class ClaudeState(
    val status: Status? = null,
    val github: GitHub? = null,
    val storage: Storage? = null,
    val broker: Broker? = null,
    val rig: Rig? = null,
    val logs: Map<String, String> = emptyMap(),
) {
    val activity: List<Activity>
        get() = (status?.events.orEmpty().map { Activity(it.time, it.kind, it.text) } +
            logs.flatMap { (src, txt) -> parseActivityLog(src, txt) })
            .sortedByDescending { it.time ?: 0 }

    companion object {
        fun parseOrNull(text: String?): JsonObject? = text?.let { parseObject(it) }
    }
}

