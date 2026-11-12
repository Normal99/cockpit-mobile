package dev.normal99.cockpit.cockpit

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Where a Cockpit lives, e.g. https://myhost.tailnet.ts.net or https://192.168.1.10:9090 (a UrlRoot path is kept). */
class Endpoint(baseUrl: String) {
    val base: HttpUrl = normalize(baseUrl).toHttpUrl()

    fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path.trimStart('/')).build()

    /** The Origin header cockpit-ws checks against its Origins setting. */
    val origin: String
        get() = buildString {
            append(base.scheme).append("://").append(base.host)
            if (base.port != HttpUrl.defaultPort(base.scheme)) append(":").append(base.port)
        }

    fun socketUrl(): String = url("cockpit/socket").toString().replaceFirst("http", "ws")

    companion object {
        /**
         * "192.168.1.10" -> https://192.168.1.10:9090/ (Cockpit's own port); "box.tailnet.ts.net" -> https://box.tailnet.ts.net/
         * (Tailscale serve on 443). Anything with a scheme or port is kept as typed.
         */
        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.contains("://")) {
                val host = s.substringBefore('/')
                val rest = s.substring(host.length)
                val hasPort = host.substringAfterLast(']').contains(':')
                val port = if (hasPort || host.endsWith(".ts.net")) "" else ":9090"
                s = "https://$host$port$rest"
            }
            return "$s/"
        }
    }
}

sealed interface ChannelEvent {
    data class Ready(val message: JsonObject) : ChannelEvent
    data class Data(val text: String) : ChannelEvent
    data object Done : ChannelEvent
    data class Control(val message: JsonObject) : ChannelEvent
    data class Closed(val message: JsonObject) : ChannelEvent {
        val problem get() = message.str("problem")
        val text get() = message.str("message")
        val exitStatus get() = message.int("exit-status")
        val exitSignal get() = message.str("exit-signal")
    }
}

/** One channel multiplexed over the session's WebSocket. Events arrive in order and end with [ChannelEvent.Closed]. */
class CockpitChannel internal constructor(val id: String, private val session: CockpitSession) {
    val events = Channel<ChannelEvent>(Channel.UNLIMITED)
    @Volatile var isClosed = false
        private set

    fun send(text: String) = session.sendFrame(id, text)
    fun sendJson(message: JsonObject) = send(message.toString())
    fun done() = session.sendControl(jsonOf("command" to "done", "channel" to id))
    fun options(vararg pairs: Pair<String, Any?>) =
        session.sendControl(jsonOf("command" to "options", "channel" to id, *pairs))

    fun close(problem: String? = null) {
        if (isClosed) return
        session.sendControl(jsonOf("command" to "close", "channel" to id, "problem" to problem))
        finish(jsonOf("command" to "close", "channel" to id, "problem" to (problem ?: "cancelled")))
    }

    internal fun deliver(event: ChannelEvent) {
        if (!isClosed) events.trySend(event)
    }

    internal fun finish(message: JsonObject) {
        if (isClosed) return
        isClosed = true
        session.forget(id)
        events.trySend(ChannelEvent.Closed(message))
        events.close()
    }
}

/**
 * A logged-in Cockpit session: the same protocol cockpit.js speaks, so permissions and admin access behave
 * exactly as in the web UI. Login with `X-Superuser: any` makes Cockpit start admin access with the password.
 */
