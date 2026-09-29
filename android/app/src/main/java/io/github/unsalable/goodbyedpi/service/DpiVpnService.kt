package io.github.unsalable.goodbyedpi.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.engine.DpiEngine
import io.github.unsalable.goodbyedpi.engine.EngineConfig
import io.github.unsalable.goodbyedpi.engine.VpnTunBuilder
import io.github.unsalable.goodbyedpi.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VPN on plan servisi: motoru (byedpi + hev) ayakta tutar.
 *
 * Is parcacigi modeli: onStartCommand ve sistem geri cagirilari ana is parcaciginda yalnizca is
 * kuyruga koyar ve hemen doner (ANR yok). Motorla ilgili her sey tek bir "gdpi-engine" is
 * parcaciginda sirayla calisir; bu yuzden motor durumuna kilitsiz dokunulur ve baslat/durdur
 * birbirine karismaz. Bunun icin eylemler ASKIYA ALINMAZ (ayar yazma dahil: persistWantRunning);
 * askiya alma kuyruktaki baska bir eylemi araya sokardi.
 *
 * Kararlilik: watchdog (byedpi/hev dusmesi -> 1/3/10 sn geri cekilmeyle yeniden baslatma, 5 dk'da
 * en fazla 5 deneme, sonra Failed + uyari), fiziksel ag geri cagirisi (setUnderlyingNetworks),
 * ayar degisince VPN agini dusurmeden yerinde guncelleme. Surec olumu: START_STICKY bu servis
 * icin API 36'da ise yaramiyor, kurtarma [Recovery] (bekci servisi + periyodik is).
 */
class DpiVpnService : VpnService() {
    private lateinit var executor: ExecutorService
    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var engine: DpiEngine
    private lateinit var tunBuilder: VpnTunBuilder

    private val policy = RestartPolicy()

    // Asagidakilerin hepsine yalnizca motor is parcacigindan dokunulur.
    private var retryJob: Job? = null
    private var netRestartJob: Job? = null

    /** Son kurulan tun'un sistemde gorunen hali (VpnTunBuilder.tunKey); degismedikce tun ayni kalir. */
    private var builtTunKey: List<Any>? = null

    // Ana is parcacigi (onStartCommand) ve motor is parcacigi yaziyor.
    @Volatile
    private var foreground = false

    /**
     * onStartCommand'in en son verdigi kimlik. fail/onRevoke stopSelf(bunu) cagirir: arada yeni
     * bir START geldiyse servis durmaz ve o START kendi isini yapar (RT-4).
     */
    @Volatile
    private var lastStartId = 0

    // Ag geri cagirisi yazar, motor is parcacigi okur.
    @Volatile
    private var underlying: Network? = null

    @Volatile
    private var underlyingDns: List<InetAddress> = emptyList()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** EngineStateHolder'a verilen sayac kaynagi; onDestroy yalnizca kendininkini geri alir. */
    private var statsSource: (() -> TrafficStats)? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gdpi-engine") }
        dispatcher = executor.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        settings = SettingsRepository.get(this)
        tunBuilder = VpnTunBuilder(this, Notifications.contentIntent(this))

        val hevLog = if (BuildConfig.DEBUG) File(filesDir, "hev.log") else null
        engine = DpiEngine(filesDir, { cfg ->
            val dns = underlyingDns
            tunBuilder.establish(cfg, systemUnderlying(), dns)?.also {
                builtTunKey = VpnTunBuilder.tunKey(cfg, dns)
            }
        }, hevLog)
        // byedpi is parcaciginda gelir; isi motor is parcacigina aktar.
        engine.exitListener = DpiEngine.ExitListener { reason -> scope.launch { onEngineDied(reason) } }
        statsSource = engine::stats
        EngineStateHolder.statsSource = statsSource

