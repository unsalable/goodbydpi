package io.github.unsalable.goodbyedpi.diag

// SOZLESME (wave 2): imzalar sabit. STUB govde; "update" ajani doldurur.

data class SiteResult(
    val host: String,
    val ok: Boolean,
    /** TLS el sikismasi dahil sure; basarisizsa null */
    val millis: Long?,
    /** kisa Turkce hata ("Zaman aşımı", "Bağlantı sıfırlandı" ...); basariliysa null */
    val error: String?,
)

object ConnectionTester {
    val DEFAULT_HOSTS = listOf("discord.com", "roblox.com", "example.com")

    /**
     * Her siteye HTTPS istegi atar. [socksPort] verilirse istekler calisan byedpi
     * SOCKS5 vekili uzerinden gider (uygulamanin kendisi VPN'den haric tutuldugu icin
     * atlatmayi ancak boyle olcebiliriz); null ise dogrudan.
     */
    suspend fun run(socksPort: Int?, hosts: List<String> = DEFAULT_HOSTS): List<SiteResult> =
        hosts.map { SiteResult(it, false, null, "Henüz uygulanmadı") }
}
