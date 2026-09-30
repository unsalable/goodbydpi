package io.github.unsalable.goodbyedpi.engine

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.FakePayload

// SOZLESME (wave 2): imzalar sabit. Esleme kurallari android/docs/BYEDPI_NOTES.md bolum 7'deki
// "final mapping" ile birebir; oradaki her secenek emulatorde denendi. Bir kurali degistiren
// once o dosyadaki grup/tetikleyici notlarini okumali: byedpi'de siralama davranisi belirliyor.

object ByeDpiArgs {
    /** Sanal cozucu adresleri (tun icinde); VpnService'e DNS olarak bunlar verilir. */
    const val VIRTUAL_DNS_V4 = "198.18.0.53"
    const val VIRTUAL_DNS_V6 = "fd00:6764:7069::53"

    // Bir baglanti ciftinin (istemci + sunucu soketi) ust siniri ve aktarim tamponu. hev
    // tarafindaki max-session-count (1000) bunun altinda kalir; byedpi hic dolmaz.
    const val MAX_CONN = 2048
    const val BUF_SIZE = 16384

    /**
     * Discord ses/STUN portlari. byedpi grup basina tek --pf araligi tutuyor (sonuncusu kazanir),
     * bu yuzden her aralik ayri bir UDP grubu.
     */
    val VOICE_PORT_RANGES = listOf("50000-65535", "3478-3481", "19294-19344")

    /**
     * Ses sahtelerinin TTL'i. Masaustu sahte UDP paketini asil paketin TTL'iyle yolluyor ve
     * Turk ISS'lerinde boyle calistigi dogrulandi; dusuk TTL yalnizca DPI'nin sahteyi hic
     * gormemesi riskini getirir. 64 Android'in varsayilani: kablodaki TTL degismez.
     */
    const val VOICE_TTL = 64

    /** Tum sifir sahte yuk; tek argv ogesi, kabuk yok: ters bolu byedpi'ye aynen gider. */
    const val ZERO_FAKE_DATA = ":\\x00\\x00\\x00\\x00"

    /** Yedek gruplari tetikleyen olaylar: RST/zaman asimi ve TLS'e ServerHello gelmemesi. */
    const val FALLBACK_TRIGGERS = "torst,ssl_err"

    /** Calisan yedek yontem hedef IP:port icin bir saat hatirlanir, sonra birincil yeniden denenir. */
    const val FALLBACK_CACHE_TTL = 3600

    /**
     * 4 sn icinde ClientHello onaylanmazsa torst (yedege gec). ":0:0:1": sunucu tek bayt
     * yollayinca zaman asimi kalkar; uzun omurlu baglantilar mobil takilmada kesilmesin.
     */
    const val FALLBACK_TIMEOUT = "4:0:0:1"

    /**
     * Sanal tun aglari (VpnRoutes'un disarida biraktigi bloklari kapsar). Bu aglara giden ve
     * bir --redirect FROM'u olmayan istekleri byedpi hemen reddeder (--deny-net, bizim
     * yamamiz): orn. Android'in Ozel DNS'i sanal cozucuyu 853'ten yokluyor; reddedilmezse
     * gercek aga anlamsiz bir SYN gider ve oturum zaman asimina kadar asili kalir. DNS
     * "Kapali" iken 198.18.0.53:53 de reddedilir; baglanti testi buna gore sistem
     * cozucusune duser. 2001:db8:6764:7069::/64 tun'un kuresel kapsamli IPv6 adresinin blogu
     * (HevConfig.TUN_IPV6): gercek bir hedef degil, disari SYN gitmesin.
     */
    val VIRTUAL_NETS = listOf("198.18.0.0/15", "fd00:6764:7069::/48", "2001:db8:6764:7069::/64")

    /**
     * Cekirdek TCP_MD5SIG destekliyor mu (true/false), bilinmiyorsa null. Android GKI
     * cekirdeklerinde yok: byedpi (yamali) o zaman sahteyi yalnizca TTL ile yollar ve
     * "MD5 imzasi" ile "Sabit TTL", "MD5 + TTL 3" ile "Sahte TTL 3" ayni seyi yapar. Bunu
     * bilince --md5sig hic verilmez: yedek zincirindeki kopyalar tekillesir ve tani ekrani
     * gercekte calisan komutu gosterir. Testler bu alani degistirir.
     */
    @Volatile
    internal var md5SigSupport: () -> Boolean? = { Md5SigProbe.result }

