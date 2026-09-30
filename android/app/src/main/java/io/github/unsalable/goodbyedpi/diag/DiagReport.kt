package io.github.unsalable.goodbyedpi.diag

import io.github.unsalable.goodbyedpi.service.Ipv6Status
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

// Baglanti testi satirlari ve Tanilama raporu. Saf Kotlin (Android'siz JVM testleri calissin);
// cihazdan toplanan bilgiler DeviceInfoCollector'da.

/** Adres turu: raporda IP adresi yazilmaz, yalnizca turu (paylasilan metinde kimlik olmasin). */
object AddressKinds {
    fun kind(a: InetAddress): String {
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> when {
                b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) -> "IPv4 özel (LAN)"
                b[0] == 100 && b[1] in 64..127 -> "IPv4 CGNAT (100.64/10)"
                b[0] == 192 && b[1] == 0 && b[2] == 0 && b[3] < 8 -> "IPv4 464XLAT (192.0.0/29)"
                b[0] == 198 && b[1] in 18..19 -> "IPv4 sanal (198.18/15)"
                b[0] == 169 && b[1] == 254 -> "IPv4 link-local"
                b[0] == 127 -> "IPv4 loopback"
                else -> "IPv4 genel"
            }
            is Inet6Address -> when {
                b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8 -> "IPv6 belgeleme (2001:db8::/32)"
                b[0] and 0xE0 == 0x20 -> "IPv6 küresel"
                b[0] and 0xFE == 0xFC -> "IPv6 ULA (fc00::/7)"
                b[0] == 0xFE && b[1] and 0xC0 == 0x80 -> "IPv6 link-local"
                b[0] == 0xFE && b[1] and 0xC0 == 0xC0 -> "IPv6 site-local (fec0::/10)"
                a.isLoopbackAddress -> "IPv6 loopback"
                else -> "IPv6 diğer"
            }
            else -> "bilinmeyen"
        }
    }

    /** Turleri sayarak birlestirir: "IPv4 CGNAT (100.64/10), IPv6 küresel ×2, IPv6 link-local". */
    fun summarize(addrs: List<InetAddress>): String {
        if (addrs.isEmpty()) return "adres yok"
        return addrs.map(::kind).groupingBy { it }.eachCount().entries
            .joinToString(", ") { (k, n) -> if (n > 1) "$k ×$n" else k }
    }

    private val IPV4_RE = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val IPV6_RE = Regex("""(?<![\w:])[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}(?![\w:])""")

    /**
     * Ozel DNS sunucu adi, rapor icin. Kisisel profil adreslerinin (NextDNS, Control D, AdGuard
     * kisisel: "<profil-kimligi>.dns.nextdns.io") en soldaki etiketi hesap kimligidir; paylasilan
     * raporda o hesabi (bazi saglayicilarda kayitlarini) bulunur kilar. Uc ve daha fazla etiketli
     * adlarda en soldaki etiket bilinen genel bir sozcuk degilse "*" yazilir; saglayici (alan
     * adinin geri kalani) sorun ayirmak icin yeterli.
     */
    fun redactHostname(name: String): String {
        val labels = name.trim().trimEnd('.').split('.')
        if (labels.size < 3) return name.trim()
        val first = labels[0].lowercase()
        if (first in PUBLIC_LABELS) return name.trim()
        return (listOf("*") + labels.drop(1)).joinToString(".")
    }

    /** Genel (kimlik tasimayan) ilk etiketler: dns.adguard-dns.com, one.one.one.one, family... */
    private val PUBLIC_LABELS = setOf(
        "dns", "dns1", "dns2", "doh", "dot", "one", "family", "security", "adblock", "unfiltered",
        "base", "free", "public", "protected", "private", "common", "cloudflare-dns", "dns-unfiltered",
        "dns-family", "anycast", "kids", "standard", "default",
    )

    /** Serbest metindeki (hata mesaji vb.) IP adreslerini siler; rapor paylasiliyor. */
    fun redact(text: String): String =
        IPV6_RE.replace(IPV4_RE.replace(text, "<adres>")) { m ->
            // "12:30" gibi tek iki noktali saatler eslesmez ({2,}); en az bir onaltilik grup sart.
            if (m.value.any { it.isLetterOrDigit() }) "<adres>" else m.value
        }
}

