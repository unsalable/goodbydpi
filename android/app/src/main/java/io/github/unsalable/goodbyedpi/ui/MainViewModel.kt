package io.github.unsalable.goodbyedpi.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.diag.ConnectionTester
import io.github.unsalable.goodbyedpi.diag.DeviceInfoCollector
import io.github.unsalable.goodbyedpi.diag.DiagReport
import io.github.unsalable.goodbyedpi.diag.SiteResult
import io.github.unsalable.goodbyedpi.engine.ByeDpiArgs
import io.github.unsalable.goodbyedpi.engine.EngineConfig
import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.CustomIds
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.IspProfile
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.model.ispProfile
import io.github.unsalable.goodbyedpi.model.selectedDns
import io.github.unsalable.goodbyedpi.model.selectedMethod
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.service.EngineStateHolder
import io.github.unsalable.goodbyedpi.service.Ipv6Status
import io.github.unsalable.goodbyedpi.service.Ipv6StatusHolder
import io.github.unsalable.goodbyedpi.service.ServiceController
import io.github.unsalable.goodbyedpi.service.TrafficStats
import io.github.unsalable.goodbyedpi.update.UpdateManager
import io.github.unsalable.goodbyedpi.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ana ekran ve Ayarlar'in tek durum sahibi (masaustu MainViewModel'in karsiligi).
 *
 * Ayarlarin tamami SettingsRepository'de; burada yalnizca onun arayuze donusmus hali ve
 * kullanici eylemleri var. Tum yazmalar repository.update {} ile gider: calisan servis
 * ayarlari kendisi izleyip yeniden basliyor, arayuzun ayrica restart istemesi gerekmiyor.
 *
 * Kaynaklar yapicidan verilir ki testler kendi (temiz) ayar dosyalariyla calisabilsin.
 */
