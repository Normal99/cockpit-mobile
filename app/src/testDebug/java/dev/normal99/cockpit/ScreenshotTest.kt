package dev.normal99.cockpit

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.normal99.cockpit.data.Host
import dev.normal99.cockpit.data.HostConnection
import dev.normal99.cockpit.data.HostStore
import dev.normal99.cockpit.ui.CockpitTheme
import dev.normal99.cockpit.ui.ClaudeScreen
import dev.normal99.cockpit.ui.ContainersScreen
import dev.normal99.cockpit.ui.HostEditScreen
import dev.normal99.cockpit.ui.LogsScreen
import dev.normal99.cockpit.ui.OverviewScreen
import dev.normal99.cockpit.ui.PagesList
import dev.normal99.cockpit.ui.ServicesScreen
import dev.normal99.cockpit.ui.UpdatesScreen
import dev.normal99.cockpit.ui.rememberActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each screen against the in-app demo host into app/build/shots/ (phone size, light and dark). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var conn: HostConnection

    @Before fun setUp() {
        assumeTrue("set SHOTS=1 to render screenshots", System.getenv("SHOTS") == "1")
        Demo.ensureStarted()
        val host = Host(name = "demo-box", url = Demo.URL, user = "demo")
        conn = HostConnection(host, { "demo" }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        runBlocking {
            conn.connect()
            conn.refreshDetails()
        }
    }

    private fun shot(name: String, settleMs: Long = 4000, content: @Composable (PaddingValues) -> Unit) {
        compose.setContent {
            CockpitTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Scaffold { pad -> content(pad) }
                }
            }
        }
        // let channels answer (real threads) and the UI settle
        val end = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(100)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Composable private fun actions() = rememberActions(SnackbarHostState())

    /** The core flow: tap Approve, claude-ctl runs, broker.json changes, the watch fires, the request moves to Recent. */
    @Test fun approveMovesRequestToRecent() {
        shot("approve-before") { ClaudeScreen(conn, actions(), it) {} }
        val before = compose.onAllNodesWithText("Approve").fetchSemanticsNodes().size
        compose.onAllNodesWithText("Approve")[0].performClick()
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && compose.onAllNodesWithText("Approve").fetchSemanticsNodes().size >= before) {
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(100)
        }
        org.junit.Assert.assertEquals(before - 1, compose.onAllNodesWithText("Approve").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("done")[0].assertExists()
        compose.onRoot().captureRoboImage("build/shots/approve-after.png")
    }

    @Test fun claude() = shot("claude") { ClaudeScreen(conn, actions(), it) {} }
    @Config(qualifiers = "w393dp-h2400dp-xxhdpi")
    @Test fun claudeFull() = shot("claude-full") { ClaudeScreen(conn, actions(), it) {} }
    @Config(qualifiers = "+night")
    @Test fun claudeDark() = shot("claude-dark") { ClaudeScreen(conn, actions(), it) {} }
    @Test fun overview() = shot("overview", 9000) { OverviewScreen(conn, actions(), it) {} }
    @Test fun services() = shot("services") { ServicesScreen(conn, actions(), it) {} }
    @Test fun logs() = shot("logs") { LogsScreen(conn, null, it) }
    @Test fun containers() = shot("containers") { ContainersScreen(conn, actions(), it) }
    @Test fun updates() = shot("updates", 5000) { UpdatesScreen(conn, it) {} }
    @Test fun pages() = shot("pages") { PagesList(conn, it) {} }
    @Test fun addHost() = shot("add-host", 500) {
        HostEditScreen(HostStore(ApplicationProvider.getApplicationContext()), null, {}, null, onDemo = {})
    }

}