/** Baglanti testi satirlarinin metni ve rengi (arayuz ve rapor ayni kurallari kullanir). */
object ConnTestText {
    enum class Tone { OK, BAD, MUTED }

    data class Part(val text: String, val tone: Tone)

    /**
     * "IPv4: ✓ 312 ms · IPv6: kullanılmıyor (ağda IPv6 yok)" parcalari. Ad hic cozulemediyse ya
     * da adres IP olarak yaziliysa bos (satir yalnizca genel sonucu gosterir).
     *
     * IPv6 hatasi: vekil uzerinden test edilirken tunel IPv6 sunuyorsa (ya da bu bilinmiyorsa)
     * uygulamalar da IPv6'yi deneyip ayni hatayi alir: kirmizi ✗. Tunel IPv6 sunmuyorsa uygulamalar
     * IPv6'yi hic denemez: "kullanılmıyor" ve nedeni, ✗ yok (IPv6'siz mobil veride her cift yiginli
     * satirda ✗ gormek kullaniciyi "bir sey bozuk" sanip IPv6'yi kapatmaya itiyordu; teknik hata
     * raporda ayrica yazili). Dogrudan testte (VPN kapali) sistem kendi Happy Eyeballs'iyla IPv4'e
     * duser: gri ✗.
     */
    fun parts(r: SiteResult, ipv6: Ipv6Status?, viaProxy: Boolean): List<Part> {
        val dns = r.dns ?: return emptyList()
        if (dns.source == DnsSource.LITERAL) return emptyList()
        val out = ArrayList<Part>()
        out += Part("IPv4: ", Tone.MUTED)
        out += family(r.v4, Tone.BAD, null)
        out += Part(" · IPv6: ", Tone.MUTED)
        out += if (r.v6 == null && systemMaySkipAaaa(dns, viaProxy)) {
            Part(V6_NOT_ASKED, Tone.MUTED)
        } else {
            val (v6Tone, v6Why) = v6FailureContext(ipv6, viaProxy)
            family(r.v6, v6Tone, v6Why)
        }
        return out
    }

    /**
     * Dogrudan testte sistem cozucusu AAAA dondurmedi. Android'in cozucusu agda IPv6 yoksa AAAA
     * hic sormuyor: IPv6'siz mobil veride Google icin bile 0 AAAA geliyor. "kayıt yok" demek
     * sitenin IPv6'si yok sanilmasina yol aciyordu (motor uzerinden ayni test 8 AAAA gosteriyor).
     */
    private fun systemMaySkipAaaa(dns: DnsInfo, viaProxy: Boolean): Boolean =
        !viaProxy && dns.source == DnsSource.SYSTEM && dns.aaaa == 0

    const val V6_NOT_ASKED = "bilinmiyor (ağda IPv6 yoksa sistem AAAA sormaz)"

    private fun family(f: FamilyResult?, failTone: Tone, why: String?): Part = when {
        f == null -> Part("kayıt yok", Tone.MUTED)
        f.ok -> Part("✓ ${f.millis ?: 0} ms", Tone.OK)
        failTone == Tone.MUTED && why != null -> Part("kullanılmıyor ($why)", Tone.MUTED)
        else -> Part("✗ ${f.error ?: ConnectionTester.ERR_CONNECT}" + (why?.let { " ($it)" } ?: ""), failTone)
    }