class MainViewModel(
    app: Application,
    private val repo: SettingsRepository,
    private val engineState: StateFlow<EngineState> = EngineStateHolder.state,
    /** Yalnizca kucuk trafik bileseni toplar; bkz. TrafficPanel. */
    val traffic: StateFlow<TrafficStats> = EngineStateHolder.traffic,
    val updateState: StateFlow<UpdateState> = UpdateManager.state,
    val justUpdatedTo: StateFlow<String?> = UpdateManager.justUpdatedTo,
    /** Tunelin IPv6 durumu (servis doldurur); baglanti testi ve tani raporu okur. */
    private val ipv6Status: StateFlow<Ipv6Status> = Ipv6StatusHolder.status,
) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    // Eagerly: ilk karede dogru deger olsun, secici listeleri bos baslayip "ziplamasin".
    val settings: StateFlow<SettingsUi> = repo.settings
        .map(SettingsUi::from)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi.from(repo.current))

    val connection: StateFlow<ConnectionUi> = combine(engineState, repo.settings, ConnectionUi::from)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionUi.from(engineState.value, repo.current))

    private val _connTest = MutableStateFlow(ConnTestUi.Idle)
    val connTest: StateFlow<ConnTestUi> = _connTest.asStateFlow()

    /** Suren baglanti testi; calisan motor degisince iptal edilir (sonucu eski motora ait olurdu). */
    private var connTestJob: Job? = null

    // Aktivitenin yapacagi isler (izin ekranlari). Tamponlu: aktivite o an durmus olsa bile
    // (ekran donerken) istek kaybolmaz, tekrar basladiginda islenir.
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    // Alt cubukta gosterilecek kisa mesajlar ("VPN izni verilmedi" gibi), istege bagli eylemle.
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var opened = false

    init {
        // Baglanti testi sonuclari o anki motora ait: VPN kapaninca, yeniden kurulunca ya da
        // yontem/DNS degisip motor yerinde guncellenince (port ayni kalir) eski satirlar yeni
        // yapilandirmanin sonucu gibi okunmasin; suren test de karisik sonuc vermesin.
        viewModelScope.launch {
            engineState.map(::connTestKey).distinctUntilChanged().drop(1).collect {
                connTestJob?.cancel()
                connTestJob = null
                _connTest.value = ConnTestUi.Idle
            }
        }
    }

    /**
     * VPN izin ekrani istendi, sonucu henuz gelmedi. Sistem izin penceresi yari saydam bir
     * aktivite: arkadaki ekran STARTED kalir ve ikinci bir dokunus ikinci bir izin penceresini
     * ustuste acardi. Sonuc (ya da acilamama) gelene kadar yeni baglanma istegi yok sayilir.
     */
    private var consentPending = false

    // ------------------------------------------------------------------ acilis

    /**
     * Uygulama her acildiginda bir kez (ekran donmesi sayilmaz; ViewModel ayakta kalir).
     * Guncelleme denetimi ve "Otomatik baglan".
     */
    fun onAppOpen() {
        if (opened) return
        opened = true

        runCatching { UpdateManager.onAppOpen(context) }
            .onFailure { Log.w(TAG, "Guncelleme denetimi baslatilamadi", it) }

        // Surec disaridan olduruldugunde (LMK, kill -9) sistem VPN servisini geri getirmiyor;
        // son istek "acik" ise uygulama acilir acilmaz baglantiyi geri kur.
        val recovered = runCatching { ServiceController.recoverIfNeeded(context) }
            .onFailure { Log.w(TAG, "Baglanti geri getirilemedi", it) }
            .getOrDefault(false)
        if (recovered) return

        // Izin daha once verilmediyse sessizce gecilir: acilista kullanicinin karsisina
        // bir izin ekrani cikarmak "otomatik" degil, dayatma olurdu.
        val s = repo.current
        if (s.autoConnect && engineState.value == EngineState.Stopped && hasVpnConsent()) {
            runCatching { ServiceController.start(context) }
                .onFailure { Log.w(TAG, "Otomatik baglanti baslatilamadi", it) }
        }
    }

    private fun hasVpnConsent(): Boolean =
        runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    // ------------------------------------------------------------ guc dugmesi

    fun onPowerClick() {
        when (engineState.value) {
            EngineState.Stopped, is EngineState.Failed -> connect()
            EngineState.Starting, is EngineState.Running -> ServiceController.stop(context)
            // Kapanirken ikinci dokunus yok sayilir; durum Stopped'a dusunce tekrar acilabilir.
            EngineState.Stopping -> Unit
        }
    }

    /**
     * Hizli ayar karosu VPN izni eksikken aktiviteyi ServiceController.EXTRA_CONNECT ile acar:
     * kullanici baglanmak istiyor, izni isteyip baglan. Zaten bagliysa bir sey yapma.
     */
    fun onConnectRequested() {
        when (engineState.value) {
            EngineState.Stopped, is EngineState.Failed -> connect()
            else -> Unit
        }
    }

    private fun connect() {
        if (consentPending) return
        val consent = try {
            ServiceController.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "Servis baslatilamadi", e)
            _messages.trySend(UiMessage("Bağlantı başlatılamadı."))
            return
        }
        if (consent != null) {
            // Bildirim izni burada sorulmaz: iki sistem penceresi ayni anda acilir, VPN izni
            // reddedilse bile ardindan alakasiz bir bildirim sorusu gelirdi. Izin verilince sorulur.
            consentPending = true
            _events.trySend(UiEvent.RequestVpnConsent(consent))
        } else {
            askNotificationsOnce()
        }
    }

    /** VPN izin ekranindan donus (ya da izin ekrani hic acilamadi: granted = false). */
    fun onVpnConsentResult(granted: Boolean) {
        consentPending = false
        if (!granted) {
            // Ustuste kalmis ikinci bir pencere iptal edildiyse baglanti zaten kurulmus olabilir;
            // o zaman "izin verilmedi" demek yanlis olur.
            val connected = engineState.value is EngineState.Running || engineState.value == EngineState.Starting
            if (!connected && !hasVpnConsent()) _messages.trySend(UiMessage("VPN izni verilmedi."))
            return
        }
        // Izin simdi var; ikinci cagri servisi baslatir (tekrar Intent donerse sistem izni
        // hala vermemis demektir, dongu kurmayalim).
        val again = runCatching { ServiceController.start(context) }.getOrNull()
        if (again != null) {
            _messages.trySend(UiMessage("VPN izni alınamadı."))
            return
        }
        askNotificationsOnce()
    }

    /**
     * Android 13+ bildirim izni: ilk baglanmada bir kez sorulur. Reddedilirse bir daha
     * sorulmaz; on plan servisi bildirimsiz de calisir, yalnizca durum bildirimi gorunmez.
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return

        val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit { putBoolean(KEY_NOTIF_ASKED, true) }
        _events.trySend(UiEvent.RequestNotificationPermission)
    }

    /** Bildirim izni verildi: servis calisiyorsa durum bildirimi simdi gorunsun. */
    fun onNotificationPermissionGranted() {
        runCatching { ServiceController.refreshNotification(context) }
            .onFailure { Log.w(TAG, "Durum bildirimi yenilenemedi", it) }
    }

    // ------------------------------------------------------------------ tema

    fun setTheme(mode: ThemeMode) = edit { it.copy(themeMode = mode) }

    /** Ust cubuktaki dugme: o an gorunenin tersine gecer (Sistem secimi Ayarlar'da). */
    fun toggleTheme(currentlyDark: Boolean) = setTheme(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)

    // ------------------------------------------------------------- secimler

    /**
     * Saglayici secilince onun onerdigi yontem ve DNS birlikte uygulanir (masaustu ile ayni);
     * kullanicinin kendi girdigi ozel DNS'e dokunulmaz.
     */
    fun selectIsp(id: String) = edit { s ->
        val isp = IspProfile.fromId(id)
        if (isp.id == s.isp) return@edit s
        s.copy(
            isp = isp.id,
            method = isp.recommendedId,
            dns = if (isp.dnsId != null && !CustomIds.isCustom(s.dns)) isp.dnsId else s.dns,
        )
    }

    fun selectMethod(id: String) = edit { it.copy(method = id) }

    fun selectDns(id: String) = edit { it.copy(dns = id) }

    // -------------------------------------------------------- ozel profiller

    /**
     * Secili yontemi kopyalayarak yeni bir ozel profil olusturur ve ona gecer. Ozel bir
     * profildeysek bu "cogalt" demek; hazir yontemden turetilen profil "(ozel)" ekiyle
     * adlanir ki listede asliyla karismasin. El degmemis "Ozel" yer tutucusu varsa yenisi
     * eklenmez, o kullanilir (AppSettings.withNewCustomProfile).
     */
    fun addCustomProfile() = edit { s ->
        val source = s.selectedMethod()
        val name = if (CustomIds.isCustom(source.id)) source.name else "${source.name} (özel)"
        s.withNewCustomProfile(source.build().sanitized(), name)
    }

    /** Silinen profil seciliydi: saglayicinin onerdigi yonteme don. */
    fun deleteCustomProfile(id: String) = edit { s ->
        s.copy(
            customProfiles = s.customProfiles.filterNot { it.id.equals(id, ignoreCase = true) },
            method = if (s.method.equals(id, ignoreCase = true)) s.ispProfile().recommendedId else s.method,
        )
    }

    /** Bos ad yazilirken kaydedilmez: kullanici eski adi silip yenisini yaziyor olabilir. */
    fun renameCustomProfile(id: String, name: String) {
        if (name.isBlank()) return
        editProfile(id) { it.copy(name = name.trim()) }
    }

    fun updateCustomConfig(id: String, transform: (DpiConfig) -> DpiConfig) =
        editProfile(id) { it.copy(config = transform(it.config)) }

    /**
     * "Onerilene don": profil, secili saglayicinin onerdigi yontemin degerlerine doner.
     * Tek dokunusla elle ayarlanmis bir profil silinmesin diye onceki degerler "Geri al" ile
     * geri getirilebilir.
     */
    fun resetCustomToRecommended(id: String) {
        val previous = repo.current.customProfiles.firstOrNull { it.id == id }?.config ?: return
        edit { s ->
            val cfg = s.ispProfile().recommended.build()
            s.copy(customProfiles = s.customProfiles.map { if (it.id == id) it.copy(config = cfg) else it })
        }
        _messages.trySend(
            UiMessage("Önerilen değerlere dönüldü.", actionLabel = "Geri al") {
                updateCustomConfig(id) { previous }
            },
        )
    }

    private fun editProfile(id: String, transform: (CustomMethodProfile) -> CustomMethodProfile) = edit { s ->
        s.copy(customProfiles = s.customProfiles.map { if (it.id == id) transform(it) else it })
    }

    // ----------------------------------------------------------- ozel DNS

    /** Yeni DNS girisi; ozel bir giristeysek onu kopyalar (yer tutucu kurali: AppSettings.withNewCustomDns). */
    fun addCustomDns() = edit { it.withNewCustomDns() }

    fun deleteCustomDns(id: String) = edit { s ->
        s.copy(
            customDns = s.customDns.filterNot { it.id.equals(id, ignoreCase = true) },
            dns = if (s.dns.equals(id, ignoreCase = true)) {
                s.ispProfile().dnsId ?: DnsProfile.DEFAULT_ID
            } else {
                s.dns
            },
        )
    }

    fun renameCustomDns(id: String, name: String) {
        if (name.isBlank()) return
        editDns(id) { it.copy(name = name.trim()) }
    }

    /** Yalnizca gecerli degerler kaydedilir; gecersiz yazim ekranda uyari olarak kalir. */
    fun updateCustomDns(id: String, v4: String, v4Port: Int, v6: String, v6Port: Int) =
        editDns(id) { it.copy(v4 = v4.trim(), v4Port = v4Port, v6 = v6.trim(), v6Port = v6Port) }

    private fun editDns(id: String, transform: (CustomDnsEntry) -> CustomDnsEntry) = edit { s ->
        s.copy(customDns = s.customDns.map { if (it.id == id) transform(it) else it })
    }

    // -------------------------------------------------------------- genel

    fun updateSettings(transform: (AppSettings) -> AppSettings) = edit(transform)

    private fun edit(transform: (AppSettings) -> AppSettings) {
        // Main.immediate: ardarda gelen dokunuslar sirasiyla kilide girer, biri digerini ezmez.
        viewModelScope.launch {
            try {
                repo.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Ayar kaydedilemedi", e)
            }
        }
    }

    // ------------------------------------------------------- baglanti testi

    fun runConnectionTest() {
        if (_connTest.value.running) return
        val port = connection.value.socksPort
        // Onceki calismanin satirlari "Test ediliyor" altinda bu calismanin sonucu gibi durmasin.
        _connTest.value = ConnTestUi(running = true, results = emptyList(), viaProxy = port != null)
        connTestJob = viewModelScope.launch {
            val results = try {
                withContext(Dispatchers.IO) { ConnectionTester.run(port) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Baglanti testi calismadi", e)
                ConnectionTester.DEFAULT_HOSTS.map { SiteResult(it, false, null, "Test çalıştırılamadı") }
            }
            _connTest.value = ConnTestUi(running = false, results = results, viaProxy = port != null, ipv6 = knownIpv6())
        }
    }

    /**
     * Servisin bildirdigi IPv6 durumu; servis hic bildirmediyse (ilk deger) null. Bilinmeyen durum
     * "tunel IPv6 sunuyor" gibi yorumlanir: IPv6 hatasi o zaman kirmizi gorunur, gizlenmez.
     * Motor calisiyorsa servis durumu mutlaka yazmistir (publish Running'den hemen sonra
     * publishIpv6 cagirir). IPv6'siz agda yazilan durum UNKNOWN'a esit cikar ve StateFlow esit
     * degeri degistirmedigi icin kimlik hala UNKNOWN kalir; bu yuzden yalniz kimlige bakmak tam da
     * duzeltmenin hedefi olan agda (mobil veri, IPv6 yok) bilinen durumu "bilinmiyor" sayiyordu.
     */
    private fun knownIpv6(): Ipv6Status? = ipv6Status.value.takeIf {
        it !== Ipv6Status.UNKNOWN || engineState.value is EngineState.Running
    }

    // ----------------------------------------------------------- guncelleme

    fun startUpdate() = UpdateManager.startUpdate(context)
    fun dismissUpdate() = UpdateManager.dismiss()
    fun checkForUpdates() = UpdateManager.checkNow(context)
    fun consumeJustUpdated() = UpdateManager.consumeJustUpdated()

    // ------------------------------------------------------------- tani

    /**
     * Hakkinda > Tanilama: sorun bildiriminde kopyalanan rapor. Cihaz/Android surumu, ag turu ve
     * operator, alttaki agin adres turleri (IP yazilmaz), ozel DNS, tunelin IPv6 durumu, ayarlar,
     * motorun komut satiri (calisiyorsa gercekten calisan; kapaliysa su anki ayarlarla
     * calistirilacak olan) ve son baglanti testinin aile bazli sonuclari. Google/YouTube gibi
     * sorunlar ancak bu bilgilerin hepsi bir aradayken tek raporda ayirt edilebiliyor.
     */
    fun diagnostics(): String {
        val state = engineState.value
        val s = repo.current
        val engine = try {
            diagnosticsText(state, s)
        } catch (e: Exception) {
            "Komut satırı oluşturulamadı: ${e.message}"
        }
        return try {
            val test = _connTest.value
            DiagReport.build(
                device = DeviceInfoCollector.collect(context),
                running = state is EngineState.Running,
                ipv6 = knownIpv6(),
                settingsLine = settingsLine(s),
                engineText = engine,
                tests = test.results,
                testsViaProxy = test.viaProxy,
                testRunning = test.running,
                testsIpv6 = test.ipv6,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tani raporu olusturulamadi", e)
            engine
        }
    }

    companion object {
        private const val TAG = "MainViewModel"

        /**
         * Baglanti testi sonuclarinin ait oldugu motor: calisan port + motor kimligi; motor
         * yoksa null. Yalnizca ad degisimi (profil adi) anahtari degistirmez.
         */
        internal fun connTestKey(state: EngineState): Pair<Int, Int>? =
            (state as? EngineState.Running)?.let { it.socksPort to it.generation }

        internal fun diagnosticsText(state: EngineState, s: AppSettings): String {
            val running = state as? EngineState.Running
            return buildString {
                if (running != null && running.argv.isNotEmpty()) {
                    appendLine("Çalışan komut:")
                    appendLine(formatArgv(running.argv))
                    appendLine()
                    appendLine("Yöntem: ${running.methodName}")
                    appendLine("DNS: ${running.dnsName}")
                } else {
                    appendLine(if (running != null) "Şu anki ayarlarla komut:" else "Bağlı değil; bağlanınca çalışacak komut:")
                    appendLine(ByeDpiArgs.describe(EngineConfig.from(s)))
                    appendLine()
                    appendLine("Yöntem: ${s.selectedMethod().name}")
                    appendLine("DNS: ${s.selectedDns().name}")
                }
                append("Sağlayıcı: ${s.ispProfile().name}")
            }
        }

        /** Motor bolumunde olmayan, soruna yon veren ayarlar (saglayici/yontem/DNS motor bolumunde). */
        internal fun settingsLine(s: AppSettings): String =
            "Ayarlar: akıllı mod ${DiagReport.onOff(s.smartMode)}" +
                " · otomatik yedek yöntem ${DiagReport.onOff(s.autoFallback)}" +
                " · yerel ağı hariç tut ${DiagReport.onOff(s.excludeLan)}" +
                " · IPv6 ${DiagReport.onOff(s.ipv6)}"

        /** ByeDpiArgs.describe ile ayni bicim: "ciadpi" + bosluk/tirnak iceren argumanlar tirnakli. */
        internal fun formatArgv(argv: List<String>): String {
            val args = if (argv.firstOrNull() == "ciadpi") argv.drop(1) else argv
            return "ciadpi " + args.joinToString(" ") { a ->
                if (a.isEmpty() || a.any { it == ' ' || it == '\'' || it == '"' }) "'" + a.replace("'", "'\\''") + "'" else a
            }
        }

        /** Ayar dosyasina girmeyen, yalnizca arayuze ait kucuk bayraklar. */
        internal const val UI_PREFS = "ui"
        internal const val KEY_NOTIF_ASKED = "notifAsked"

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                MainViewModel(app, SettingsRepository.get(app))
            }
        }
    }
}
