package io.github.unsalable.goodbyedpi.update

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Testler icin en basit HTTP/1.1 sunucusu. com.sun.net.httpserver Android birim testlerinin
 * derleme yolunda yok (android.jar onyukleme sinif yolu), bu yuzden elle.
 */
class TinyHttpServer : Closeable {
    data class Response(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val routes = ConcurrentHashMap<String, Response>()

    /** Son istegin basliklari (anahtarlar kucuk harf). */
    @Volatile
    var lastHeaders: Map<String, String> = emptyMap()
        private set

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        lastHeaders = headers
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val r = routes[path] ?: Response(404, ByteArray(0))
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${r.code} X\r\n")
        head.append("Content-Length: ${r.body.size}\r\nConnection: close\r\n")
        r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        out.write(r.body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            buf.write(b)
        }
    }

    override fun close() = server.close()
}