    internal fun v6FailureContext(ipv6: Ipv6Status?, viaProxy: Boolean): Pair<Tone, String?> = when {
        !viaProxy -> Tone.MUTED to null
        ipv6 == null || ipv6.tunV6 -> Tone.BAD to null
        !ipv6.setting -> Tone.MUTED to "IPv6 ayarı kapalı"
        !ipv6.underlyingGlobal || !ipv6.underlyingDefaultRoute -> Tone.MUTED to "ağda IPv6 yok"
        ipv6.probeOk == false -> Tone.MUTED to "ağın IPv6'sı çalışmıyor"
        else -> Tone.MUTED to "tünelde IPv6 kapalı"
    }

    /** Satirin genel isareti: birincil aile basarili ve kirmizi bir IPv6 hatasi yok. */
    fun rowOk(r: SiteResult, ipv6: Ipv6Status?, viaProxy: Boolean): Boolean =
        r.ok && parts(r, ipv6, viaProxy).none { it.tone == Tone.BAD }

    /** Rapor satirlari: sonuc, DNS ozeti, teknik hata ayrintisi ve robot dogrulamasi notu. */
    fun reportLines(r: SiteResult, ipv6: Ipv6Status?, viaProxy: Boolean): List<String> {
        val mark = if (rowOk(r, ipv6, viaProxy)) "✓" else "✗"
        val p = parts(r, ipv6, viaProxy)
        val lines = ArrayList<String>()
        lines += if (p.isEmpty()) {
            "$mark ${r.host} — " + (if (r.ok) "${r.millis ?: 0} ms" else r.error ?: ConnectionTester.ERR_CONNECT)
        } else {
            "$mark ${r.host} — " + p.joinToString("") { it.text }
        }
        r.dns?.takeIf { it.source != DnsSource.LITERAL }?.let { d ->
            val src = when {
                d.source == DnsSource.SELECTED -> "seçili DNS"
                systemMaySkipAaaa(d, viaProxy) -> "sistem çözücüsü; ağda IPv6 yoksa AAAA sorulmaz"
                else -> "sistem çözücüsü"
            }
            val http = listOfNotNull(
                r.v4?.httpStatus?.let { "IPv4 HTTP $it" },
                r.v6?.httpStatus?.let { "IPv6 HTTP $it" },
            )
            lines += "    DNS: ${d.a} A, ${d.aaaa} AAAA ($src)" + if (http.isEmpty()) "" else " · " + http.joinToString(", ")
        }
        if (r.dns == null && r.errorDetail != null) lines += "    hata: ${AddressKinds.redact(r.errorDetail)}"
        r.v4?.errorDetail?.let { lines += "    IPv4 hata: ${AddressKinds.redact(it)}" }
        r.v6?.errorDetail?.let { lines += "    IPv6 hata: ${AddressKinds.redact(it)}" }
        if (r.captcha) {
            lines += "    ${ConnectionTester.CAPTCHA_NOTE}"
        } else if (ConnectionTester.checkFor(r.host) == ConnectionTester.HttpCheck.GOOGLE_SEARCH &&
            (r.v4?.httpStatus != null || r.v6?.httpStatus != null)
        ) {
            // HTTP 200 robot dogrulamasi olmadiginin kaniti degil (bkz. NO_CAPTCHA_NOTE).
            lines += "    ${ConnectionTester.NO_CAPTCHA_NOTE}"
        }
        return lines
    }
}

/** Tanilama raporuna giren cihaz ve ag bilgisi (DeviceInfoCollector doldurur). */
data class DeviceInfo(
    val appVersion: String,
    val androidRelease: String,
    val sdk: Int,
    val model: String,
    /** [TRANSPORT_CELLULAR] / "Wi-Fi" / ...; bagli ag yoksa null */
    val transport: String?,
    /** TelephonyManager.networkOperatorName (SIM'in operatoru, etkin ag ne olursa olsun); bos ise null */
    val operator: String?,
    /** Bagli (alttaki) agin adres turleri; ag yoksa null */
    val underlyingAddresses: String?,
    /** Bagli agda IPv6 varsayilan yolu; ag yoksa null */
    val underlyingV6DefaultRoute: Boolean?,
    /** Ozel DNS durumu, Turkce */
    val privateDns: String,
    /** VPN aginin (tun) adres turleri; VPN yoksa null */
    val tunAddresses: String?,
) {
    companion object {
        const val TRANSPORT_CELLULAR = "Mobil veri"
    }
}