class CockpitSession(
    val endpoint: Endpoint,
    private val http: OkHttpClient,
) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Ready : State
        data class Failed(val error: Throwable) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val channels = ConcurrentHashMap<String, CockpitChannel>()
    private val counter = AtomicInteger(0)
    @Volatile private var seed = "m"
    @Volatile private var ws: WebSocket? = null
    @Volatile private var initDone = CompletableDeferred<JsonObject>()

    /** Cookie header from the login, also handed to WebViews so plugin pages share this session. */
    @Volatile var cookies: List<String> = emptyList()
        private set
    @Volatile var initMessage: JsonObject? = null
        private set
    /** "localhost" normally; included in open messages like cockpit.js does. */
    @Volatile var defaultHost: String? = null
        private set

    val isReady get() = state.value == State.Ready

    suspend fun connect(user: String, password: String, admin: Boolean, timeoutMs: Long = 20_000) {
        disconnect()
        _state.value = State.Connecting
        try {
            cookies = login(user, password, admin)
            openSocket()
            withTimeout(timeoutMs) { initDone.await() }
            _state.value = State.Ready
        } catch (e: Throwable) {
            ws?.cancel()
            ws = null
            _state.value = State.Failed(e)
            throw e
        }
    }

    private suspend fun login(user: String, password: String, admin: Boolean): List<String> = withContext(Dispatchers.IO) {
        val auth = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        val req = Request.Builder()
            .url(endpoint.url("cockpit/login"))
            .header("Authorization", "Basic $auth")
            .header("X-Superuser", if (admin) "any" else "none")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        http.newCall(req).execute().use { resp -> loginResult(resp) }
    }

    private fun loginResult(resp: Response): List<String> {
        val body = resp.body.string()
        when (resp.code) {
            200 -> {
                val cookies = resp.headers("Set-Cookie").map { it.substringBefore(';').trim() }.filter { it.contains('=') }
                if (cookies.isEmpty()) throw CockpitException("authentication-failed", "Cockpit did not start a session")
                return cookies
            }
            401, 403 -> {
                val problem = parseObject(body).str("problem") ?: "authentication-failed"
                throw CockpitException(problem, parseObject(body).str("message") ?: "Login failed")
            }
            404 -> throw CockpitException("no-cockpit", "No Cockpit at ${endpoint.base}")
            else -> throw CockpitException("internal-error", "Login failed: HTTP ${resp.code} ${resp.message}".trim())
        }
    }

    private fun openSocket() {
        initDone = CompletableDeferred()
        val req = Request.Builder()
            .url(endpoint.socketUrl())
            .header("Origin", endpoint.origin)
            .header("Cookie", cookies.joinToString("; "))
            .header("Sec-WebSocket-Protocol", "cockpit1")
            .build()
        ws = http.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === ws) handle(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) handle(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket === ws) lost(CockpitException("disconnected", reason.ifEmpty { "Connection closed" }))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === ws) lost(t)
        }
    }

    internal fun handle(message: String) {
        val (channel, payload) = Frame.decode(message) ?: return
        if (channel.isNotEmpty()) {
            channels[channel]?.deliver(ChannelEvent.Data(payload))
            return
        }
        val msg = parseObject(payload) ?: return
        val ch = msg.str("channel")
        when (msg.str("command")) {
            "init" -> onInit(msg)
            "ping" -> sendControl(JsonObject(msg + ("command" to JsonPrimitive("pong"))))
            "close" -> if (ch == null) lost(CockpitException(msg.str("problem") ?: "disconnected", msg.str("message") ?: "Session ended"))
                       else channels[ch]?.finish(msg)
            "ready" -> ch?.let { channels[it]?.deliver(ChannelEvent.Ready(msg)) }
            "done" -> ch?.let { channels[it]?.deliver(ChannelEvent.Done) }
            else -> ch?.let { channels[it]?.deliver(ChannelEvent.Control(msg)) }
        }
    }

    private fun onInit(msg: JsonObject) {
        val problem = msg.str("problem")
        if (problem != null) {
            initDone.completeExceptionally(CockpitException(problem, msg.str("message") ?: problem))
            return
        }
        if (msg.int("version") != 1) {
            initDone.completeExceptionally(CockpitException("not-supported", "Unsupported Cockpit protocol version"))
            return
        }
        msg.str("channel-seed")?.let { seed = it }
        defaultHost = msg.str("host")
        initMessage = msg
        sendControl(jsonOf("command" to "init", "version" to 1))
        initDone.complete(msg)
    }

    private fun lost(error: Throwable) {
        ws = null
        initDone.completeExceptionally(error)
        val problem = (error as? CockpitException)?.problem ?: "disconnected"
        val close = jsonOf("command" to "close", "problem" to problem, "message" to error.friendlyMessage())
        channels.values.toList().forEach { it.finish(close) }
        if (_state.value != State.Idle) _state.value = State.Failed(error)
    }

    /** Opens a channel; options are the Cockpit "open" fields (payload, spawn, path, superuser, ...). */
    fun open(vararg options: Pair<String, Any?>): CockpitChannel {
        val id = seed + counter.incrementAndGet()
        val ch = CockpitChannel(id, this)
        channels[id] = ch
        val opts = options.toMap()
        val host = if ("host" in opts) null else defaultHost
        val msg = jsonOf("command" to "open", "channel" to id, "host" to host, *options)
        if (!isReady || ws == null) {
            ch.finish(jsonOf("command" to "close", "problem" to "disconnected", "message" to "Not connected"))
        } else {
            sendControl(msg)
        }
        return ch
    }

    internal fun forget(id: String) {
        channels.remove(id)
    }

    internal fun sendFrame(channel: String, payload: String): Boolean = ws?.send(Frame.encode(channel, payload)) ?: false
    internal fun sendControl(message: JsonObject): Boolean = sendFrame("", message.toString())

    fun disconnect() {
        val w = ws
        ws = null
        if (w != null) {
            w.close(1000, "bye")
        }
        val close = jsonOf("command" to "close", "problem" to "disconnected", "message" to "Disconnected")
        channels.values.toList().forEach { it.finish(close) }
        _state.value = State.Idle
    }

    /** The cookie for WebViews (CookieManager wants "name=value; Path=/" per cookie). */
    fun webCookies(): List<String> = cookies.map { "$it; Path=/; Secure; HttpOnly" }

    /** Raw HTTP GET within the session, e.g. manifests.json. */
    suspend fun httpGet(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(endpoint.url(path)).header("Cookie", cookies.joinToString("; ")).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CockpitException(if (resp.code == 404) "not-found" else "internal-error", "HTTP ${resp.code} for $path")
            resp.body.string()
        }
    }
}
