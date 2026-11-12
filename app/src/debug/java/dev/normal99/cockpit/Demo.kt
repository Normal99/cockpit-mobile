package dev.normal99.cockpit

import dev.normal99.cockpit.cockpit.Frame
import dev.normal99.cockpit.cockpit.jsonOf
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.str
import dev.normal99.cockpit.cockpit.strings
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Debug builds only: a pretend Cockpit inside the app, on the phone's own loopback, so every screen can be tried
 * (and screenshotted) without a server. It speaks the real protocol and serves sample data shaped like the Claude
 * page's status files. Approving or denying a request updates the files, like the real broker does.
 */
object Demo {
    const val URL = "http://127.0.0.1:37999/"
    const val available = true
    private var server: MockWebServer? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val watchers = Collections.synchronizedList(ArrayList<Pair<WebSocket, Pair<String, String>>>()) // socket to (channel, path)
    private var admin = "sudo"
    private val now get() = System.currentTimeMillis() / 1000.0

    @Synchronized
    fun ensureStarted() {
        if (server != null) return
        val t = Thread {
            val s = MockWebServer()
            s.dispatcher = Handler
            s.start(InetAddress.getByName("127.0.0.1"), 37999)
            server = s
        }
        t.start()
        t.join()
    }

    /* ------------------------------------------------------------------ sample data */

    private fun iso(secAgo: Int) = java.time.OffsetDateTime.now().minusSeconds(secAgo.toLong()).withNano(0).toString()

    private val pending = mutableListOf(
        req("r3f9a21", listOf("dnf", "install", "-y", "java-25-openjdk-devel"), "JDK needed to build the new Android app with Gradle", "/home/claude/projects/cockpit-mobile", 95, "pending"),
        req("r77c0de", listOf("systemctl", "restart", "nginx.service"), "Pick up the new reverse proxy config for the demo site", "/home/claude/projects/site", 30, "pending"),
    )
    private val recent = mutableListOf(
        req("r2532fc", listOf("rm", "/tmp/broker-cockpit-test"), "COCKPIT TEST: approve this from the Claude page", "/tmp", 5400, "done", exit = 0, via = "cockpit"),
        req("r5309b6", listOf("snapper", "-c", "claude", "delete", "3"), "Remove the failed experiment snapshot", "/home/claude", 9000, "denied", via = "cli"),
    )

    private fun req(id: String, argv: List<String>, reason: String, cwd: String, ago: Int, status: String, exit: Int? = null, via: String? = null) =
        jsonOf("id" to id, "argv" to argv, "reason" to reason, "cwd" to cwd, "uid" to 1001, "tier" to "ask", "detail" to "not in the Auto tier",
            "status" to status, "created" to now - ago, "decided_via" to via, "result" to exit?.let { mapOf("exit" to it) })

