package dev.normal99.cockpit.system

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SystemModelsTest {
    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectError(File("/dev/null")).start()
        p.inputStream.readBytes().toString(Charsets.UTF_8).also { p.waitFor() }
    }.getOrNull()

    private val stat = """
        cpu  100 0 50 800 50 0 0 0 0 0
        cpu0 100 0 50 800 50 0 0 0 0 0
        intr 12345 1 2
        ctxt 999
    """.trimIndent()
    private val meminfo = """
        MemTotal:       16000000 kB
        MemFree:         1000000 kB
        MemAvailable:   12000000 kB
        SwapTotal:       8000000 kB
        SwapFree:        8000000 kB
        HugePages_Total:       0
    """.trimIndent()
    private fun net(rx: Long, tx: Long) = """
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo: 5000 10 0 0 0 0 0 0 5000 10 0 0 0 0 0 0
          eth0: $rx 10 0 0 0 0 0 0 $tx 10 0 0 0 0 0 0
    """.trimIndent()

    @Test fun procSamplesAndRates() {
        val a = parseProcSample("$stat\n$meminfo\n0.50 0.40 0.30 1/200 1234\n3600.5 7000.1\n${net(1000, 500)}", 0)!!
        val b = parseProcSample(stat.replace("cpu  100 0 50 800 50", "cpu  200 0 100 850 50") + "\n$meminfo\n1.00 0.50 0.30 2/200 1300\n3602.5 7003.0\n${net(3000, 1500)}", 2000)!!
        assertEquals(1000L, a.netRx) // lo is left out
        val m = metricsBetween(a, b)
        assertEquals(75.0, m.cpuPct, 0.01) // 150 busy of 200 ticks
        assertEquals(4_000_000L * 1024, m.memUsed)
        assertEquals(1000.0, m.rxBps, 0.01)
        assertEquals(500.0, m.txBps, 0.01)
        assertEquals(1.0, m.load.first, 0.0)
        assertEquals(3602.5, m.uptimeS, 0.0)
    }

    @Test fun realProcSample() {
        val out = run("sh", "-c", "cat /proc/stat /proc/meminfo /proc/loadavg /proc/uptime /proc/net/dev")
        assumeTrue(out != null)
        val s = parseProcSample(out!!, 0)
        assertNotNull(s)
        assertTrue(s!!.memTotal > 0 && s.cpuTotal > 0 && s.uptimeS > 0)
    }

    @Test fun realSystemInfo() {
        val out = run(*SystemCommands.INFO.toTypedArray())
        assumeTrue(out != null)
        val i = parseSystemInfo(out!!)
        assertTrue(i.hostname.isNotEmpty())
        assertTrue(i.os, i.os.isNotEmpty())
        assertTrue(i.cores > 0)
    }

    @Test fun realDf() {
        val out = run(*SystemCommands.DF.toTypedArray())
        assumeTrue(out != null)
        val fs = parseDf(out!!)
        assertTrue(fs.isNotEmpty())
        assertEquals("/", fs.first { it.target == "/" }.target)
    }

    @Test fun realUnits() {
        val out = run(*SystemCommands.UNITS.toTypedArray())
        assumeTrue(out != null && out.startsWith("["))
        val units = parseUnits(out!!)
        assertTrue(units.any { it.name == "cockpit.socket" || it.name.endsWith(".service") })
        assertTrue(units.none { it.load == "not-found" })
        val files = parseUnitFiles(run(*SystemCommands.UNIT_FILES.toTypedArray()) ?: "[]")
        assertTrue(files.isEmpty() || files.values.any { it == "enabled" })
    }

    @Test fun journal() {
        val e = parseJournalLine("""{"__REALTIME_TIMESTAMP":"1794442028544389","PRIORITY":"3","SYSLOG_IDENTIFIER":"sshd","_SYSTEMD_UNIT":"sshd.service","MESSAGE":"oops","_PID":"12"}""")!!
        assertEquals(1794442028544L, e.timeMs)
        assertEquals(3, e.priority)
        assertEquals("sshd", e.identifier)
        assertEquals("oops", e.message)
        val bin = parseJournalLine("""{"__REALTIME_TIMESTAMP":"1","MESSAGE":[104,105]}""")!!
        assertEquals("hi", bin.message)
        val real = run("journalctl", "-o", "json", "-n", "5", "--no-pager")
        if (real != null) real.lines().filter { it.isNotBlank() }.forEach { assertNotNull(parseJournalLine(it)) }
    }

    @Test fun containers() {
        val c = parseContainers("""[{"Id":"abc","Names":["web"],"Image":"docker.io/nginx:latest","State":"running","Status":"Up 2 hours",
            "Ports":[{"host_ip":"","container_port":80,"host_port":8080,"range":1,"protocol":"tcp"}],"Created":1794440000}]""", system = false)
        assertEquals("web", c.single().name)
        assertTrue(c.single().running)
        assertEquals("8080→80/tcp", c.single().ports)
        assertEquals(emptyList<Container>(), parseContainers("[]", true))
    }

    @Test fun checkUpdate() {
        val u = parseCheckUpdate("""
            kernel.x86_64                       6.18.2-300.fc45        updates
            python3-libs.x86_64                 3.15.1-1.fc45          updates
            weird line
            Obsoleting packages
            foo.noarch 1-1 updates
        """.trimIndent())
        assertEquals(listOf("kernel", "python3-libs"), u.map { it.name })
        assertEquals("6.18.2-300.fc45", u[0].version)
        assertEquals("x86_64", u[0].arch)
    }
}
