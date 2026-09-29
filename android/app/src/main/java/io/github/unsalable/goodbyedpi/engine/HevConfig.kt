package io.github.unsalable.goodbyedpi.engine

import java.io.File

/**
 * hev-socks5-tunnel YAML'i (android/docs/HEV_NOTES.md 5-6). Her baslatmada filesDir/hev.yml'e
 * yeniden yazilir.
 *
 * Degerler telefona gore: dusuk bellek ve sinirli fd. Oturum basina yigin 28672 bayt
 * (20480 + tcp-buffer-size; udp 5 x 1500 < 8192 oldugu icin hev bunu buyutmez), UDP oturumu
 * 30 sn bosta kalinca kapanir (her DNS sorgusu ayri bir UDP iliskisi aciyor, fd birikmesin),
 * TCP 10 dk (anlik bildirim baglantilari her 5 dk kopmasin).
 */
object HevConfig {
    /** Builder.setMtu ile AYNI olmali: hev dis fd'de mtu'yu okuma tamponu boyu olarak kullanir. */
    const val MTU = 8500
    const val TUN_IPV4 = "198.18.0.1"
    const val TUN_IPV6 = "fd00:6764:7069::1"

    const val TASK_STACK_SIZE = 28672
    const val TCP_BUFFER_SIZE = 8192
    const val UDP_COPY_BUFFER_NUMS = 5
    const val UDP_RECV_BUFFER_SIZE = 131072
    /** byedpi'nin -c 2048'inin altinda: her hev oturumu byedpi'de en az iki soket tutar. */
    const val MAX_SESSION_COUNT = 1000
    /** byedpi geri dongu adresinde; yavas baglanti byedpi'nin takildigi anlamina gelir. */
    const val CONNECT_TIMEOUT_MS = 5000
    const val TCP_RW_TIMEOUT_MS = 600000
    const val UDP_RW_TIMEOUT_MS = 30000

    /** hev.log bundan buyukse baslatmadan once hev.log.1 olur; hev dosyayi kendisi hic kirpmaz. */
    const val LOG_ROTATE_BYTES = 512L * 1024

    /**
     * @param logFile null: gunluk kapali (surum). Doluysa log-level info (hata ayiklama derlemesi).
     *
     * Yalnizca duz skaler degerler: hev bir bolumde skaler olmayan deger gorunce bolumun
     * kalanini sessizce atliyor. IPv6 metni tirnakli (':' YAML'da anlam tasir). tunnel.ipv4/ipv6
     * dis fd ile yok sayilir, dosya kendini belgelesin diye yazilir. socks5.udp 'udp' sart:
     * varsayilan 'tcp' byedpi'nin bilmedigi bir hev uzantisi. mark/pid-file/limit-nofile asla.
     */
    fun yaml(socksPort: Int, ipv6: Boolean, logFile: String? = null): String {
        require(socksPort in 1..65535) { "socksPort: $socksPort" }
        return buildString {
            append("tunnel:\n")
            append("  mtu: ").append(MTU).append('\n')
            append("  ipv4: ").append(TUN_IPV4).append('\n')
            if (ipv6) append("  ipv6: '").append(TUN_IPV6).append("'\n")
            append("socks5:\n")
            append("  address: 127.0.0.1\n")
            append("  port: ").append(socksPort).append('\n')
            append("  udp: 'udp'\n")
            append("misc:\n")
            append("  task-stack-size: ").append(TASK_STACK_SIZE).append('\n')
            append("  tcp-buffer-size: ").append(TCP_BUFFER_SIZE).append('\n')
            append("  udp-copy-buffer-nums: ").append(UDP_COPY_BUFFER_NUMS).append('\n')
            append("  udp-recv-buffer-size: ").append(UDP_RECV_BUFFER_SIZE).append('\n')
            append("  max-session-count: ").append(MAX_SESSION_COUNT).append('\n')
            append("  connect-timeout: ").append(CONNECT_TIMEOUT_MS).append('\n')
            append("  tcp-read-write-timeout: ").append(TCP_RW_TIMEOUT_MS).append('\n')
            append("  udp-read-write-timeout: ").append(UDP_RW_TIMEOUT_MS).append('\n')
            if (logFile == null) {
                append("  log-file: null\n")
                append("  log-level: warn\n")
            } else {
                append("  log-file: '").append(logFile.replace("'", "''")).append("'\n")
                append("  log-level: info\n")
            }
        }
    }

    /**
     * YAML'i atomik yazar (gecici dosya + rename): hev dosyayi iki kez okuyor (senkron dogrulama
     * ve is parcaciginda), yarim bir dosya gormemeli.
     */
    fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "hev.yml yazilamadi" }
        }
    }

    /** Hata ayiklama gunlugunu sinirda tutar; baslatmadan once cagrilir. */
    fun rotateLog(log: File) {
        if (log.length() > LOG_ROTATE_BYTES) {
            val old = File(log.path + ".1")
            old.delete()
            if (!log.renameTo(old)) log.delete()
        }
    }
}
