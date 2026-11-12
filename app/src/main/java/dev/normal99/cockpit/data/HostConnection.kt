package dev.normal99.cockpit.data

import dev.normal99.cockpit.cockpit.AdminAccess
import dev.normal99.cockpit.cockpit.CockpitException
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Endpoint
import dev.normal99.cockpit.cockpit.UntrustedCertificateException
import dev.normal99.cockpit.cockpit.httpClientFor
import dev.normal99.cockpit.cockpit.int
import dev.normal99.cockpit.cockpit.obj
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.str
import dev.normal99.cockpit.cockpit.untrustedCertificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** A Cockpit page another package (or a mod) provides, from manifests.json "menu" and "tools". */
data class CockpitPage(val pkg: String, val name: String, val label: String, val order: Int, val section: String) {
    /** Relative to the Cockpit base URL; pages run standalone, outside the web shell. */
    val path get() = "cockpit/@localhost/$pkg/${if (name.endsWith(".html")) name else "$name.html"}"
}

fun parseManifests(text: String): Pair<Map<String, JsonObject>, List<CockpitPage>> {
    val all = parseObject(text) ?: return emptyMap<String, JsonObject>() to emptyList()
    val manifests = all.mapNotNull { (k, v) -> v.obj()?.let { k to it } }.toMap()
    val pages = manifests.flatMap { (pkg, m) ->
        listOf("menu", "tools").flatMap { section ->
            m[section].obj()?.mapNotNull { (name, e) ->
                val o = e.obj() ?: return@mapNotNull null
                CockpitPage(pkg, o.str("path") ?: name, o.str("label") ?: name, o.int("order") ?: 1000, section)
            } ?: emptyList()
        }
    }.sortedWith(compareBy({ it.section != "menu" }, { it.order }, { it.label }))
    return manifests to pages
}

/**
 * One host's live connection. Reconnects on its own after a dropped network (not after a bad password or an
 * untrusted certificate, which need the person).
 */
class HostConnection(val host: Host, private val passwordOf: (Host) -> String?, private val scope: CoroutineScope) {
    val session = CockpitSession(Endpoint(host.url), httpClientFor(host.pinnedSha256))
    val state: StateFlow<CockpitSession.State> get() = session.state

    private val _pages = MutableStateFlow<List<CockpitPage>>(emptyList())
    val pages: StateFlow<List<CockpitPage>> = _pages.asStateFlow()
    private val _manifests = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val manifests: StateFlow<Map<String, JsonObject>> = _manifests.asStateFlow()

    /** Cockpit's superuser state: "none" = limited access, "init" = starting, anything else = administrative access. */
    private val _admin = MutableStateFlow<String?>(null)
    val admin: StateFlow<String?> = _admin.asStateFlow()
    val hasAdmin get() = _admin.value.let { it != null && it != "none" && it != "init" }

    private val lock = Mutex()
    private var retryJob: Job? = null
    @Volatile var wanted = false
        private set

    suspend fun connect() = lock.withLock {
        wanted = true
        if (session.isReady) return@withLock
        val pw = passwordOf(host) ?: throw CockpitException("authentication-failed", "No password saved for ${host.name}")
        session.connect(host.user, pw, host.admin)
        scope.launch { refreshDetails() }
        watchForDrop()
    }

    private fun watchForDrop() {
        retryJob?.cancel()
        retryJob = scope.launch {
            session.state.collect { st ->
                if (st is CockpitSession.State.Failed && wanted && isTransient(st.error)) {
                    var wait = 2_000L
                    while (wanted && !session.isReady) {
                        delay(wait)
                        runCatching { connect() }
                        wait = (wait * 2).coerceAtMost(60_000)
                    }
                }
            }
        }
    }

    private fun isTransient(e: Throwable): Boolean {
        if (e.untrustedCertificate() != null) return false
        if (e is CockpitException && e.problem in setOf("authentication-failed", "no-cockpit", "not-supported", "access-denied")) return false
        return true
    }

    suspend fun refreshDetails() {
        runCatching {
            val (m, p) = parseManifests(session.httpGet("cockpit/@localhost/manifests.json"))
            _manifests.value = m
            _pages.value = p
        }
        refreshAdmin()
    }

    suspend fun refreshAdmin() {
        _admin.value = runCatching { AdminAccess(session, scope).current() }.getOrNull() ?: _admin.value
    }

    suspend fun setAdmin(on: Boolean) {
        val a = AdminAccess(session, scope)
        try {
            if (on) a.start(passwordOf(host) ?: "") else a.stop()
        } finally {
            refreshAdmin()
        }
    }

    fun disconnect() {
        wanted = false
        retryJob?.cancel()
        session.disconnect()
    }

    companion object {
        fun certificateProblem(e: Throwable): UntrustedCertificateException? = e.untrustedCertificate()
    }
}

/** Keeps one connection per host for the app's lifetime. */
class Connections(private val store: HostStore, private val scope: CoroutineScope) {
    private val map = HashMap<String, HostConnection>()

    @Synchronized
    fun get(host: Host): HostConnection {
        val existing = map[host.id]
        // A changed host (address, user, pin...) gets a fresh connection.
        if (existing != null && existing.host == host) return existing
        existing?.disconnect()
        return HostConnection(host, { store.password(it.id) }, scope).also { map[host.id] = it }
    }

    @Synchronized
    fun drop(id: String) {
        map.remove(id)?.disconnect()
    }

    @Synchronized
    fun pauseAll() = map.values.forEach { it.disconnect() }
}
