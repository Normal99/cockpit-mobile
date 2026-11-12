package dev.normal99.cockpit.claude

import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Superuser
import dev.normal99.cockpit.cockpit.spawnResult
import dev.normal99.cockpit.cockpit.watchFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Live view of the Claude page's status files plus its claude-ctl actions, over one Cockpit session. */
class ClaudeRepo(private val session: CockpitSession) {

    private fun <T> json(path: String, parse: (kotlinx.serialization.json.JsonObject) -> T): Flow<T?> =
        session.watchFile(path)
            .map { text -> ClaudeState.parseOrNull(text)?.let { runCatching { parse(it) }.getOrNull() } }
            .onStart { emit(null) }
            .catch { emit(null) }

    private fun text(path: String): Flow<String> = session.watchFile(path).map { it ?: "" }.onStart { emit("") }.catch { emit("") }

    val state: Flow<ClaudeState> = combine(
        json(ClaudeFiles.STATUS, Status::parse),
        json(ClaudeFiles.GITHUB, GitHub::parse),
        json(ClaudeFiles.STORAGE, Storage::parse),
        json(ClaudeFiles.BROKER, Broker::parse),
        json(ClaudeFiles.RIG, Rig::parse),
    ) { status, github, storage, broker, rig -> ClaudeState(status, github, storage, broker, rig) }
        .combine(
            combine(ClaudeFiles.LOGS.map { (k, path) -> text(path).map { k to it } }) { it.toMap() },
        ) { s, logs -> s.copy(logs = logs) }

    /** Runs claude-ctl as root through Cockpit's admin access, like the web page. Returns its output or throws. */
    suspend fun ctl(vararg args: String): String {
        val r = session.spawnResult(listOf(ClaudeFiles.CTL) + args, Superuser.REQUIRE)
        return r.orThrow().trim()
    }

    suspend fun approve(id: String) = ctl("request", "approve", id)
    suspend fun deny(id: String) = ctl("request", "deny", id)
    suspend fun alwaysAllow(id: String) = ctl("request", "always", id)
    suspend fun reconnect(window: String) = ctl("reconnect", window)
    suspend fun restartWindow(window: String) = ctl("restart-window", window)
    suspend fun restartAll() = ctl("restart")
    suspend fun newSession() = ctl("new-session")
    suspend fun snapshot(description: String) = ctl("snapshot", description.ifBlank { "from Cockpit Mobile" })
    suspend fun sweep() = ctl("sweep")
    suspend fun prune() = ctl("prune")
    suspend fun rig(action: String) = ctl("rig", action)

    companion object {
        /** The Claude package is installed when Cockpit lists it in manifests.json. */
        fun isInstalled(manifests: Map<String, *>) = "claude" in manifests
    }
}
