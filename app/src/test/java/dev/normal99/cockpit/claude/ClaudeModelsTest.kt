package dev.normal99.cockpit.claude

import dev.normal99.cockpit.cockpit.parseObject
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelsTest {
    private val oslo = ZoneId.of("Europe/Oslo")

    @Test fun times() {
        assertEquals(1794440520105L, parseTime(parseObject("""{"t":1794440520.105198}""")!!["t"]))
        assertEquals(1794440818000L, parseIso("2026-11-12T00:46:58+01:00"))
        // broker.json writes local time without an offset
        assertEquals(1794440818000L, parseIso("2026-11-12T00:46:58", oslo))
        assertEquals(1794440818000L, parseIso("2026-11-12 00:46:58", oslo))
    }

    @Test fun broker() {
        val b = Broker.parse(parseObject("""
            {"updated":"2026-11-12T00:42:40",
             "pending":[{"id":"r1a2b3c","argv":["dnf","install","-y","htop"],"reason":"need htop","cwd":"/home/claude/projects",
                         "status":"pending","tier":"ask","created":1794440520.1}],
             "approved_not_run":[],
             "recent":[{"id":"r000001","argv":["rm","/tmp/x"],"reason":"","cwd":"/tmp","status":"done","created":1794435710.1,
                        "decided_via":"cockpit","result":{"exit":0}},
                       {"id":"r1a2b3c","argv":["dnf"],"status":"pending","created":1}]}
        """.trimIndent())!!)
        assertEquals(1, b.open.size)
        val r = b.open.single()
        assertEquals("dnf install -y htop", r.command)
        assertTrue(r.isPending)
        assertEquals(listOf("r000001"), b.history.map { it.id })
        assertEquals(0, b.history.single().exit)
        assertEquals("cockpit", b.history.single().decidedVia)
    }

    @Test fun statusStorageRig() {
        val s = Status.parse(parseObject("""
            {"updated":"2026-11-12T00:43:38+01:00",
             "sessions":[{"window":"main","index":0,"up":true,"uptime_s":601,"restarts_today":2,"rc":"connected"},
                         {"window":"rc","up":false,"uptime_s":0,"restarts_today":0,"rc":"down"}],
             "branches":[{"branch":"claude/x","ahead":3,"behind":0,"last_commit":"2026-11-11T22:00:00+01:00","subject":"Fix"}],
             "events":[{"time":"2026-11-11T22:43:17+01:00","kind":"watchdog","text":"session missing"}]}
        """.trimIndent())!!)
        assertEquals(listOf("main", "rc"), s.sessions.map { it.window })
        assertEquals(2, s.sessions[0].restartsToday)
        assertEquals("down", s.sessions[1].rc)
        assertEquals(3, s.branches.single().ahead)

        val st = Storage.parse(parseObject("""
            {"updated":"2026-11-12T00:38:01+01:00","disk":{"size":125257646080,"used":4290109440,"avail":119113703424,"pct":4},
             "compression":"60%","cargo_target":1036697802,"podman":114889,"cache":2045317008,
             "snapshots":{"count":2,"exclusive_bytes":0,"latest":[{"number":2,"date":"2026-11-11 23:05:40","description":"acceptance test","cleanup":"number","type":"single"},
                                                                    {"number":1,"date":"2026-11-11 23:00:01","description":"","cleanup":"timeline","type":"single"}]}}
        """.trimIndent())!!)
        assertEquals(4, st.disk.pct)
        assertEquals(listOf("acceptance test", "timeline"), st.snapshots!!.latest.map { it.label })

        val rig = Rig.parse(parseObject("""
            {"updated":"2026-11-12T00:43:21+01:00","state":"up","tailscale":true,"apollo":true,"uptime_s":1175,"locked":false,
             "sessions":[{"window":"main","up":true,"rc":"connected"}],"moonlight_url":null,"wake_pending":false}
        """.trimIndent())!!)
        assertEquals("up", rig.state)
        assertEquals(null, rig.moonlightUrl)
        assertEquals(false, rig.locked)
    }

    @Test fun activityMergesLogsNewestFirst() {
        val st = ClaudeState(
            status = Status(null, emptyList(), emptyList(), listOf(StatusEvent(parseIso("2026-11-11T22:43:17+01:00"), "start", "main"))),
            logs = mapOf(
                "broker" to "2026-11-11T22:21:52\tauto\tdnf install -y htop\t0\n2026-11-11T23:22:06\tdecided\tr5309b6 approve via cli\t\n",
                "rig" to "2026-11-11T22:49:29+01:00\tstate\tstate off -> booting\n",
            ),
        )
        val a = st.activity
        assertEquals(4, a.size)
        assertEquals("root auto", a.last().kind)
        assertEquals("dnf install -y htop · exit 0", a.last().text)
        assertTrue(a.zipWithNext().all { (x, y) -> (x.time ?: 0) >= (y.time ?: 0) })
    }

    /** The live files of the machine running the tests, when it has the Claude page set up. */
    @Test fun parsesLiveFilesIfPresent() {
        for ((path, parse) in listOf<Pair<String, (kotlinx.serialization.json.JsonObject) -> Any>>(
            ClaudeFiles.STATUS to Status::parse, ClaudeFiles.BROKER to Broker::parse, ClaudeFiles.STORAGE to Storage::parse,
            ClaudeFiles.RIG to Rig::parse, ClaudeFiles.GITHUB to GitHub::parse,
        )) {
            val f = File(path)
            if (!f.canRead()) continue
            assertNotNull(path, parse(parseObject(f.readText())!!))
        }
    }
}
