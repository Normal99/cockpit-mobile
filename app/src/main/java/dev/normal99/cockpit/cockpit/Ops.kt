package dev.normal99.cockpit.cockpit

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Cockpit's superuser option: null = as the logged-in user, "try" = as root if admin access is on, "require" = root or fail. */
enum class Superuser(val value: String?) { NONE(null), TRY("try"), REQUIRE("require") }

data class SpawnResult(val output: String, val exitStatus: Int?, val message: String?, val problem: String?) {
    val ok get() = problem == null && (exitStatus ?: 0) == 0
    fun orThrow(): String {
        if (problem != null) throw CockpitException(problem, message ?: problem)
        if ((exitStatus ?: 0) != 0) throw CockpitException(null, message?.trim().orEmpty().ifEmpty { "Exited with status $exitStatus" }, exitStatus)
        return output
    }
}

/** Runs a command to completion, like cockpit.spawn(). Errors from stderr arrive in [SpawnResult.message]. */
suspend fun CockpitSession.spawnResult(
    args: List<String>,
    superuser: Superuser = Superuser.NONE,
    input: String? = null,
    environ: List<String> = emptyList(),
    directory: String? = null,
    timeoutMs: Long = 120_000,
): SpawnResult {
    val ch = open(
        "payload" to "stream", "spawn" to args, "err" to "message",
        "superuser" to superuser.value, "environ" to environ.ifEmpty { null }, "directory" to directory,
    )
    if (input != null) {
        ch.send(input)
        ch.done()
    }
    val out = StringBuilder()
    try {
        return withTimeout(timeoutMs) {
            for (ev in ch.events) {
                when (ev) {
                    is ChannelEvent.Data -> out.append(ev.text)
                    is ChannelEvent.Closed -> {
                        val problem = ev.problem
                        val status = ev.exitStatus ?: if (ev.exitSignal != null) 128 else null
                        return@withTimeout SpawnResult(out.toString(), status, ev.text ?: ev.exitSignal?.let { "Killed by $it" }, problem)
                    }
                    else -> Unit
                }
            }
            SpawnResult(out.toString(), null, "Disconnected", "disconnected")
        }
    } finally {
        ch.close()
    }
}

suspend fun CockpitSession.spawn(args: List<String>, superuser: Superuser = Superuser.NONE, input: String? = null): String =
    spawnResult(args, superuser, input).orThrow()

/** A long-running command (journalctl -f, a sampling loop) as a flow of output chunks. Cancelling kills the process. */
fun CockpitSession.spawnStream(args: List<String>, superuser: Superuser = Superuser.NONE): Flow<String> = channelFlow {
    val ch = open("payload" to "stream", "spawn" to args, "err" to "message", "superuser" to superuser.value)
    try {
        for (ev in ch.events) {
            when (ev) {
                is ChannelEvent.Data -> send(ev.text)
                is ChannelEvent.Closed -> {
                    ev.problem?.takeIf { it != "cancelled" }?.let { throw CockpitException(it, ev.text ?: it) }
                    if ((ev.exitStatus ?: 0) != 0) throw CockpitException(null, ev.text?.trim().orEmpty().ifEmpty { "Exited with status ${ev.exitStatus}" }, ev.exitStatus)
                }
                else -> Unit
            }
        }
    } finally {
        ch.close()
    }
}

/** Splits a chunk stream into whole lines. */
fun Flow<String>.lines(): Flow<String> = channelFlow {
    val buf = StringBuilder()
    collect { chunk ->
        buf.append(chunk)
        var nl = buf.indexOf("\n")
        while (nl >= 0) {
            send(buf.substring(0, nl))
            buf.delete(0, nl + 1)
            nl = buf.indexOf("\n")
        }
    }
    if (buf.isNotEmpty()) send(buf.toString())
}

/** Reads a file like cockpit.file().read(); null when it doesn't exist. */
suspend fun CockpitSession.readFile(path: String, superuser: Superuser = Superuser.NONE): String? {
    val ch = open("payload" to "fsread1", "path" to path, "superuser" to superuser.value, "max_read_size" to 16 * 1024 * 1024)
    val out = StringBuilder()
    try {
        for (ev in ch.events) {
            when (ev) {
                is ChannelEvent.Data -> out.append(ev.text)
                is ChannelEvent.Closed -> {
                    return when (ev.problem) {
                        null -> if (ev.message.str("tag") == "-") null else out.toString()
                        "not-found" -> null
                        else -> throw CockpitException(ev.problem, ev.text ?: ev.problem!!)
                    }
                }
                else -> Unit
            }
        }
    } finally {
        ch.close()
    }
    throw CockpitException("disconnected", "Disconnected")
}

/**
 * Live file contents like cockpit.file().watch(): emits the contents (null = missing) now and after each change.
 * Uses fswatch1, plus a slow poll as a fallback for watches that miss a replaced file (as the web plugin does).
 */
