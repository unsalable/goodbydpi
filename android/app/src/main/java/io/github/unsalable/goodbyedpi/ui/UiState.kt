package io.github.unsalable.goodbyedpi.ui

import android.content.Intent
import androidx.compose.runtime.Immutable
import io.github.unsalable.goodbyedpi.diag.SiteResult
import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.CustomIds
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.IspProfile
import io.github.unsalable.goodbyedpi.model.MethodPreset
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.model.dnsChoices
import io.github.unsalable.goodbyedpi.model.ispProfile
import io.github.unsalable.goodbyedpi.model.methodChoices
import io.github.unsalable.goodbyedpi.model.selectedDns
import io.github.unsalable.goodbyedpi.model.selectedMethod
import io.github.unsalable.goodbyedpi.service.EngineState

// Arayuzun okudugu durum nesneleri. Hepsi degismez: Compose bir alan degismedikce ilgili
// bileseni yeniden kurmaz. Listeler kendi sarmalayicilarinda; List arayuzu tek basina
// "kararsiz" sayilir ve her ayar degisiminde tum secici listeleri yeniden cizdirirdi.

/** Secici listelerindeki tek satir: ad + tek satirlik aciklama (masaustu acilir liste gibi). */
@Immutable
data class Choice(val id: String, val name: String, val description: String)

@Immutable
data class Choices(val items: List<Choice>) {
    val size: Int get() = items.size
}

/** Ayarlarin arayuze donusmus hali; ana ekran cipleri ve Ayarlar ekrani bunu okur. */
@Immutable
data class SettingsUi(
    val themeMode: ThemeMode,
    val isp: Choice,
    val isps: Choices,
    val method: Choice,
    val methods: Choices,
    /** Secili yontem bir ozel profilse onun kendisi (duzenleyici acilir), degilse null. */
    val customProfile: CustomMethodProfile?,
    val dns: Choice,
    val dnsList: Choices,
    /** Secili DNS bir ozel girisse onun kendisi, degilse null. */
    val customDns: CustomDnsEntry?,
    val autoConnect: Boolean,
    val startOnBoot: Boolean,
    val autoUpdate: Boolean,
    val autoFallback: Boolean,
    val excludeLan: Boolean,
    val ipv6: Boolean,
) {
    companion object {
        private val ispChoices = Choices(IspProfile.all.map { Choice(it.id, it.name, it.description) })

        fun from(s: AppSettings): SettingsUi {
            val isp = s.ispProfile()
            val method = s.selectedMethod()
            val dns = s.selectedDns()
            return SettingsUi(
                themeMode = s.themeMode,
                isp = Choice(isp.id, isp.name, isp.description),
                isps = ispChoices,
                method = method.toChoice(),
                methods = Choices(s.methodChoices().map { it.toChoice() }),
                customProfile = if (CustomIds.isCustom(method.id)) {
                    s.customProfiles.firstOrNull { it.id.equals(method.id, ignoreCase = true) }
                } else {
                    null
                },
                dns = dns.toChoice(),
                dnsList = Choices(s.dnsChoices().map { it.toChoice() }),
                customDns = if (CustomIds.isCustom(dns.id)) {
                    s.customDns.firstOrNull { it.id.equals(dns.id, ignoreCase = true) }
                } else {
                    null
                },
                autoConnect = s.autoConnect,
                startOnBoot = s.startOnBoot,
                autoUpdate = s.autoUpdate,
                autoFallback = s.autoFallback,
                excludeLan = s.excludeLan,
                ipv6 = s.ipv6,
            )
        }

        private val tr = java.util.Locale.forLanguageTag("tr")

        // Ozel profillerin aciklamasi teknik ozeti ("sahte paket (TTL 5) - ..."); cumle gibi
        // buyuk harfle baslasin. Turkce yerel ayar: "i" -> "I", "i" -> "I".
        private fun capital(s: String) = s.replaceFirstChar { it.titlecase(tr) }

        private fun MethodPreset.toChoice() = Choice(id, name, capital(description))
        private fun DnsProfile.toChoice() = Choice(id, name, capital(description))
    }
}

/** Guc dugmesinin dort gorsel durumu (masaustu IsIdle / IsConnecting / IsConnected / IsFailed). */
enum class PowerPhase { Off, Connecting, Connected, Failed }

@Immutable
data class ConnectionUi(
    val phase: PowerPhase,
    val title: String,
    val detail: String,
    /** Calisirken byedpi'nin yerel portu; baglanti testi bunun uzerinden gider. */
    val socksPort: Int?,
    /**
     * Kapanirken dugme bir sey yapmaz (MainViewModel.onPowerClick Stopping'i yok sayar);
     * erisilebilirlikte de "devre disi" gorunsun, "Baglaniyor" diye okunmasin.
     */
    val stopping: Boolean = false,
) {
    /** Guc dugmesinin erisilebilirlik durumu: ekrandaki baslikla ayni, uc noktasiz. */
    val stateLabel: String get() = title.trimEnd('…')

    companion object {
        const val HINT_OFF = "Bağlanmak için düğmeye dokun"

        fun from(state: EngineState, settings: AppSettings): ConnectionUi = when (state) {
            EngineState.Stopped -> ConnectionUi(PowerPhase.Off, "Kapalı", HINT_OFF, null)
            EngineState.Starting -> ConnectionUi(PowerPhase.Connecting, "Bağlanıyor…", "Motor başlatılıyor", null)
            EngineState.Stopping -> ConnectionUi(PowerPhase.Connecting, "Durduruluyor…", "Bağlantı kapatılıyor", null, stopping = true)
            is EngineState.Failed -> ConnectionUi(
                PowerPhase.Failed,
                "Bağlantı kurulamadı",
                state.message.ifBlank { "Bilinmeyen hata." },
                null,
            )
            is EngineState.Running -> {
                // Masaustu ProfileSummary gibi: saglayici (Genel degilse) - yontem - DNS.
                val isp = settings.ispProfile()
                val prefix = if (isp.id == IspProfile.GENERAL_ID) "" else isp.name + " · "
                ConnectionUi(
                    PowerPhase.Connected,
                    "Bağlı",
                    "$prefix${state.methodName} · DNS: ${state.dnsName}",
                    state.socksPort.takeIf { it > 0 },
                )
            }
        }
    }
}

/** Ayarlar > Baglanti testi. */
@Immutable
data class ConnTestUi(
    val running: Boolean,
    val results: List<SiteResult>,
    /** Son test calisan vekil uzerinden mi yapildi (yoksa dogrudan mi)? */
    val viaProxy: Boolean,
) {
    companion object {
        val Idle = ConnTestUi(running = false, results = emptyList(), viaProxy = false)
    }
}

/** Alt cubuk mesaji; [actionLabel] varsa yaninda bir dugme ("Geri al") cikar ve [onAction] calisir. */
class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** Aktivitenin yerine getirmesi gereken tek seferlik istekler. */
sealed interface UiEvent {
    /** VPN izni ekrani; RESULT_OK gelince MainViewModel.onVpnConsentResult(true). */
    data class RequestVpnConsent(val intent: Intent) : UiEvent

    /** Android 13+ bildirim izni (bir kez sorulur). */
    data object RequestNotificationPermission : UiEvent
}