object DiagReport {
    /**
     * Kopyalanabilir tanilama metni. [engineText] motor bolumu (komut satiri, yontem, DNS,
     * saglayici); [settingsLine] diger ayarlar. IP adresi icermez (adres turleri yazilir); komut
     * satirindaki adresler uygulamanin kendi sanal adresleri ve secili DNS sunucusudur.
     */
    fun build(
        device: DeviceInfo,
        running: Boolean,
        ipv6: Ipv6Status?,
        settingsLine: String,
        engineText: String,
        tests: List<SiteResult>,
        testsViaProxy: Boolean,
        testRunning: Boolean,
        /** Test bittigi andaki IPv6 durumu (satir renkleri/nedenleri buna gore). */
        testsIpv6: Ipv6Status? = ipv6,
    ): String = buildString {
        appendLine("GoodbyeDPI ${device.appVersion} · Android ${device.androidRelease} (API ${device.sdk}) · ${device.model}")
        // Operator adi SIM'den gelir: Wi-Fi'deyken "operatör: Turkcell" yazmak sorunu ev
        // internetinin saglayicisi yerine mobil operatore yukletiyordu.
        val op = device.operator?.let {
            if (device.transport == DeviceInfo.TRANSPORT_CELLULAR) "operatör: $it" else "SIM operatörü: $it (etkin ağ değil)"
        }
        val net = listOfNotNull(device.transport ?: "bağlı ağ yok", op)
        appendLine("Ağ: " + net.joinToString(" · "))
        device.underlyingAddresses?.let { appendLine("Ağ adresleri: $it") }
        device.underlyingV6DefaultRoute?.let { appendLine("Ağda IPv6 varsayılan yol: " + if (it) "var" else "yok") }
        appendLine("Özel DNS: ${device.privateDns}")
        appendLine("Tünel adresleri: " + (device.tunAddresses ?: "tünel yok"))
        appendLine(ipv6Line(running, ipv6))
        appendLine(settingsLine)
        appendLine()
        appendLine(engineText.trimEnd())
        appendLine()
        when {
            testRunning -> appendLine("Bağlantı testi: sürüyor")
            tests.isEmpty() -> appendLine("Bağlantı testi: yapılmadı (Ayarlar → BAĞLANTI TESTİ)")
            else -> {
                appendLine("Bağlantı testi (" + (if (testsViaProxy) "motor üzerinden" else "doğrudan, VPN kapalı") + "):")
                tests.forEach { r -> ConnTestText.reportLines(r, testsIpv6, testsViaProxy).forEach { appendLine("  $it") } }
            }
        }
    }.trimEnd()

    internal fun ipv6Line(running: Boolean, s: Ipv6Status?): String {
        if (!running) return "IPv6 (tünel): bağlı değil"
        if (s == null) return "IPv6 (tünel): servis durum bildirmedi"
        val probe = when (s.probeOk) {
            null -> "yapılmadı"
            true -> "başarılı"
            false -> "başarısız"
        } + (s.probeDetail?.takeIf { it.isNotBlank() }?.let { " (${AddressKinds.redact(it.trim()).take(120)})" } ?: "")
        return "IPv6 (tünel): ayar " + onOff(s.setting) +
            " · ağda küresel IPv6 " + yesNo(s.underlyingGlobal) +
            " · IPv6 varsayılan yol " + yesNo(s.underlyingDefaultRoute) +
            " · erişim denemesi $probe" +
            " · tünelde IPv6 " + onOff(s.tunV6)
    }

    fun onOff(b: Boolean) = if (b) "açık" else "kapalı"
    private fun yesNo(b: Boolean) = if (b) "var" else "yok"
}