    /** byedpi argv'si (program adi haric). */
    fun build(config: EngineConfig): List<String> {
        val primary = config.primary
        val args = ArrayList<String>(64)

        // -i her zaman: varsayilan 0.0.0.0 telefonun Wi-Fi'sinda acik bir SOCKS vekili olurdu.
        // -N: alan adi cozumleme yok. hev her zaman IP gonderir; byedpi'nin tek is parcacikli
        // dongusunde engelleyen bir getaddrinfo tum trafigi ve durdurmayi bekletebilirdi
        // (loopback portunu bulan herhangi bir uygulama bile). Baglanti testi de ad degil IP
        // gonderir.
        args += listOf(
            "-i", "127.0.0.1",
            "-p", config.socksPort.toString(),
            "-c", MAX_CONN.toString(),
            "-b", BUF_SIZE.toString(),
            "-N",
        )

        config.dnsTargetV4?.let { args += listOf("--redirect", "$VIRTUAL_DNS_V4:53=$it") }
        config.dnsTargetV6?.let { args += listOf("--redirect", "[$VIRTUAL_DNS_V6]:53=$it") }
        for (net in VIRTUAL_NETS) args += listOf("--deny-net", net)

        // Port 443'e UDP (QUIC/HTTP3) sessizce dusurulur; tarayicilar TCP+TLS'e doner ve
        // asagidaki TCP gruplari devreye girer.
        if (primary.blockQuic) args += listOf("--drop-udp", "443")

        // Ses gruplari birincil TCP grubundan ONCE: birincil ile yedekleri arasina statik bir
        // grup girerse yedek zinciri orada biter. --auto=none yalnizca grup ayiracidir.
        if (primary.voiceFake) {
            for (range in VOICE_PORT_RANGES) {
                args += listOf(
                    "--proto=udp",
                    "--pf=$range",
                    "--udp-fake", primary.voiceFakeRepeats.toString(),
                    "--ttl", VOICE_TTL.toString(),
                    "--auto=none",
                )
            }
        }

        // Akilli mod: ilk (statik) TCP grubu hicbir sey yapmaz, secili yontem ilk yedek olur.
        // Neden: sahte paketli yontemler sabit ve dusuk TTL kullanir; ISS icindeki Google
        // onbellek sunuculari (GGC) ve CDN'ler 2-4 atlama uzakta olabildigi icin sahte oraya
        // ulasir, sunucu sahteye cevap verir ya da bozuk istegi reddeder ve engelsiz site
        // (Google aramasi) kirilir. Dogrudan grup engelsiz siteye hic dokunmaz; engel ilk turda
        // RST/zaman asimi (torst) ya da ServerHello gelmemesi (ssl_err) olarak gorulunce byedpi
        // ayni baglantida saklanan ilk istegi secili yontemle yeniden yollar (seffaf tekrar),
        // calisan grup IP:port icin --cache-ttl kadar hatirlanir. Sira kurali (BYEDPI_NOTES
        // 2.1.4) degismez: yedekler dogrudan grubun hemen ardinda, arada statik grup yok.
        val scope = scopeOf(primary)
        val smart = config.smartMode
        val firstGroup = if (smart) emptyList() else tcpGroup(primary)
        args += scope
        args += firstGroup

        // Yedekler ilk grubun hemen ardindan; ayni argv'yi uretenler (ilk grupla ya da
        // birbiriyle) tekrar eklenmez, bos yere bir tur daha denenmesin. Akilli modda secili
        // yontem zincirin basinda: otomatik yedek kapaliyken bile engelli sitede o denenir.
        val chain = if (smart) listOf(primary) + config.fallbacks else config.fallbacks
        var fallbackCount = 0
        val seen = HashSet<List<String>>()
        seen += firstGroup
        for (fb in chain) {
            val group = tcpGroup(fb)
            if (!seen.add(group)) continue
            args += "--auto=$FALLBACK_TRIGGERS"
            // Kapsam secili yontemden: HTTP ilk gruba hic girmiyorsa tetikleyici de uretmez,
            // yedeklerin farkli kapsam tasimasi yalnizca kafa karistirir.
            args += scope
            args += group
            args += listOf("--cache-ttl", FALLBACK_CACHE_TTL.toString())
            fallbackCount++
        }
        // Yedek yokken ETIMEDOUT yalnizca baglantiyi oldururdu; bu yuzden sadece yedekle.
        if (fallbackCount > 0) args += listOf("--timeout", FALLBACK_TIMEOUT)

        return args
    }

