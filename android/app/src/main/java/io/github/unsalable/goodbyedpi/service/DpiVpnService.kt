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
 * birbirine karismaz.
 *
 * Kararlilik: START_STICKY (surec olurse sistem bos intent ile yeniden baslatir, wantRunning'e
 * bakilir), watchdog (byedpi/hev dusmesi -> 1/3/10 sn geri cekilmeyle yeniden baslatma, 5 dk'da
 * en fazla 5 deneme, sonra Failed + uyari), varsayilan ag geri cagirisi (setUnderlyingNetworks),
 * ayar degisince canli yeniden baslatma.
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

    // Ana is parcacigi (onStartCommand) ve motor is parcacigi yaziyor.
    @Volatile
    private var foreground = false

    /** Motor bu DNS listesiyle kuruldu (DNS "Kapali" iken ag degisince karsilastirmak icin). */
    private var builtDns: List<InetAddress> = emptyList()

    // Ag geri cagirisi ConnectivityThread'de yazar, motor is parcacigi okur.
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
            builtDns = dns
            tunBuilder.establish(cfg, systemUnderlying(), dns)
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

        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action startId=$startId")
        when (action) {
            ACTION_STOP -> scope.launch { stopByUser(startId) }
            ACTION_RESTART -> scope.launch { restart(intent.getBooleanExtra(EXTRA_FORCE, false), startId) }
            // Her zaman acik VPN sistemi SERVICE_INTERFACE ile baslatir: kullanici istegi sayilir.
            ACTION_START, SERVICE_INTERFACE -> scope.launch { startByUser() }
            // Surec olduktan sonra START_STICKY yeniden baslatmasi: son istege bak.
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
            settings.update { it.copy(wantRunning = false) }
            publish(EngineState.Stopped)
            leaveForeground()
            stopSelf()
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

    private suspend fun startByUser() {
        Notifications.cancelFailure(this)
        settings.update { it.copy(wantRunning = true) }
        policy.reset()
        if (engine.isRunning && engine.checkHealth() == null) {
            // Zaten bagli (ornek: karo iki kez, her zaman acik + kullanici): yalnizca durumu tazele.
            publishRunning()
            return
        }
        cancelPending()
        startEngine()
    }

    private suspend fun startFromSticky(startId: Int) {
        if (engine.isRunning || retryJob?.isActive == true) return
        if (settings.current.wantRunning) {
            Log.i(TAG, "yapiskan yeniden baslatma: son istek acik, motor kuruluyor")
            policy.reset()
            startEngine()
        } else {
            stopSelfIfIdle(startId)
        }
    }

    private suspend fun stopByUser(startId: Int) {
        cancelPending()
        if (engine.isRunning) {
            publish(EngineState.Stopping)
            engine.stop()
        }
        settings.update { it.copy(wantRunning = false) }
        publish(EngineState.Stopped)
        leaveForeground()
        // Bu STOP'tan sonra bir START geldiyse (kuyrukta) servis durmaz: stopSelf(startId).
        stopSelf(startId)
    }

    /**
     * Ayarlar degisti (ya da zorla). Motor calismiyorsa ve istek aciksa baslatir; istek kapaliysa
     * servisi birakir. [force] yoksa ayni yapilandirma icin yeniden baslatmaz: arayuz her
     * degisiklikte restartIfRunning cagirsa da ayar gozlemcisiyle cift baslatma olmaz.
     */
    private suspend fun restart(force: Boolean, startId: Int? = null) {
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
        if (!force && running != null && running.runtimeKey() == cfg.runtimeKey()) return
        Log.i(TAG, "yeniden baslatiliyor (force=$force)")
        cancelPending()
        policy.reset()
        publish(EngineState.Starting)
        engine.stop()
        startEngine()
    }

    private suspend fun startEngine() {
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

    private suspend fun onEngineDied(reason: String) {
        if (!engine.isRunning) return
        Log.w(TAG, "motor dustu: $reason")
        engine.stop()
        scheduleRetry(reason)
    }

    private suspend fun scheduleRetry(reason: String) {
        val wait = policy.next(SystemClock.elapsedRealtime())
        if (wait == null) {
            fail("$reason Otomatik yeniden bağlanma 5 denemede başarısız oldu.")
            return
        }
        Log.i(TAG, "watchdog: ${wait} ms sonra yeniden denenecek")
        publish(EngineState.Starting)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            startEngine()
        }
    }

    /** Kalici hata: motor kapali, Failed, uyari; servis durur ama wantRunning korunur (acilista tekrar). */
    private fun fail(message: String) {
        engine.stop()
        publish(EngineState.Failed(message))
        Notifications.showFailure(this, message)
        leaveForeground()
        stopSelf()
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (engine.isRunning || retryJob?.isActive == true) return
        if (EngineStateHolder.state.value !is EngineState.Failed) publish(EngineState.Stopped)
        leaveForeground()
        stopSelf(startId)
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
        publish(EngineState.Running(engine.sinceElapsed, cfg.methodName, cfg.dnsName, cfg.socksPort))
    }

    private fun publish(state: EngineState) {
        if (EngineStateHolder.state.value != state) Log.i(TAG, "durum: $state")
        EngineStateHolder.set(state)
        if (foreground && (state is EngineState.Running || state is EngineState.Starting || state is EngineState.Stopping)) {
            Notifications.updateStatus(this, state)
        }
        // Karo "aktif karo" degil: panel her acildiginda onStartListening ile durumu kendisi
        // okuyor, burada ayrica haber vermeye gerek yok.
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
     * Ayarlar degisince (yontem, DNS, yerel ag, IPv6, yedekler) motoru yeni ayarla yeniden
     * kurar. 400 ms debounce: kullanici adimlayiciyi hizla tiklarken her adimda yeniden
     * baslatilmasin.
     */
    @OptIn(FlowPreview::class)
    private fun observeSettings() {
        scope.launch {
            settings.settings
                .map { EngineConfig.from(it).runtimeKey() }
                .distinctUntilChanged()
                .debounce(SETTINGS_DEBOUNCE_MS)
                .collect { key ->
                    val running = engine.runningConfig ?: return@collect
                    if (running.runtimeKey() != key) {
                        Log.i(TAG, "ayarlar degisti, motor yeniden kuruluyor")
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

    private suspend fun checkHealth() {
        engine.checkHealth()?.let { onEngineDied(it) }
    }

    /**
     * Fiziksel (VPN olmayan) agi izler. registerDefaultNetworkCallback burada ise yaramiyor:
     * VPN kurulunca sahibi olan uygulamaya da (paketimiz VPN disinda olsa bile) varsayilan ag
     * olarak VPN'in kendisini bildiriyor (emulatorde goruldu) ve VPN kendi ustune kurulmus
     * gorunuyordu. Bu yuzden INTERNET + NOT_VPN istegi:
     * - API 31+: registerBestMatchingNetworkCallback, yani sistemin sectigi fiziksel ag;
     *   setUnderlyingNetworks ona ayarlanir.
     * - Daha eski: eslesen tum aglar izlenir (DNS "Kapali" icin en son gelenin DNS'i);
     *   setUnderlyingNetworks(null) = "sistemin varsayilan agi", sistem kendisi takip eder.
     * Geri cagirilar yalnizca alan yazip isi motor is parcacigina aktarir.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val bestMatching = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // API < 31: kullanilabilir aglar, gelis sirasiyla (son gelen onde sayilir).
        val available = LinkedHashMap<Network, List<InetAddress>>()

        fun publishCurrent() {
            val current = available.keys.lastOrNull()
            underlying = current
            underlyingDns = current?.let { available[it] }.orEmpty()
            applyUnderlying()
            scope.launch { onNetworkChanged() }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (bestMatching) available.clear()
                available.remove(network)
                available[network] = cm.getLinkProperties(network)?.dnsServers.orEmpty()
                Log.i(TAG, "fiziksel ag: $network")
                publishCurrent()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network !in available) return
                val dns = lp.dnsServers.orEmpty()
                if (available[network] == dns) return
                available[network] = dns
                if (network == underlying) publishCurrent()
            }

            override fun onLost(network: Network) {
                if (available.remove(network) == null) return
                Log.i(TAG, "ag kayboldu: $network")
                publishCurrent()
            }
        }
        try {
            if (bestMatching) {
                cm.registerBestMatchingNetworkCallback(request, cb, Handler(Looper.getMainLooper()))
            } else {
                // Handler'li surum API 26+; bu surumde geri cagirilar tek bir sistem is
                // parcaciginda sirayla gelir, available yine tek is parcacigindan kullanilir.
                cm.registerNetworkCallback(request, cb)
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
     * verilmisti, yeni agda onlara ulasilamaz; yeni sunucularla yeniden kurulur.
     */
    private suspend fun onNetworkChanged() {
        checkHealth()
        val running = engine.runningConfig ?: return
        if (running.redirectsDns) return
        val now = underlyingDns
        if (now.isEmpty() || now.toSet() == builtDns.toSet()) return
        netRestartJob?.cancel()
        netRestartJob = scope.launch {
            // Ag gecisinde bilgiler birkac geri cagiriyla parca parca geliyor; durulmasini bekle.
            delay(NETWORK_SETTLE_MS)
            if (engine.isRunning && underlyingDns.toSet() != builtDns.toSet()) {
                Log.i(TAG, "alttaki agin DNS'i degisti, VPN yeniden kuruluyor")
                restart(force = true)
            }
        }
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