        registerNetworkCallback()
        observeSettings()
        startHealthLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+: startForegroundService'ten sonra birkac saniye icinde startForeground
        // sart; her yolda (STOP ve bos intent dahil) once bu.
        goForeground()
        // Her eylem icin (bos intent, RESTART, bilinmeyen dahil): hepsi bir startId harcar.
        lastStartId = startId

        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action startId=$startId")
        when (action) {
            ACTION_STOP -> scope.launch { stopByUser(startId) }
            ACTION_RESTART -> scope.launch { restart(intent.getBooleanExtra(EXTRA_FORCE, false), startId) }
            // Her zaman acik VPN sistemi SERVICE_INTERFACE ile baslatir: kullanici istegi sayilir.
            ACTION_START, SERVICE_INTERFACE -> scope.launch { startByUser() }
            // START_STICKY yeniden baslatmasi (surec olmeden servis durdurulduysa): son istege bak.
            null -> scope.launch { startFromSticky(startId) }
            else -> {
                Log.w(TAG, "bilinmeyen eylem: $action")
                scope.launch { stopSelfIfIdle(startId) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Kullanici VPN'i ayarlardan kapatti ya da baska bir VPN aldi. super.onRevoke() yalnizca
        // stopSelf cagiriyor; motoru once kapatmak icin kendimiz yapiyoruz.
        Log.i(TAG, "onRevoke")
        scope.launch {
            cancelPending()
            publish(EngineState.Stopping)
            engine.stop()
            persistWantRunning(false)
            Recovery.disarm(this@DpiVpnService)
            publish(EngineState.Stopped)
            leaveForeground()
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        unregisterNetworkCallback()
        scope.cancel()
        // Normalde motor burada zaten kapali; degilse (sistem servisi durdurdu) son is olarak
        // kapat. Ana is parcacigini beklemeden: executor kuyrugu bitirip kendisi kapanir.
        // Durum yalnizca gercekten bir motor kapattiysak yazilir: hizli kapat/ac'ta yeni servis
        // ornegi coktan Starting/Running yayinlamis olabilir, onu ezmeyelim.
        val source = statsSource
        executor.execute {
            if (engine.isRunning) {
                engine.stop()
                EngineStateHolder.set(EngineState.Stopped)
            }
            if (EngineStateHolder.statsSource === source) EngineStateHolder.statsSource = null
        }
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ eylemler (motor is parcacigi)

    private fun startByUser() {
        Notifications.cancelFailure(this)
        persistWantRunning(true)
        policy.reset()
        if (engine.isRunning && engine.checkHealth() == null) {
            // Zaten bagli (ornek: karo iki kez, her zaman acik + kullanici): yalnizca durumu
            // tazele. Arada bir STOP on plandan cikarmis olabilir: bildirimsiz VPN kalmasin.
            if (!foreground) goForeground()
            publishRunning()
            return
        }
        cancelPending()
        startEngine()
    }

    private fun startFromSticky(startId: Int) {
        if (engine.isRunning || retryJob?.isActive == true) return
        if (settings.current.wantRunning) {
            Log.i(TAG, "yapiskan yeniden baslatma: son istek acik, motor kuruluyor")
            policy.reset()
            startEngine()
        } else {
            stopSelfIfIdle(startId)
        }
    }

    private fun stopByUser(startId: Int) {
        cancelPending()
        if (engine.isRunning) {
            publish(EngineState.Stopping)
            engine.stop()
        }
        persistWantRunning(false)
        Recovery.disarm(this)
        publish(EngineState.Stopped)
        leaveForeground()
        // Bu STOP'tan sonra bir START geldiyse (kuyrukta) servis durmaz: stopSelf(startId).
        stopSelf(startId)
    }

    /**
     * Ayarlar ya da ag degisti (ya da zorla). Motor calismiyorsa ve istek aciksa baslatir; istek
     * kapaliysa servisi birakir. [force] yoksa motoru VPN agini dusurmeden yerinde gunceller
     * (DpiEngine.reconfigure): yalnizca degisen parca yenilenir, ayni yapilandirmada hicbir sey
     * yapilmaz (arayuz restartIfRunning cagirsa da ayar gozlemcisiyle cift is olmaz). [force]
     * tam yeniden kurulum.
     */
    private fun restart(force: Boolean, startId: Int? = null) {
        if (!engine.isRunning && retryJob?.isActive != true) {
            if (settings.current.wantRunning) {
                policy.reset()
                startEngine()
            } else if (startId != null) {
                stopSelfIfIdle(startId)
            }
            return
        }
        val cfg = EngineConfig.from(settings.current)
        val running = engine.runningConfig
        if (!force && running != null) {
            val rebuildTun = VpnTunBuilder.tunKey(cfg, underlyingDns) != builtTunKey
            if (!rebuildTun && running.sameEngineAs(cfg)) {
                // Yalnizca adlar degistiyse: motora dokunmadan durum/bildirim metni (C4).
                if (running.methodName != cfg.methodName || running.dnsName != cfg.dnsName) {
                    engine.reconfigure(cfg, rebuildTun = false)
                    publishRunning()
                }
                return
            }
            Log.i(TAG, "yerinde guncelleniyor (tun ${if (rebuildTun) "yeni" else "ayni"})")
            cancelPending()
            policy.reset()
            try {
                engine.reconfigure(cfg, rebuildTun)
                publishRunning()
            } catch (e: DpiEngine.StartException) {
                // Motor tamamen durdu (yarim motor yok); normal yeniden deneme yolu.
                Log.w(TAG, "yerinde guncelleme basarisiz: ${e.message}", e.cause)
                if (e.retryable) scheduleRetry(e.message ?: "") else fail(e.message ?: "")
            } catch (t: Throwable) {
                Log.e(TAG, "yerinde guncelleme beklenmedik hata", t)
                engine.stop()
                scheduleRetry("Motor başlatılamadı (${t.javaClass.simpleName}).")
            }
            return
        }
        Log.i(TAG, "yeniden baslatiliyor (force=$force)")
        cancelPending()
        policy.reset()
        publish(EngineState.Starting)
        engine.stop()
        startEngine()
    }

    private fun startEngine() {
        // Hizli kapat/ac: STOP isi on plandan cikmis ama sonraki START servisi durdurmamis olabilir.
        if (!foreground) goForeground()
        publish(EngineState.Starting)
        // Izin geri alinmissa establish null doner; onceden bakmak gereksiz bir byedpi baslatmasini onler.
        if (VpnService.prepare(this) != null) {
            fail("VPN izni yok")
            return
        }
        val cfg = EngineConfig.from(settings.current)
        try {
            engine.start(cfg)
            publishRunning()
            // Surec olurse arayuz acilmadan geri gelsin (bekci + periyodik is).
            runCatching { Recovery.arm(this) }.onFailure { Log.w(TAG, "kurtarma kurulamadi", it) }
            // Guncelleme denetimi kendi sinirini (6 sa) tutuyor; motoru asla dusurmemeli.
            runCatching { UpdateManager.backgroundCheck(applicationContext) }
                .onFailure { Log.w(TAG, "backgroundCheck", it) }
        } catch (e: DpiEngine.StartException) {
            Log.w(TAG, "baslatma basarisiz: ${e.message}", e.cause)
            if (e.retryable) scheduleRetry(e.message ?: "") else fail(e.message ?: "")
        } catch (t: Throwable) {
            // Beklenmedik (programlama) hatasi: servis cokmesin, yeniden denensin.
            Log.e(TAG, "baslatma beklenmedik hata", t)
            engine.stop()
            scheduleRetry("Motor başlatılamadı (${t.javaClass.simpleName}).")
        }
    }

    private fun onEngineDied(reason: String) {
        if (!engine.isRunning) return
        Log.w(TAG, "motor dustu: $reason")
        engine.stop()
        scheduleRetry(reason)
    }

    private fun scheduleRetry(reason: String) {
        val wait = policy.next(SystemClock.elapsedRealtime())
        if (wait == null) {
            fail("$reason Otomatik yeniden bağlanma 5 denemede başarısız oldu.")
            return
        }
        Log.i(TAG, "watchdog: $wait ms sonra yeniden denenecek")
        publish(EngineState.Starting)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            startEngine()
        }
    }

    /**
     * Kalici hata: motor kapali, Failed, uyari; servis durur ama wantRunning korunur (arayuz
     * acilinca tekrar). Arka plan kurtarmasi kaldirilir: kullanici bir sey yapmadan her
     * surec olumunde ayni hatayi tekrar tekrar gostermesin.
     */
    private fun fail(message: String) {
        engine.stop()
        publish(EngineState.Failed(message))
        Notifications.showFailure(this, message)
        Recovery.disarm(this)
        leaveForeground()
        stopSelf(lastStartId)
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (engine.isRunning || retryJob?.isActive == true) return
        if (EngineStateHolder.state.value !is EngineState.Failed) publish(EngineState.Stopped)
        leaveForeground()
        stopSelf(startId)
    }

    /**
     * Son istegi yazar. Askiya ALMAZ (RT-3): eskiden settings.update burada askiya aliniyor ve
     * tek is parcacikli kuyruk o arada baska bir eylemi (ornek: STOP sirasinda START) calistirip
     * motor durumunu karistiriyordu. runBlocking is parcacigini bloklar ama kuyruktaki baska bir
     * eylemi araya sokmaz; bellekteki deger CAS ile hemen degisir, dosya da donmeden yazilir
     * (surec hemen olse bile istek kaybolmasin: kurtarma buna bakiyor).
     */
    private fun persistWantRunning(want: Boolean) {
        runBlocking { settings.update { it.copy(wantRunning = want) } }
    }

    private fun cancelPending() {
        retryJob?.cancel()
        retryJob = null
        netRestartJob?.cancel()
        netRestartJob = null
    }

    // ------------------------------------------------------------------ durum ve bildirim

    private fun publishRunning() {
        val cfg = engine.runningConfig ?: return
        publish(EngineState.Running(engine.sinceElapsed, cfg.methodName, cfg.dnsName, cfg.socksPort, engine.runningArgv))
    }

    private fun publish(state: EngineState) {
        if (EngineStateHolder.state.value != state) Log.i(TAG, "durum: ${describe(state)}")
        EngineStateHolder.set(state)
        if (foreground && (state is EngineState.Running || state is EngineState.Starting || state is EngineState.Stopping)) {
            Notifications.updateStatus(this, state)
        }
        // Karo "aktif karo" degil: panel her acildiginda onStartListening ile durumu kendisi
        // okuyor, burada ayrica haber vermeye gerek yok.
    }

    // argv uzun; gunluge yalnizca ozet (argv hata ayiklama derlemesinde DpiEngine'de yaziliyor).
    private fun describe(state: EngineState): String =
        if (state is EngineState.Running) {
            "Running(${state.methodName}, ${state.dnsName}, port ${state.socksPort}, argv ${state.argv.size} oge)"
        } else {
            state.toString()
        }

    private fun goForeground() {
        val n = Notifications.status(this, EngineStateHolder.state.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.STATUS_ID, n)
            }
            foreground = true
        } catch (e: Exception) {
            // Android 12+ arka plandan baslatma yasagi vb. VPN yine kurulabilir; sistem
            // servisi daha kolay oldurur ama cokmekten iyidir.
            Log.e(TAG, "startForeground basarisiz", e)
        }
    }

    private fun leaveForeground() {
        foreground = false
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------ gozlemciler

    /**
     * Ayarlar degisince (yontem, DNS, yerel ag, IPv6, yedekler) motoru yeni ayarla yerinde
     * gunceller. 400 ms debounce: kullanici adimlayiciyi hizla tiklarken her adimda
     * guncellenmesin. Yalnizca ad degisimi (profil adi, DNS adi) motora dokunmaz (C4).
     */
    @OptIn(FlowPreview::class)
    private fun observeSettings() {
        scope.launch {
            settings.settings
                .map { EngineConfig.from(it) }
                .distinctUntilChanged { a, b ->
                    a.methodName == b.methodName && a.dnsName == b.dnsName && a.sameEngineAs(b)
                }
                .debounce(SETTINGS_DEBOUNCE_MS)
                .collect { cfg ->
                    val running = engine.runningConfig ?: return@collect
                    if (!running.sameEngineAs(cfg) || running.methodName != cfg.methodName || running.dnsName != cfg.dnsName) {
                        Log.i(TAG, "ayarlar degisti")
                        restart(force = false)
                    }
                }
        }
    }

    /**
     * hev kendi kendine durursa (tun kapandi/okuma hatasi) bunu bildiren bir geri cagiri yok;
     * seyrek ve ucuz bir yoklama (atomik okuma) yeterli. byedpi dusmesi zaten aninda geliyor.
     */
    private fun startHealthLoop() {
        scope.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                checkHealth()
            }
        }
    }