    /** Tani ekrani icin okunabilir tam komut satiri. */
    fun describe(config: EngineConfig): String =
        "ciadpi " + build(config).joinToString(" ") { quote(it) }

    /** TCP gruplarinin kapsami: TLS ClientHello, istenirse duz HTTP istekleri de. */
    internal fun scopeOf(config: DpiConfig): String =
        if (config.fragmentHttp) "--proto=tls,http" else "--proto=tls"

    /**
     * Bir DpiConfig'in TCP grubu (kapsam haric), BYEDPI_NOTES 7.1. Parcalar artan konum
     * sirasiyla: byedpi geride kalan bir konumu "split cancel" ile atlar.
     */
    internal fun tcpGroup(c: DpiConfig): List<String> {
        val parts = ArrayList<String>(16)
        if (c.splitTls) {
            // Ters sira = byedpi disorder: ilk parca TTL 1 ile gider, cekirdek sonra yeniden yollar.
            parts += listOf(if (c.reverseSplit) "--disorder" else "--split", c.splitPosition.toString())
            // +h: TLS'te SNI, HTTP'de Host ortasi; +s HTTP istegini iptal ederdi.
            if (c.splitSni) parts += listOf("--split", "0+hm")
        }
        if (c.fakePacket) {
            // Bolme varken sahteyi 2'den bolmek anlamsiz: bolme konumunun gerisinde kalir ve iptal olur.
            if (c.splitFake && !c.splitTls) parts += listOf("--fake", "2")
            parts += listOf("--fake", "-1")
            // TTL HER ZAMAN: byedpi sahteyi zaten dusuk TTL ile yollar (varsayilan 8). fakeTtl
            // kapaliyken de koruma TTL'e duser; MD5 tek basina GKI cekirdeginde yok ve sahte
            // sunucuya ulasip her baglantiyi bozardi.
            parts += listOf("--ttl", c.ttl.toString())
            // Cekirdek MD5 desteklemiyorsa (GKI) byedpi zaten yalnizca TTL kullanir; argv'yi de
            // oyle yaz ki ayni davranan gruplar yedek zincirinde tekillessin (DPI-6).
            if (c.fakeMd5Sig && md5SigSupport() != false) parts += "--md5sig"
            if (c.fakePayload == FakePayload.TLS) {
                // --fake-sni sahte ClientHello'yu gercek istegin boyuna da getirir.
                parts += listOf("--fake-sni", c.fakeSni)
            } else {
                parts += listOf("--fake-data", ZERO_FAKE_DATA)
            }
        }
        if (c.tlsRecordSplit) parts += listOf("--tlsrec", "3+s")
        return parts
    }

    // Yalnizca gosterim: argv ogeleri JNI'ye aynen gider, kabuk yok.
    private fun quote(s: String): String =
        if (s.isEmpty() || s.any { it == ' ' || it == '\'' || it == '"' }) "'" + s.replace("'", "'\\''") + "'" else s
}

/**
 * TCP_MD5SIG'i surecte bir kez yoklar. Cekirdegin setsockopt'u bu secenegi CONFIG_TCP_MD5SIG
 * yoksa hic tanimaz (ENOPROTOOPT); varsa 4 baytlik bir deger struct tcp_md5sig icin kisa
 * oldugu icin EINVAL doner. Baglanmamis bir soket yeter, ag trafigi yok. Baska her sonuc
 * (orn. seccomp/EPERM, JVM birim testindeki sahte android.jar) "bilinmiyor" sayilir ve
 * --md5sig eskisi gibi verilir; byedpi'nin ENOPROTOOPT yamasi o durumda da TTL'e duser.
 */
private object Md5SigProbe {
    // include/uapi/linux/tcp.h
    private const val TCP_MD5SIG = 14

    val result: Boolean? by lazy { probe() }

    // Os.setsockoptInt API 26'da geldi; 24-25'te yoklama yok, "bilinmiyor" (lint NewApi).
    // SDK_INT okumasi da try icinde: JVM birim testinin sahte android.jar'i firlatabilir.
    private fun probe(): Boolean? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) probeSockopt() else null
    } catch (ignored: Throwable) {
        null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun probeSockopt(): Boolean? = try {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_MD5SIG, 0)
            true
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.ENOPROTOOPT -> false
                OsConstants.EINVAL -> true
                else -> null
            }
        } finally {
            try {
                Os.close(fd)
            } catch (ignored: Throwable) {
            }
        }
    } catch (ignored: Throwable) {
        null
    }
}
