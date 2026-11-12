package dev.normal99.cockpit

import dev.normal99.cockpit.claude.ClaudeRepo
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Endpoint
import dev.normal99.cockpit.cockpit.httpClientFor
import dev.normal99.cockpit.cockpit.spawn
import dev.normal99.cockpit.data.parseManifests
import dev.normal99.cockpit.system.SystemCommands
import dev.normal99.cockpit.system.parseUnits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real cockpit-ws. Run one without a password for testing:
 *   /usr/libexec/cockpit-ws --port 9999 --address 127.0.0.1 --local-session=/usr/bin/cockpit-bridge
 *   COCKPIT_E2E_URL=http://127.0.0.1:9999 ./gradlew testDebugUnitTest
 */
class EndToEndTest {
    private val url = System.getenv("COCKPIT_E2E_URL")

    @Test fun realCockpitWs() = runBlocking {
        assumeTrue("COCKPIT_E2E_URL not set", url != null)
        val s = CockpitSession(Endpoint(url!!), httpClientFor(null))
        s.connect(System.getenv("COCKPIT_E2E_USER") ?: "x", System.getenv("COCKPIT_E2E_PASSWORD") ?: "x", admin = true)
        assertTrue(s.spawn(listOf("hostname")).isNotBlank())
        assertTrue(parseUnits(s.spawn(SystemCommands.UNITS)).isNotEmpty())

        val (manifests, pages) = parseManifests(s.httpGet("cockpit/@localhost/manifests.json"))
        assertTrue(manifests.keys.toString(), "system" in manifests)
        assertTrue(pages.any { it.pkg == "system" })
        if (ClaudeRepo.isInstalled(manifests)) {
            assertTrue(pages.any { it.pkg == "claude" && it.label == "Claude" })
            val st = withTimeout(15_000) { ClaudeRepo(s).state.first { it.status != null && it.broker != null && it.storage != null } }
            println("Claude page: ${st.status!!.sessions.size} sessions, ${st.broker!!.open.size} open requests, disk ${st.storage!!.disk.pct}%, ${st.activity.size} activity lines")
        }
        s.disconnect()
    }
}