    private fun files(): Map<String, String> = mapOf(
        "/var/lib/claude-status/status.json" to jsonOf(
            "updated" to iso(20),
            "sessions" to listOf(
                mapOf("window" to "main", "index" to 0, "up" to true, "uptime_s" to 15_800, "restarts_today" to 0, "rc" to "connected"),
                mapOf("window" to "rc", "index" to 1, "up" to true, "uptime_s" to 15_800, "restarts_today" to 1, "rc" to "down"),
            ),
            "branches" to listOf(
                mapOf("branch" to "claude/init", "ahead" to 4, "behind" to 0, "last_commit" to iso(600), "subject" to "Native Claude screen and notifications"),
            ),
            "events" to listOf(
                mapOf("time" to iso(7200), "kind" to "start", "text" to "main"),
                mapOf("time" to iso(3000), "kind" to "permission_prompt", "text" to "projects/cockpit-mobile"),
                mapOf("time" to iso(1500), "kind" to "restart", "text" to "rc exit=143 recent=1"),
                mapOf("time" to iso(400), "kind" to "idle_prompt", "text" to "projects/cockpit-mobile"),
            ),
        ).toString(),
        "/var/lib/claude-status/github.json" to jsonOf(
            "updated" to iso(90),
            "prs" to listOf(mapOf("number" to 1, "title" to "Cockpit Mobile: first version", "branch" to "claude/init",
                "url" to "https://github.com/", "checks" to listOf(mapOf("name" to "build", "state" to "success"), mapOf("name" to "test", "state" to "in_progress")))),
        ).toString(),
        "/var/lib/claude-status-root/storage.json" to jsonOf(
            "updated" to iso(300),
            "disk" to mapOf("size" to 125_257_646_080, "used" to 86_290_109_440, "avail" to 38_967_536_640, "pct" to 69),
            "compression" to "60%", "cargo_target" to 9_036_697_802, "podman" to 2_114_889_000, "cache" to 14_045_317_008,
            "snapshots" to mapOf("count" to 31, "exclusive_bytes" to 1_204_000_000, "latest" to listOf(
                mapOf("number" to 31, "date" to iso(1200).replace('T', ' ').take(19), "description" to "timeline", "cleanup" to "timeline", "type" to "single"),
                mapOf("number" to 30, "date" to iso(4800).replace('T', ' ').take(19), "description" to "before big refactor", "cleanup" to "number", "type" to "single"),
            )),
        ).toString(),
        "/var/lib/claude-status-root/broker.json" to jsonOf(
            "updated" to iso(10).take(19), "pending" to JsonArray(pending), "approved_not_run" to JsonArray(emptyList()), "recent" to JsonArray(recent),
        ).toString(),
        "/var/lib/claude-status-root/mainrig.json" to jsonOf(
            "updated" to iso(40), "state" to "up", "tailscale" to true, "apollo" to true, "uptime_s" to 5_175, "locked" to true,
            "sessions" to listOf(mapOf("window" to "main", "up" to true, "rc" to "connected")), "moonlight_url" to null, "wake_pending" to false,
        ).toString(),
        "/var/lib/claude-status-root/broker-activity.log" to "${iso(8000).take(19)}\tauto\tdnf install -y htop\t0\n",
        "/var/lib/claude-status-root/cockpit-activity.log" to "${iso(2500)}\tcockpit\treconnect RC in main\n",
        "/var/lib/claude-status-root/rig-activity.log" to "${iso(5300)}\tup\tstate booting -> up\n",
        "/etc/os-release" to "NAME=\"Fedora Linux\"\nPRETTY_NAME=\"Fedora Linux 45 (Server Edition)\"\nID=fedora\n",
    )

    private val manifests = """
        {"system":{"menu":{"index":{"label":"Overview","order":-2},"logs":{"label":"Logs","order":20},"services":{"label":"Services","order":50}},
                   "tools":{"terminal":{"label":"Terminal"}}},
         "storaged":{"menu":{"index":{"label":"Storage","order":30}}},
         "networkmanager":{"menu":{"index":{"label":"Networking","order":40}}},
         "podman":{"menu":{"index":{"label":"Podman containers","order":60}}},
         "packagekit":{"menu":{"updates":{"label":"Software updates","order":70,"path":"updates"}}},
         "files":{"menu":{"index":{"label":"File browser","order":80}}},
         "claude":{"menu":{"index":{"label":"Claude","order":25}}}}
    """.trimIndent()

    private val units = listOf(
        Triple("sshd.service", "active" to "running", "OpenSSH server daemon"),
        Triple("cockpit.service", "active" to "running", "Cockpit Web Service"),
        Triple("tailscaled.service", "active" to "running", "Tailscale node agent"),
        Triple("podman-auto-update.service", "inactive" to "dead", "Podman auto-update service"),
        Triple("snapper-timeline.service", "inactive" to "dead", "Timeline of Snapper Snapshots"),
        Triple("ntfy.service", "active" to "running", "ntfy notification server"),
        Triple("btrfs-scrub-notify.service", "failed" to "failed", "Monthly btrfs scrub with phone notification"),
        Triple("NetworkManager.service", "active" to "running", "Network Manager"),
        Triple("chronyd.service", "active" to "running", "NTP client/server"),
        Triple("firewalld.service", "active" to "running", "firewalld - dynamic firewall daemon"),
        Triple("dnf-makecache.service", "inactive" to "dead", "dnf makecache"),
    )

