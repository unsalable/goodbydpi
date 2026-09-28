package io.github.unsalable.goodbyedpi.engine

import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.fallbackMethods
import io.github.unsalable.goodbyedpi.model.selectedConfig
import io.github.unsalable.goodbyedpi.model.selectedDns
import io.github.unsalable.goodbyedpi.model.selectedMethod

// SOZLESME (wave 2): imzalar sabit; "runtime" ajani genisletebilir (alan ekleyebilir).

/** Motorun tek bir calismasi icin ayarlardan cozulmus, degismez yapilandirma. */
data class EngineConfig(
    val methodName: String,
    val primary: DpiConfig,
    /** Otomatik yedek yontemler (autoFallback kapaliysa bos). */
    val fallbacks: List<DpiConfig>,
    /** Kapali profilde isActive == false. */
    val dns: DnsProfile,
    val excludeLan: Boolean,
    val ipv6: Boolean,
    /** byedpi'nin dinleyecegi yerel port; 0 = motor bos port secer. */
    val socksPort: Int = 0,
) {
    /**
     * Sanal cozucunun (198.18.0.53) yonlendirilecegi hedef, "ip:port" ya da "[ip6]:port".
     * Profilde yalnizca IPv6 adres varsa IPv4 sanal cozucu ona yonlenir: byedpi aileler
     * arasi yonlendirmeyi destekliyor (BYEDPI_NOTES 6). null = DNS'e dokunulmaz.
     */
    val dnsTargetV4: String?
        get() {
            val v4 = dns.v4Addr?.trim()?.takeIf { it.isNotEmpty() }
            if (v4 != null) return "$v4:${portOf(dns.v4Port)}"
            val v6 = dns.v6Addr?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return "[$v6]:${portOf(dns.v6Port)}"
        }

    /**
     * IPv6 sanal cozucunun (fd00:6764:7069::53) hedefi. Yalnizca profilde IPv6 adres varsa ve
     * IPv6 tun uzerinden geciyorsa; aksi halde sistem IPv6 cozucuyu hic gormez.
     */
    val dnsTargetV6: String?
        get() {
            if (!ipv6) return null
            val v6 = dns.v6Addr?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return "[$v6]:${portOf(dns.v6Port)}"
        }

    /** DNS sanal cozucu uzerinden mi gidiyor (yoksa alttaki agin DNS'i mi kullaniliyor)? */
    val redirectsDns: Boolean
        get() = dnsTargetV4 != null

    val dnsName: String
        get() = dns.name

    /**
     * Motorun davranisini belirleyen her sey; iki yapilandirmanin "yeniden baslatmaya deger"
     * farkli olup olmadigini soyler. DnsProfile esitligi yalnizca kimlige baktigi icin (ozel
     * DNS'in adresi degisince esit kalir) data class esitligi burada yetmiyor; port da
     * disarida: her calismada motor yeni port seciyor.
     */
    fun runtimeKey(): List<Any?> = listOf(
        methodName,
        dns.name,
        excludeLan,
        ipv6,
        ByeDpiArgs.build(copy(socksPort = 0)),
    )

    companion object {
        fun from(settings: AppSettings): EngineConfig = EngineConfig(
            methodName = settings.selectedMethod().name,
            primary = settings.selectedConfig(),
            fallbacks = if (settings.autoFallback) {
                settings.fallbackMethods().map { it.build().sanitized() }
            } else {
                emptyList()
            },
            dns = settings.selectedDns(),
            excludeLan = settings.excludeLan,
            ipv6 = settings.ipv6,
        )

        // Ozel DNS'te 0 "varsayilan port" demek (DnsProfile.createCustom de ayni kurali uygular).
        private fun portOf(p: Int): Int = if (p in 1..65535) p else 53
    }
}
