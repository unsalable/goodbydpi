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
    }
}
