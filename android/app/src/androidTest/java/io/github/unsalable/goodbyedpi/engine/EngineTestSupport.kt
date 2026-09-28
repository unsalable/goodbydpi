package io.github.unsalable.goodbyedpi.engine

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.MethodPreset
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Enstrumantasyon testlerinin ortak yardimcilari (uygulama surecinde calisir). */
internal object EngineTestSupport {
    /**
     * Emulatorde sahte paketli yontemler slirp yuzunden baglantiyi bozuyor (SPEC 7); tesisat
     * testleri sahtesiz bir yontemle yapilir.
     */
    fun plainConfig(port: Int = 0, dns: DnsProfile = DnsProfile.Off): EngineConfig = EngineConfig(
        methodName = MethodPreset.PlainSplit.name,
        primary = MethodPreset.PlainSplit.build().copy(voiceFake = false),
        fallbacks = emptyList(),
        dns = dns,
        excludeLan = true,
        ipv6 = false,
        socksPort = port,
    )

    /** Acik fd sayisi: sizinti kontrolu. */
    fun fdCount(): Int = File("/proc/self/fd").list()?.size ?: -1

    /** "gdpi-byedpi" adli canli is parcacigi sayisi. */
    fun byedpiThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "gdpi-byedpi" && it.isAlive }

    /**
     * SOCKS5 vekil uzerinden elle HTTPS GET; durum satirini dondurur. HttpsURLConnection'in
     * vekil davranisina guvenmek yerine soket seviyesinde: alan adi byedpi'ye gider (-N yok).
     */
    fun httpsGetViaSocks(port: Int, host: String = "example.com", timeoutMs: Int = 15_000): String {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        Socket(proxy).use { raw ->
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            tls.use { s ->
                s.soTimeout = timeoutMs
                s.startHandshake()
                s.outputStream.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                return s.inputStream.bufferedReader().readLine() ?: ""
            }
        }
    }

    /** hev icin tun yerine SOCK_SEQPACKET soket cifti (HEV_NOTES dogrulamasindaki gibi). */
    class FakeTun : AutoCloseable {
        private val a = java.io.FileDescriptor()
        private val b = java.io.FileDescriptor()

        init {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, a, b)
        }

        /** Motora verilen uc (motor kapatir); her cagri yeni bir kopya. */
        fun engineEnd(): ParcelFileDescriptor = ParcelFileDescriptor.dup(a)

        /** Karsi ucu kapatmak hev'in okumasini EOF yapar: P3 ile hev ~1 sn'de kendisi durur. */
        fun closePeer() {
            runCatching { Os.close(b) }
        }

        override fun close() {
            runCatching { Os.close(a) }
            closePeer()
        }
    }
}
