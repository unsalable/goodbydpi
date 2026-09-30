package io.github.unsalable.goodbyedpi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HevConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun matchesHevNotesLiteral() {
        // HEV_NOTES.md 6'daki dosya, birebir (port 10808).
        val expected = """
            tunnel:
              mtu: 8500
              ipv4: 198.18.0.1
              ipv6: '2001:db8:6764:7069::1'
            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
            misc:
              task-stack-size: 28672
              tcp-buffer-size: 8192
              udp-copy-buffer-nums: 5
              udp-recv-buffer-size: 131072
              max-session-count: 1000
              connect-timeout: 5000
              tcp-read-write-timeout: 600000
              udp-read-write-timeout: 30000
              log-file: null
              log-level: warn

        """.trimIndent()
        assertEquals(expected, HevConfig.yaml(10808, ipv6 = true))
    }

    @Test
    fun ipv6OffOmitsTunnelIpv6() {
        val y = HevConfig.yaml(1080, ipv6 = false)
        assertFalse("ipv6" in y)
        assertTrue("  port: 1080\n" in y)
    }

    @Test
    fun debugLogFile() {
        val y = HevConfig.yaml(1080, ipv6 = true, logFile = "/data/user/0/x/files/hev.log")
        assertTrue("  log-file: '/data/user/0/x/files/hev.log'\n" in y)
        assertTrue("  log-level: info\n" in y)
        assertFalse("null" in y)
    }

    @Test
    fun forbiddenKeysNeverEmitted() {
        val y = HevConfig.yaml(1080, ipv6 = true, logFile = "/x")
        for (k in listOf("mark", "pid-file", "limit-nofile", "mapdns", "username", "password", "tcp-fastopen", "pipeline")) {
            assertFalse(k, k in y)
        }
        // Yalnizca duz skaler degerler: bolum basliklari disinda her satir "anahtar: deger".
        y.lines().filter { it.isNotEmpty() }.forEach { line ->
            if (!line.startsWith(" ")) assertTrue(line, line.endsWith(":")) else assertTrue(line, Regex("^  [a-z0-9-]+: \\S.*$").matches(line))
        }
        // hev: task-stack-size >= 20480 + max(tcp-buffer-size, 1500 * udp-copy-buffer-nums)
        assertTrue(HevConfig.TASK_STACK_SIZE >= 20480 + maxOf(HevConfig.TCP_BUFFER_SIZE, 1500 * HevConfig.UDP_COPY_BUFFER_NUMS))
        assertTrue(HevConfig.MAX_SESSION_COUNT < ByeDpiArgs.MAX_CONN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroPort() {
        HevConfig.yaml(0, ipv6 = true)
    }

    @Test
    fun atomicWriteAndLogRotation() {
        val f = File(tmp.root, "hev.yml")
        HevConfig.write(f, "a")
        HevConfig.write(f, "b")
        assertEquals("b", f.readText())
        assertFalse(File(f.path + ".tmp").exists())

        val log = File(tmp.root, "hev.log")
        log.writeBytes(ByteArray((HevConfig.LOG_ROTATE_BYTES + 1).toInt()))
        HevConfig.rotateLog(log)
        assertFalse(log.exists())
        assertTrue(File(log.path + ".1").exists())
        log.writeText("small")
        HevConfig.rotateLog(log)
        assertEquals("small", log.readText())
    }
}