    private fun checkHealth() {
        engine.checkHealth()?.let { onEngineDied(it) }
    }

    /**
     * Fiziksel (VPN olmayan) varsayilan agi izler; her zaman TEK bir "gecerli" ag tutulur.
     * - API 31+: registerBestMatchingNetworkCallback(INTERNET + NOT_VPN), yani sistemin sectigi
     *   fiziksel ag; setUnderlyingNetworks ona ayarlanir. registerDefaultNetworkCallback burada
     *   ise yaramiyor: S+'ta uygulama basina varsayilan ag var ve VPN'in sahibine VPN'in
     *   kendisi bildiriliyor (emulatorde goruldu).
     * - Daha eski: registerDefaultNetworkCallback, S oncesinde sistemin varsayilan agini (asla
     *   VPN degil) izler; DNS "Kapali" iken sunucular ONDAN alinir. Eski kod tum eslesen aglari
     *   dinleyip en son geleni seciyordu: arka planda acik mobil veri Wi-Fi varken operator
     *   DNS'ini getiriyor ve her hucresel titremede VPN'i yeniden kuruyordu (RT-2).
     *   setUnderlyingNetworks(null) = "sistemin varsayilan agi", sistem kendisi takip eder.
     * Geri cagirilar yalnizca alan yazip isi motor is parcacigina aktarir.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val bestMatching = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun publishCurrent(network: Network?, dns: List<InetAddress>) {
            underlying = network
            underlyingDns = dns
            applyUnderlying()
            scope.launch { onNetworkChanged() }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Emniyet: S oncesi varsayilan geri cagiri kendi VPN'imizi asla vermemeli; verirse
                // sanal DNS'i "alttaki agin DNS'i" sanmayalim.
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                Log.i(TAG, "fiziksel ag: $network")
                publishCurrent(network, cm.getLinkProperties(network)?.dnsServers.orEmpty())
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != underlying) return
                val dns = lp.dnsServers.orEmpty()
                if (dns == underlyingDns) return
                publishCurrent(network, dns)
            }

            override fun onLost(network: Network) {
                if (network != underlying) return
                Log.i(TAG, "ag kayboldu: $network")
                publishCurrent(null, emptyList())
            }
        }
        try {
            if (bestMatching) {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerBestMatchingNetworkCallback(request, cb, Handler(Looper.getMainLooper()))
            } else {
                // Handler'li surum API 26+; bu surumde geri cagirilar tek bir sistem is
                // parcaciginda sirayla gelir.
                cm.registerDefaultNetworkCallback(cb)
            }
            networkCallback = cb
        } catch (e: Exception) {
            // Geri cagiri sinirina (100/uygulama) takilmak gibi: VPN yine calisir, yalnizca
            // olculu/ag bilgisi guncellenmez.
            Log.w(TAG, "ag geri cagirisi kaydedilemedi", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    /** VPN'in ustunde calistigi ag (Builder ve setUnderlyingNetworks icin); null = sistem secsin. */
    private fun systemUnderlying(): Network? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) underlying else null

    /** Sistem VPN'in hangi agin ustunde oldugunu bilsin (olculu durum, ag simgesi). */
    private fun applyUnderlying() {
        val n = systemUnderlying()
        runCatching { setUnderlyingNetworks(n?.let { arrayOf(it) }) }
            .onFailure { Log.w(TAG, "setUnderlyingNetworks", it) }
    }

    /**
     * Normalde ag degisiminde yeniden kurulacak bir sey yok: byedpi yeni soketleri yeni agdan
     * acar. Tek istisna DNS "Kapali": VPN'e eski agin DNS sunuculari (ornek: Wi-Fi modemi)
     * verilmisti, yeni agda onlara ulasilamaz; yeni sunucularla tun yerinde yenilenir.
     * Karsilastirma VPN'e GERCEKTEN verilecek listeyle (IPv6 kapaliyken IPv6 sunucular, geri
     * dongu vb. ayiklanmis, kume olarak): kullanilmayacak bir girdi yuzunden yenilenmesin.
     */
    private fun onNetworkChanged() {
        checkHealth()
        val running = engine.runningConfig ?: return
        if (running.redirectsDns) return
        if (!dnsChanged(running)) return
        netRestartJob?.cancel()
        netRestartJob = scope.launch {
            // Ag gecisinde bilgiler birkac geri cagiriyla parca parca geliyor; durulmasini bekle.
            delay(NETWORK_SETTLE_MS)
            val cfg = engine.runningConfig
            if (cfg != null && dnsChanged(cfg)) {
                Log.i(TAG, "alttaki agin DNS'i degisti, tun yerinde yenileniyor")
                restart(force = false)
            }
        }
    }

    /** Ag yokken (DNS bos) yenileme yok: sonraki ag gelince bakilir. */
    private fun dnsChanged(cfg: EngineConfig): Boolean {
        val now = underlyingDns
        return now.isNotEmpty() && VpnTunBuilder.tunKey(cfg, now) != builtTunKey
    }

    companion object {
        private const val TAG = "GdpiVpnService"

        const val ACTION_START = "io.github.unsalable.goodbyedpi.action.START"
        const val ACTION_STOP = "io.github.unsalable.goodbyedpi.action.STOP"
        const val ACTION_RESTART = "io.github.unsalable.goodbyedpi.action.RESTART"

        /** RESTART ile: yapilandirma ayni olsa da yeniden kur. */
        const val EXTRA_FORCE = "io.github.unsalable.goodbyedpi.extra.FORCE"

        private const val SETTINGS_DEBOUNCE_MS = 400L
        private const val HEALTH_INTERVAL_MS = 20_000L
        private const val NETWORK_SETTLE_MS = 1_000L
    }
}
