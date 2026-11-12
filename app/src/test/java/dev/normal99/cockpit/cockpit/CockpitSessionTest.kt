package dev.normal99.cockpit.cockpit

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A tiny Cockpit stand-in: password login, the init handshake, and a few channel types. */
class FakeCockpit : Dispatcher() {
    val received = CopyOnWriteArrayList<String>()
    val loginHeaders = CopyOnWriteArrayList<Map<String, String>>()
    var files = mapOf("/etc/hostname" to "optiplex\n")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when (path) {
            "/cockpit/login" -> {
                loginHeaders += mapOf(
                    "auth" to (request.headers["Authorization"] ?: ""),
                    "superuser" to (request.headers["X-Superuser"] ?: ""),
                )
                val ok = request.headers["Authorization"] == "Basic " + Base64.getEncoder().encodeToString("admin:secret".toByteArray())
                if (ok) MockResponse.Builder().code(200).addHeader("Set-Cookie", "cockpit=abc123; Path=/; HttpOnly").body("{}").build()
                else MockResponse.Builder().code(401).body("""{"problem":"authentication-failed","message":"Authentication failed"}""").build()
            }
            "/cockpit/socket" -> {
                if (request.headers["Cookie"]?.contains("cockpit=abc123") != true) return MockResponse.Builder().code(403).build()
                MockResponse.Builder().webSocketUpgrade(Socket()).build()
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    inner class Socket : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("\n" + """{"command":"init","version":1,"channel-seed":"7:","host":"localhost"}""")
            webSocket.send("\n" + """{"command":"ping"}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val (ch, payload) = Frame.decode(text)!!
            if (ch.isNotEmpty()) return
            val msg = parseObject(payload)!!
            if (msg.str("command") != "open") return
            val id = msg.str("channel")!!
            fun ctl(vararg p: Pair<String, Any?>) = webSocket.send("\n" + jsonOf("channel" to id, *p))
            when (msg.str("payload")) {
                "stream" -> {
                    val args = msg.strings("spawn")
                    ctl("command" to "ready")
                    when (args.first()) {
                        "echo" -> {
                            webSocket.send("$id\n" + args.drop(1).joinToString(" ") + "\n")
                            ctl("command" to "close", "exit-status" to 0)
                        }
                        "false" -> ctl("command" to "close", "exit-status" to 1, "message" to "it failed")
                        "admin-only" -> if (msg.str("superuser") == "require") {
                            webSocket.send("$id\nroot\n"); ctl("command" to "close", "exit-status" to 0)
                        } else ctl("command" to "close", "problem" to "access-denied")
                        "ticker" -> {
                            for (i in 1..3) webSocket.send("$id\nline$i\nhalf")
                            webSocket.send("$id\n-end\n")
                            ctl("command" to "close", "exit-status" to 0)
                        }
                    }
                }
                "fsread1" -> {
                    val content = files[msg.str("path")]
                    if (content == null) ctl("command" to "close", "tag" to "-")
                    else {
                        webSocket.send("$id\n$content")
                        ctl("command" to "close", "tag" to "1:2")
                    }
                }
            }
        }
    }
}

class CockpitSessionTest {
    private lateinit var server: MockWebServer
    private val fake = FakeCockpit()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun session() = CockpitSession(Endpoint(server.url("/").toString()), OkHttpClient())

    @Test fun loginHandshakeAndSpawn() = runBlocking {
        val s = session()
        s.connect("admin", "secret", admin = true)
        assertEquals("any", fake.loginHeaders.single()["superuser"])
        assertEquals("localhost", s.defaultHost)
        assertEquals("hello world\n", s.spawn(listOf("echo", "hello", "world")))

        // the client answered init and the ping, and its open carried the host and seed
        val controls = fake.received.filter { it.startsWith("\n") }.map { parseObject(it.substring(1))!! }
        assertEquals(1, controls.first().int("version"))
        assertEquals("init", controls.first().str("command"))
        assertTrue(controls.any { it.str("command") == "pong" })
        val open = controls.first { it.str("command") == "open" }
        assertEquals("localhost", open.str("host"))
        assertTrue(open.str("channel")!!.startsWith("7:"))
        s.disconnect()
    }

    @Test fun wrongPasswordIsReported() = runBlocking {
        try {
            session().connect("admin", "nope", admin = false)
            fail("expected a login failure")
        } catch (e: CockpitException) {
            assertEquals("authentication-failed", e.problem)
            assertEquals("Wrong user name or password.", e.friendly())
        }
    }

    @Test fun exitStatusAndAccessDenied() = runBlocking {
        val s = session()
        s.connect("admin", "secret", admin = true)
        val r = s.spawnResult(listOf("false"))
        assertEquals(1, r.exitStatus)
        assertEquals("it failed", r.message)
        try {
            s.spawn(listOf("admin-only"))
            fail("expected access-denied")
        } catch (e: CockpitException) {
            assertTrue(e.isAccessDenied)
        }
        assertEquals("root\n", s.spawn(listOf("admin-only"), Superuser.REQUIRE))
    }

    @Test fun streamLinesAreReassembled() = runBlocking {
        val s = session()
        s.connect("admin", "secret", admin = true)
        val lines = withTimeout(5000) { s.spawnStream(listOf("ticker")).lines().toList() }
        assertEquals(listOf("line1", "halfline2", "halfline3", "half-end"), lines)
    }

    @Test fun readFileAndMissingFile() = runBlocking {
        val s = session()
        s.connect("admin", "secret", admin = true)
        assertEquals("optiplex\n", s.readFile("/etc/hostname"))
        assertNull(s.readFile("/nope"))
        assertEquals("optiplex\n", withTimeout(5000) { s.watchFile("/etc/hostname").first() })
    }

    @Test fun disconnectClosesOpenChannels() = runBlocking {
        val s = session()
        s.connect("admin", "secret", admin = true)
        val ch = s.open("payload" to "stream", "spawn" to listOf("sleep"))
        s.disconnect()
        val events = withTimeout(5000) { ch.events.receiveAsFlowList() }
        val closed = events.last() as ChannelEvent.Closed
        assertEquals("disconnected", closed.problem)
    }

    private suspend fun kotlinx.coroutines.channels.Channel<ChannelEvent>.receiveAsFlowList(): List<ChannelEvent> {
        val out = mutableListOf<ChannelEvent>()
        for (e in this) out += e
        return out
    }
}

class EndpointTest {
    @Test fun normalizes() {
        assertEquals("https://192.168.1.10:9090/", Endpoint.normalize("192.168.1.10"))
        assertEquals("https://box.tail1.ts.net/", Endpoint.normalize("box.tail1.ts.net"))
        assertEquals("https://box:9091/", Endpoint.normalize("box:9091"))
        assertEquals("http://box:9090/", Endpoint.normalize("http://box:9090/"))
        assertEquals("https://box:9090/admin/", Endpoint.normalize("box/admin"))
    }

    @Test fun originAndSocket() {
        val e = Endpoint("https://box.tail1.ts.net")
        assertEquals("https://box.tail1.ts.net", e.origin)
        assertEquals("wss://box.tail1.ts.net/cockpit/socket", e.socketUrl())
        val p = Endpoint("10.0.0.2")
        assertEquals("https://10.0.0.2:9090", p.origin)
        assertEquals("https://10.0.0.2:9090/cockpit/login", p.url("cockpit/login").toString())
    }

    @Test fun frames() {
        assertEquals("\n{}", Frame.encode("", "{}"))
        assertEquals("4" to "a\nb", Frame.decode("4\na\nb"))
        assertNull(Frame.decode("nonewline"))
    }
}