fun CockpitSession.watchFile(path: String, pollMs: Long = 30_000): Flow<String?> = channelFlow {
    val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    kick.trySend(Unit)
    val watch = open("payload" to "fswatch1", "path" to path)
    launch {
        for (ev in watch.events) {
            if (ev is ChannelEvent.Data) kick.trySend(Unit)
        }
    }
    launch {
        while (true) {
            delay(pollMs)
            kick.trySend(Unit)
        }
    }
    try {
        for (k in kick) {
            delay(150) // let a writer finish its rename
            val text = try {
                readFile(path)
            } catch (e: CockpitException) {
                if (e.problem == "disconnected") throw e else null
            }
            send(text)
        }
    } finally {
        watch.close()
    }
}.distinctUntilChanged()

/** A dbus-json3 channel: method calls with replies, plus signals for the matches added. */
class DBusClient(
    private val session: CockpitSession,
    bus: String,
    name: String?,
    superuser: Superuser = Superuser.NONE,
    scope: CoroutineScope,
) {
    private val ch = session.open("payload" to "dbus-json3", "bus" to bus, "name" to name, "superuser" to superuser.value)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonArray>>()
    private var nextId = 1
    private val _signals = MutableSharedFlow<JsonArray>(extraBufferCapacity = 64)
    val signals: SharedFlow<JsonArray> = _signals

    init {
        scope.launch(Dispatchers.Default) {
            for (ev in ch.events) {
                when (ev) {
                    is ChannelEvent.Data -> onMessage(parseObject(ev.text) ?: continue)
                    is ChannelEvent.Closed -> {
                        val err = CockpitException(ev.problem ?: "disconnected", ev.text ?: "D-Bus channel closed")
                        pending.values.forEach { it.completeExceptionally(err) }
                        pending.clear()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun onMessage(msg: JsonObject) {
        val id = msg.str("id")
        when {
            "reply" in msg -> id?.let { pending.remove(it)?.complete(msg["reply"].arr().firstOrNull().arr()) }
            "error" in msg -> id?.let {
                val e = msg["error"].arr()
                val name = e.getOrNull(0).prim()?.content
                val text = e.getOrNull(1).arr().firstOrNull().prim()?.content ?: name ?: "D-Bus error"
                val problem = when {
                    name?.contains("AccessDenied") == true || name?.contains("NotAuthorized") == true -> "access-denied"
                    else -> name
                }
                pending.remove(it)?.completeExceptionally(CockpitException(problem, text))
            }
            "signal" in msg -> _signals.tryEmit(msg["signal"].arr())
        }
    }

    suspend fun call(path: String, iface: String, method: String, args: List<Any?> = emptyList(), signature: String? = null, timeoutMs: Long = 60_000): JsonArray {
        val id: String
        synchronized(this) { id = (nextId++).toString() }
        val d = CompletableDeferred<JsonArray>()
        pending[id] = d
        val msg = jsonOf("call" to listOf(path, iface, method, toJson(args)), "id" to id, "type" to signature)
        if (ch.isClosed) throw CockpitException("disconnected", "Not connected")
        ch.sendJson(msg)
        try {
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        }
    }

    fun addMatch(path: String? = null, iface: String? = null, member: String? = null) {
        ch.sendJson(jsonOf("add-match" to jsonOf("path" to path, "interface" to iface, "member" to member)))
    }

    fun close() = ch.close()
}

/** Unwraps a D-Bus variant as dbus-json3 encodes it: {"t": "s", "v": ...}. */
fun JsonElement?.variantValue(): JsonElement? = (this as? JsonObject)?.get("v") ?: this

/**
 * Cockpit's admin access ("Limited access" / "Administrative access" in the web top bar), through the bridge's
 * cockpit.Superuser object on the internal bus.
 */
class AdminAccess(private val session: CockpitSession, private val scope: CoroutineScope) {
    /** "none", "init", "sudo", "root", ... */
    suspend fun current(): String {
        val bus = DBusClient(session, "internal", null, scope = scope)
        try {
            val r = bus.call("/superuser", "org.freedesktop.DBus.Properties", "Get", listOf("cockpit.Superuser", "Current"), "ss")
            return r.firstOrNull().variantValue().prim()?.content ?: "none"
        } finally {
            bus.close()
        }
    }

    /** Turns admin access on, answering sudo's password prompt with [password]. */
    suspend fun start(password: String) {
        val bus = DBusClient(session, "internal", null, scope = scope)
        try {
            bus.addMatch("/superuser", "cockpit.Superuser", "Prompt")
            var answered = 0
            val answerJob = scope.launch {
                bus.signals.collect { sig ->
                    if (sig.getOrNull(2).prim()?.content == "Prompt") {
                        // A second prompt means the password was wrong: give up rather than lock the account.
                        if (answered++ == 0) bus.call("/superuser", "cockpit.Superuser", "Answer", listOf(password), "s")
                        else bus.call("/superuser", "cockpit.Superuser", "Stop", emptyList())
                    }
                }
            }
            try {
                bus.call("/superuser", "cockpit.Superuser", "Start", listOf("any"), "s", timeoutMs = 60_000)
            } finally {
                answerJob.cancel()
            }
        } finally {
            bus.close()
        }
    }

    suspend fun stop() {
        val bus = DBusClient(session, "internal", null, scope = scope)
        try {
            bus.call("/superuser", "cockpit.Superuser", "Stop", emptyList())
        } finally {
            bus.close()
        }
    }
}
