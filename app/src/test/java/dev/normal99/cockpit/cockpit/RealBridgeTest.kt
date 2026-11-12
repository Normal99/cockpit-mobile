package dev.normal99.cockpit.cockpit

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the client against the real cockpit-bridge of the build machine (skipped where it isn't installed):
 * a test WebSocket stands in for cockpit-ws and pipes frames to the bridge's stdio ("<length>\n<channel>\n<payload>").
 */
class RealBridgeTest {
    private val bridgePath = "/usr/bin/cockpit-bridge"
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private inner class Pipe : WebSocketListener() {
        lateinit var proc: Process
        lateinit var stdin: OutputStream

        override fun onOpen(webSocket: WebSocket, response: Response) {
            proc = ProcessBuilder(bridgePath).redirectError(File("/dev/null")).start()
            stdin = proc.outputStream
            Thread {
                val inp = BufferedInputStream(proc.inputStream)
                while (true) {
                    val len = StringBuilder()
                    while (true) {
                        val c = inp.read()
                        if (c < 0) return@Thread
                        if (c == '\n'.code) break
                        len.append(c.toChar())
                    }
                    val text = String(inp.readNBytes(len.toString().toInt()), Charsets.UTF_8)
                    // like cockpit-ws: the browser gets ws's own init (seed + host), not the bridge's
                    if (text.startsWith("\n") && parseObject(text.substring(1)).str("command") == "init") {
                        webSocket.send("\n" + """{"command":"init","version":1,"host":"localhost","channel-seed":"1:"}""")
                    } else {
                        webSocket.send(text)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun onMessage(webSocket: WebSocket, message: String) {
            val isInit = message.startsWith("\n") && parseObject(message.substring(1)).str("command") == "init"
            val text = if (isInit) "\n" + """{"command":"init","version":1,"host":"localhost"}""" else message
            val bytes = text.toByteArray(Charsets.UTF_8)
            synchronized(this) {
                stdin.write("${bytes.size}\n".toByteArray())
                stdin.write(bytes)
                stdin.flush()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            proc.destroy()
        }
    }

    @Before fun setUp() {
        assumeTrue("cockpit-bridge not installed", File(bridgePath).canExecute())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.url.encodedPath) {
                "/cockpit/login" -> MockResponse.Builder().code(200).addHeader("Set-Cookie", "cockpit=x; Path=/").build()
                "/cockpit/socket" -> MockResponse.Builder().webSocketUpgrade(Pipe()).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.close()
    }

    private suspend fun session() = CockpitSession(Endpoint(server.url("/").toString()), OkHttpClient()).apply {
        connect("me", "pw", admin = false)
    }

    @Test fun spawnFilesAndExitStatus() = runBlocking {
        val s = session()
        val host = s.spawn(listOf("hostname")).trim()
        assertTrue(host.isNotEmpty())
        val r = s.spawnResult(listOf("sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals("out\n", r.output)
        assertEquals(3, r.exitStatus)
        assertEquals("err\n", r.message)
        assertTrue(s.readFile("/etc/os-release")!!.contains("ID="))
        assertEquals(null, s.readFile("/definitely/not/here"))
        s.disconnect()
    }

    @Test fun watchSeesChanges() = runBlocking {
        val s = session()
        val f = File.createTempFile("watch", ".json").apply { writeText("one") }
        try {
            val second = scope.async { withTimeout(15_000) { s.watchFile(f.path).filter { it == "two" }.first() } }
            delay(1500)
            // replace the file the way the status timers do (write then rename)
            val tmp = File(f.parentFile, f.name + ".tmp").apply { writeText("two") }
            tmp.renameTo(f)
            assertEquals("two", second.await())
        } finally {
            f.delete()
            s.disconnect()
        }
    }

    @Test fun dbusAndAdminState() = runBlocking {
        val s = session()
        val bus = DBusClient(s, "system", "org.freedesktop.systemd1", scope = scope)
        val version = bus.call("/org/freedesktop/systemd1", "org.freedesktop.DBus.Properties", "Get",
            listOf("org.freedesktop.systemd1.Manager", "Version"), "ss")
        assertTrue(version.toString(), version.firstOrNull().variantValue().prim()!!.content.isNotEmpty())
        bus.close()
        // the bridge isn't privileged here, so admin access is off
        assertEquals("none", AdminAccess(s, scope).current())
        s.disconnect()
    }

    @Test fun superuserRequireIsRefusedWithoutAdmin() = runBlocking {
        val s = session()
        try {
            s.spawn(listOf("id", "-u"), Superuser.REQUIRE)
            throw AssertionError("expected access-denied")
        } catch (e: CockpitException) {
            assertTrue(e.problem, e.isAccessDenied)
        }
        // "try" falls back to the logged-in user
        assertEquals(s.spawn(listOf("id", "-u")), s.spawn(listOf("id", "-u"), Superuser.TRY))
        s.disconnect()
    }
}