    /* ------------------------------------------------------------------ server */

    private object Handler : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/cockpit/login" -> MockResponse.Builder().code(200).addHeader("Set-Cookie", "cockpit=demo; Path=/; HttpOnly").body("{}").build()
                path == "/cockpit/socket" -> MockResponse.Builder().webSocketUpgrade(Socket()).build()
                path.endsWith("/manifests.json") -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(manifests).build()
                path.endsWith(".html") -> MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body(
                    "<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:16px'>" +
                        "<h2>Demo page</h2><p>On a real host this is Cockpit's own <b>${path.substringAfter("@localhost/")}</b>, running inside the app with your login.</p>",
                ).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private class Socket : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("\n" + """{"command":"init","version":1,"channel-seed":"d:","host":"localhost"}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            watchers.removeAll { it.first === webSocket }
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val (ch, payload) = Frame.decode(text) ?: return
            if (ch.isNotEmpty()) return dbusCall(webSocket, ch, payload)
            val msg = parseObject(payload) ?: return
            val id = msg.str("channel")
            when (msg.str("command")) {
                "open" -> open(webSocket, id!!, msg)
                "close" -> {
                    watchers.removeAll { it.first === webSocket && it.second.first == id }
                    streams.remove(id)?.cancel(true)
                }
            }
        }

        private val streams = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()

        private fun ctl(ws: WebSocket, id: String, vararg p: Pair<String, Any?>) = ws.send("\n" + jsonOf("channel" to id, *p))

        private fun finish(ws: WebSocket, id: String, out: String, exit: Int = 0, message: String? = null, delayMs: Long = 120) {
            timer.schedule({
                if (out.isNotEmpty()) ws.send("$id\n$out")
                ctl(ws, id, "command" to "close", "exit-status" to exit, "message" to message)
            }, delayMs, TimeUnit.MILLISECONDS)
        }

        private fun open(ws: WebSocket, id: String, msg: JsonObject) {
            ctl(ws, id, "command" to "ready")
            when (msg.str("payload")) {
                "fsread1" -> {
                    val content = files()[msg.str("path")]
                    if (content == null) ctl(ws, id, "command" to "close", "tag" to "-")
                    else { ws.send("$id\n$content"); ctl(ws, id, "command" to "close", "tag" to "1:${content.hashCode()}") }
                }
                "fswatch1" -> watchers += ws to (id to (msg.str("path") ?: ""))
                "dbus-json3" -> Unit
                "stream" -> spawn(ws, id, msg.strings("spawn"), msg.str("superuser"))
                else -> ctl(ws, id, "command" to "close", "problem" to "not-supported")
            }
        }

        private fun dbusCall(ws: WebSocket, ch: String, payload: String) {
            val m = parseObject(payload) ?: return
            val call = m["call"] as? JsonArray ?: return
            val method = (call.getOrNull(2) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val reply = when (method) {
                "Get" -> """[[{"t":"s","v":"$admin"}]]"""
                "Start" -> { admin = "sudo"; "[[]]" }
                "Stop" -> { admin = "none"; "[[]]" }
                else -> "[[]]"
            }
            ws.send("$ch\n" + """{"reply":$reply,"id":"${m.str("id")}"}""")
        }

        private fun needsRoot(ws: WebSocket, id: String, superuser: String?): Boolean {
            if (superuser == "require" && admin == "none") {
                ctl(ws, id, "command" to "close", "problem" to "access-denied")
                return true
            }
            return false
        }

        private fun spawn(ws: WebSocket, id: String, args: List<String>, superuser: String?) {
            val joined = args.joinToString(" ")
            if (needsRoot(ws, id, superuser)) return
            when {
                args.first() == "sh" && joined.contains("PRETTY_NAME") ->
                    finish(ws, id, "demo-box\nFedora Linux 45 (Server Edition)\n6.17.9-300.fc45.x86_64\n Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz\n4\n6d1f0c1e-demo\n")
                args.first() == "sh" && joined.contains("/proc/stat") -> sampler(ws, id)
                args.first() == "df" -> finish(ws, id, "Mounted on Filesystem Type 1B-blocks Used Avail\n" +
                    "/ /dev/sda3 btrfs 125257646080 86290109440 38967536640\n/boot /dev/sda2 ext4 1006632960 321912832 616562688\n")
                joined.startsWith("systemctl list-units") -> finish(ws, id, JsonArray(units.map { (n, s, d) ->
                    jsonOf("unit" to n, "load" to "loaded", "active" to s.first, "sub" to s.second, "description" to d) }).toString())
                joined.startsWith("systemctl list-unit-files") -> finish(ws, id, JsonArray(units.map { (n, _, _) ->
                    jsonOf("unit_file" to n, "state" to if (n.startsWith("dnf") || n.startsWith("podman")) "disabled" else "enabled") }).toString())
                joined.startsWith("systemctl status") -> finish(ws, id, "● ${args.last()}\n     Loaded: loaded (/usr/lib/systemd/system/${args.last()}; enabled)\n" +
                    "     Active: active (running) since today; 4h 23min ago\n   Main PID: 1234\n\nNov 12 01:02:03 demo-box systemd[1]: Started ${args.last()}.\n")
                args.first() == "systemctl" || args.first() == "shutdown" -> finish(ws, id, "", delayMs = 700)
                args.first() == "journalctl" -> journal(ws, id, follow = "--follow" in args,
                    maxPrio = args.firstOrNull { it.startsWith("--priority=") }?.substringAfter('=')?.toIntOrNull() ?: 7)
                args.first() == "podman" && args.getOrNull(1) == "ps" -> finish(ws, id, if (superuser == "require") "[]" else """[
                    {"Id":"9f1c2a","Names":["ntfy"],"Image":"docker.io/binwiederhier/ntfy:latest","State":"running","Status":"Up 4 hours","Ports":[{"host_port":2586,"container_port":80,"protocol":"tcp"}],"Created":1794400000},
                    {"Id":"77ab01","Names":["moonlight-web"],"Image":"ghcr.io/example/moonlight-web:1.4","State":"exited","Status":"Exited (0) 2 days ago","Ports":[],"Created":1794100000}]""")
                args.first() == "podman" -> finish(ws, id, if (args.getOrNull(1) == "logs") "listening on :80\nsubscribed topic optiplex-claude\n" else args.last() + "\n", delayMs = 600)
                args.first() == "dnf" -> finish(ws, id, "kernel.x86_64   6.18.2-300.fc45   updates\nkernel-core.x86_64   6.18.2-300.fc45   updates\n" +
                    "podman.x86_64   5:5.7.1-1.fc45   updates\ncockpit.x86_64   371-1.fc45   updates\n", exit = 100, delayMs = 1500)
                args.first().endsWith("claude-ctl") -> claudeCtl(ws, id, args.drop(1))
                args.first() == "bash" -> finish(ws, id, "(demo) ${args.last()}\n")
                else -> finish(ws, id, "")
            }
        }

        private fun claudeCtl(ws: WebSocket, id: String, a: List<String>) {
            if (a.firstOrNull() == "request") {
                val rid = a.getOrNull(2)
                val r = pending.firstOrNull { it.str("id") == rid }
                if (r == null) return finish(ws, id, "", 1, "no such pending request $rid")
                pending.remove(r)
                val approve = a[1] != "deny"
                recent.add(0, JsonObject(r + mapOf(
                    "status" to kotlinx.serialization.json.JsonPrimitive(if (approve) "done" else "denied"),
                    "decided_via" to kotlinx.serialization.json.JsonPrimitive("cockpit"),
                ) + (if (approve) mapOf("result" to jsonOf("exit" to 0)) else emptyMap())))
                finish(ws, id, if (approve) "approved $rid" else "denied $rid", delayMs = 700)
                timer.schedule({ changed("/var/lib/claude-status-root/broker.json") }, 900, TimeUnit.MILLISECONDS)
            } else {
                finish(ws, id, when (a.firstOrNull()) { "new-session" -> "s1112-0142"; else -> "" }, delayMs = 900)
            }
        }

        private fun changed(path: String) {
            synchronized(watchers) {
                watchers.filter { it.second.second == path }.forEach { (w, cp) -> w.send("${cp.first}\n" + jsonOf("event" to "changed", "path" to path)) }
            }
        }

        private fun sampler(ws: WebSocket, id: String) {
            var user = 100_000L
            var idle = 900_000L
            var rx = 5_000_000_000L
            var tx = 900_000_000L
            var t = 0
            streams[id] = timer.scheduleWithFixedDelay({
                t++
                val busy = (40 + 30 * kotlin.math.sin(t / 4.0) + Random.nextInt(20)).toLong().coerceIn(2, 190)
                user += busy
                idle += 200 - busy
                rx += Random.nextLong(20_000, 900_000)
                tx += Random.nextLong(5_000, 200_000)
                val avail = 9_800_000 + Random.nextLong(-200_000, 200_000)
                ws.send("$id\ncpu  $user 0 ${user / 5} $idle 300 0 0 0 0 0\n" +
                    "MemTotal:       16301000 kB\nMemAvailable:   $avail kB\nSwapTotal:       8388604 kB\nSwapFree:        8100000 kB\n" +
                    "0.%02d 0.48 0.41 2/412 9999\n${15_800 + t * 2}.00 50000.00\n".format(30 + Random.nextInt(60)) +
                    "  eth0: $rx 0 0 0 0 0 0 0 $tx 0 0 0 0 0 0 0\n@@\n")
            }, 0, 2, TimeUnit.SECONDS)
        }

        private fun journal(ws: WebSocket, id: String, follow: Boolean, maxPrio: Int) {
            val lines = listOf<Triple<String, Int, String>>(
                Triple("sshd", 6, "Accepted publickey for claude from 100.64.0.2 port 51122 ssh2"),
                Triple("systemd", 6, "Started claude-status.service - Claude status for Cockpit."),
                Triple("btrfs-scrub-notify", 3, "scrub of / failed: device busy"),
                Triple("kernel", 4, "usb 1-1.2: device descriptor read/64, error -71"),
                Triple("cockpit-ws", 5, "New connection to session from 100.64.0.2"),
                Triple("tailscaled", 6, "magicsock: endpoints changed"),
                Triple("podman", 6, "container 9f1c2a health_status=healthy"),
            ).filter { it.second <= maxPrio }
            val start = System.currentTimeMillis() - 3_600_000
            fun entry(i: Int, ms: Long): String {
                val (ident, prio, text) = lines[i % lines.size]
                return jsonOf("__REALTIME_TIMESTAMP" to (ms * 1000).toString(), "PRIORITY" to prio.toString(), "SYSLOG_IDENTIFIER" to ident,
                    "_SYSTEMD_UNIT" to if (ident == "kernel") null else "$ident.service", "MESSAGE" to text, "_PID" to (1000 + i).toString()).toString() + "\n"
            }
            ws.send("$id\n" + (0 until 40).joinToString("") { entry(it, start + it * 90_000L) })
            if (!follow) { ctl(ws, id, "command" to "close", "exit-status" to 0); return }
            var n = 40
            streams[id] = timer.scheduleWithFixedDelay({ ws.send("$id\n" + entry(n++, System.currentTimeMillis())) }, 4, 6, TimeUnit.SECONDS)
        }
    }
}
