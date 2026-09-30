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
import io.github.unsalable.goodbyedpi.engine.Ipv6Gate
import io.github.unsalable.goodbyedpi.engine.VpnTunBuilder
import io.github.unsalable.goodbyedpi.update.UpdateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet6Address
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

    /** Alttaki agin IPv6 ozeti (kuresel adresler + varsayilan yol); geri cagiri yazar. */
    @Volatile
    private var underlyingLink: V6Link = V6Link.NONE

    // IPv6 kapisi (Ipv6Gate): yalnizca motor is parcacigi. Baslangicta false (guvenli taraf):
    // motor erisim denemesi gecmeden IPv6'siz kurulur, gecince tun yerinde yenilenir.
    private var underlyingV6 = false

    /** underlyingV6'nin ait oldugu ag, onun IPv6 ozeti ve deneme anahtari (son degerlendirme). */
    private var v6Network: Network? = null
    private var v6Link: V6Link = V6Link.NONE
    private var v6Key: ProbeKey? = null

    /** Gunluge son yazilan IPv6 durumu (her degisimde tek satir). */
    private var loggedIpv6: Ipv6Status? = null

    /** Erisim denemesi sonuclari (ag + kuresel adres kumesi basina); kucuk, en eskisi atilir. */
    private val probeCache = Ipv6ProbeCache<ProbeKey>(PROBE_OK_TTL_MS, PROBE_FAIL_TTL_MS, PROBE_CACHE_SIZE)
    private val probesRunning = HashMap<ProbeKey, CompletableDeferred<Ipv6Probe.Result>>()

    /** Denemeler burada: motor is parcacigi 2,5 sn'lik baglanti beklemesiyle tikanmasin. */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        observeIpv6Debug()
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
            // Bildirim izni yeni verildi: yukaridaki goForeground bildirimi zaten yeniden gonderdi.
            // Servis bu istekle yeni dogduysa (motor yok; ya da Recovery.giveUp /
            // ServiceController.recoverInBackground cokmus surecten asili kalan kaydi
            // temizliyor) on plandan cikip hemen birakilir.
            ACTION_REFRESH_NOTIFICATION -> scope.launch { stopSelfIfIdle(startId) }
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
        probeScope.cancel()
        // Normalde motor burada zaten kapali; degilse (sistem servisi durdurdu) son is olarak
        // kapat. Ana is parcacigini beklemeden: executor kuyrugu bitirip kendisi kapanir.
        // Durum yalnizca gercekten bir motor kapattiysak yazilir: hizli kapat/ac'ta yeni servis
        // ornegi coktan Starting/Running yayinlamis olabilir, onu ezmeyelim.
        val source = statsSource
        executor.execute {
            if (engine.isRunning) {
                engine.stop()
                EngineStateHolder.set(EngineState.Stopped)
                // Tun kapandi: arayuzdeki "Su an" satiri eski durumu gostermesin. Ayni kosulla:
                // normal durdurmada publish() bunu zaten yazdi; kosulsuz yazmak hizli kapat/ac'ta
                // yeni ornegin tunV6=true'sunu ezip 20 sn "IPv6 deneniyor" gosteriyordu.
                Ipv6StatusHolder.set(Ipv6StatusHolder.status.value.copy(tunV6 = false))
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
        // Arka plan yolu ilk basarili baslatmayi beklemeden acik: geri cekilmedeyken surec
        // olurse yapiskan yeniden baslatma ve App.onCreate bu istegi geri getirebilsin.
        runCatching { Recovery.markStartRequested(this) }.onFailure { Log.w(TAG, "markStartRequested", it) }
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
        // Surec olunce sistem bu servisi de yeniden baslatabiliyor (cokme sonrasi goruldu). Bu da
        // bir arka plan kurtarmasi: App.onCreate'in karari (disarm: kalici hata, ust uste cokmede
        // vazgecildi) burada da gecerli, yoksa vazgecilen baglanti kendiliginden geri acilirdi.
        if (settings.current.wantRunning && Recovery.backgroundArmed(this)) {
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
        val cfg = effectiveConfig()
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
        prepareIpv6ForStart()
        val cfg = effectiveConfig()
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
            fail(RestartPolicy.giveUpMessage(reason))
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
        publish(EngineState.Running(engine.sinceElapsed, cfg.methodName, cfg.dnsName, cfg.socksPort, engine.runningArgv, engine.generation))
    }

    private fun publish(state: EngineState) {
        if (EngineStateHolder.state.value != state) Log.i(TAG, "durum: ${describe(state)}")
        EngineStateHolder.set(state)
        // Her durum degisiminde tun'un IPv6'si da degismis olabilir (kuruldu, yenilendi, durdu).
        publishIpv6()
        if (foreground && (state is EngineState.Running || state is EngineState.Starting || state is EngineState.Stopping)) {
            Notifications.updateStatus(this, state)
        }
        // Karo "aktif karo" degil: panel her acildiginda onStartListening ile durumu kendisi
        // okuyor, burada ayrica haber vermeye gerek yok.
    }

    // argv uzun; gunluge yalnizca ozet (argv hata ayiklama derlemesinde DpiEngine'de yaziliyor).
    private fun describe(state: EngineState): String =
        if (state is EngineState.Running) {
            "Running(${state.methodName}, ${state.dnsName}, port ${state.socksPort}, argv ${state.argv.size} oge, motor ${state.generation})"
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
     *
     * Degisim DARALTILMAMIS ayarlarla aranir (EngineConfig.settingsChanges), alttaki aga gore
     * daraltma collect'te yapilir. Eskiden akisin icinde o anki underlyingV6 ile daraltiliyordu:
     * ilk deger deneme bitmeden (false ile) kaydediliyor, deneme gecip tun IPv6'ya gecince
     * kullanicinin "IPv6 kapat"i o eski kayitla ayni gorunup atiliyordu; tun ag degisene kadar
     * IPv6 sunmaya devam ediyordu (Google/YouTube acilmiyorsa onerilen tek care calismiyordu).
     */
    @OptIn(FlowPreview::class)
    private fun observeSettings() {
        scope.launch {
            EngineConfig.settingsChanges(settings.settings)
                .debounce(SETTINGS_DEBOUNCE_MS)
                .collect {
                    val running = engine.runningConfig ?: return@collect
                    val cfg = effectiveConfig()
                    if (!running.sameEngineAs(cfg) || running.methodName != cfg.methodName || running.dnsName != cfg.dnsName) {
                        Log.i(TAG, "ayarlar degisti")
                        restart(force = false)
                    }
                }
        }
        // IPv6 ayari acilinca deneme baslasin (kapaliyken hic denenmez), durum satiri tazelensin.
        // maybeRefreshTun: tun ayarla uyussun diye ikinci guvence (yukaridaki gozlemci zaten
        // yeniler); restart(force=false) ayni yapilandirmada hicbir sey yapmadigi icin cift is yok.
        // Debounce yok: bu gozlemci yukaridakinden (400 ms) once calisir, underlyingV6 orada
        // kullanilmadan duzelir.
        scope.launch {
            var previous: Boolean? = null
            settings.settings
                .map { it.ipv6 }
                .distinctUntilChanged()
                .collect { on ->
                    // Kapaliyken deneme yapilmaz, basari bayatlar ama underlyingV6 onunla true
                    // kalir. Ayar yeniden acilinca ona guvenmek, taze deneme surerken tun'a IPv6
                    // verirdi (IPv6 bu arada bozulduysa Chrome/YouTube RST alir, sonra ikinci bir
                    // yenileme). Yeni baslatmadaki kural: bayat basari yokmus gibi, IPv4'le beklenir.
                    if (previous == false && on) distrustStaleSuccess(v6Key)
                    previous = on
                    evaluateIpv6()
                    maybeRefreshTun()
                }
        }
    }

    /**
     * YALNIZCA HATA AYIKLAMA: deneme sonucu adb'den zorlanirsa (src/debug DebugIpv6Receiver)
     * onbellek atilir ve hemen yeniden denenir. Surumde BuildConfig.DEBUG sabit false.
     */
    private fun observeIpv6Debug() {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            Ipv6Probe.debugOverride.drop(1).collect { forced ->
                Log.i(TAG, "ipv6: hata ayiklama zorlamasi=$forced, deneme onbellegi siliniyor")
                probeCache.clear()
                evaluateIpv6()
                maybeRefreshTun()
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
                noteUptime()
                // Basarisiz deneme PROBE_FAIL_TTL_MS, basarili deneme PROBE_OK_TTL_MS sonra
                // yeniden denenir (Ipv6ProbeCache): ag gecici bozuksa IPv6 sonsuza dek kapali,
                // sonradan bozulduysa sonsuza dek acik kalmasin.
                if (evaluateIpv6()) maybeRefreshTun()
            }
        }
    }

    private fun checkHealth() {
        engine.checkHealth()?.let { onEngineDied(it) }
    }

    /** Motor izleme penceresi boyunca ayakta kaldiysa son arka plan kurtarmasi tutmustur. */
    private fun noteUptime() {
        if (!engine.isRunning) return
        val up = SystemClock.elapsedRealtime() - engine.sinceElapsed
        runCatching { Recovery.noteEngineUptime(this, up) }.onFailure { Log.w(TAG, "noteEngineUptime", it) }
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

        fun publishCurrent(network: Network?, dns: List<InetAddress>, link: V6Link) {
            // Once ozet, sonra ag: motor is parcacigi agi gorunce ozeti de yeni gorsun.
            underlyingLink = link
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
                val lp = cm.getLinkProperties(network)
                publishCurrent(network, lp?.dnsServers.orEmpty(), v6LinkOf(lp))
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != underlying) return
                val dns = lp.dnsServers.orEmpty()
                // IPv6 adresi / yolu genelde onAvailable'dan SONRA (SLAAC) gelir; DNS ayni olsa da
                // IPv6 ozeti degistiyse yeniden degerlendirilir.
                val link = v6LinkOf(lp)
                if (dns == underlyingDns && link == underlyingLink) return
                publishCurrent(network, dns, link)
            }

            override fun onLost(network: Network) {
                if (network != underlying) return
                Log.i(TAG, "ag kayboldu: $network")
                // IPv6 ozeti bilerek korunur: ag yokken tun yenilenmez, sonraki ag karar verir.
                publishCurrent(null, emptyList(), underlyingLink)
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
     * acar. Iki istisna, ikisinde de tun yerinde yenilenir:
     * - IPv6: yeni agin IPv6'si (Ipv6Gate + erisim denemesi) tun'dakinden farkliysa.
     * - DNS "Kapali": VPN'e eski agin DNS sunuculari (ornek: Wi-Fi modemi) verilmisti, yeni agda
     *   onlara ulasilamaz. Karsilastirma VPN'e GERCEKTEN verilecek listeyle (IPv6 kapaliyken
     *   IPv6 sunucular, geri dongu vb. ayiklanmis, kume olarak): kullanilmayacak bir girdi
     *   yuzunden yenilenmesin.
     */
    private fun onNetworkChanged() {
        checkHealth()
        evaluateIpv6()
        maybeRefreshTun()
    }

    /**
     * Tun'un yenilenmesi gerekiyorsa NETWORK_SETTLE_MS sonra (ag gecisinde bilgiler birkac geri
     * cagiriyla parca parca geliyor) restart(force=false): tunKey degistigi icin tun yerinde
     * yenilenir. IPv6'yi ACMAK icin deneme beklenir (sonucu gelince burasi yeniden cagrilir):
     * IPv4'teki tun gecis basina en cok bir kez yenilenir. IPv6'yi KAPATMAK beklemez: tun IPv6
     * sunarken denemesi bitmemis yeni bir aga (ornek: adresi/yolu olan ama IPv6 cikisi bozuk
     * mobil veri) gecildiyse deneme boyunca (~5-6 sn) Chrome/YouTube IPv6'dan RST alirdi, 1.0.1
     * belirtisi. Bedeli: yeni agin IPv6'si calisiyorsa bir ek yenileme.
     */
    private fun maybeRefreshTun() {
        val running = engine.runningConfig ?: return
        if (!v6Differs(running) && (running.redirectsDns || !dnsChanged(running))) return
        netRestartJob?.cancel()
        netRestartJob = scope.launch {
            delay(NETWORK_SETTLE_MS)
            val cfg = engine.runningConfig ?: return@launch
            val v6Off = cfg.ipv6 && v6Differs(cfg)
            if (probePendingForCurrent() && !v6Off) return@launch
            if (v6Differs(cfg)) {
                Log.i(TAG, "ipv6: tun ${cfg.ipv6} -> ${!cfg.ipv6}, tun yerinde yenileniyor")
                restart(force = false)
            } else if (!cfg.redirectsDns && dnsChanged(cfg)) {
                Log.i(TAG, "alttaki agin DNS'i degisti, tun yerinde yenileniyor")
                restart(force = false)
            }
        }
    }

    /** Ag yokken (onLost) IPv6 yuzunden yenileme yok: sonraki ag gelince bakilir. */
    private fun v6Differs(cfg: EngineConfig): Boolean =
        underlying != null && cfg.ipv6 != effectiveConfig().ipv6

    /** Ag yokken (DNS bos) yenileme yok: sonraki ag gelince bakilir. */
    private fun dnsChanged(cfg: EngineConfig): Boolean {
        val now = underlyingDns
        return now.isNotEmpty() && VpnTunBuilder.tunKey(cfg, now) != builtTunKey
    }

    // ------------------------------------------------------------------ IPv6 kapisi (motor is parcacigi)

    /** Ayarlar, alttaki agin IPv6'siyla daraltilmis: tun, hev ve byedpi hep bunu kullanir. */
    private fun effectiveConfig(): EngineConfig =
        EngineConfig.from(settings.current).withUnderlyingV6(underlyingV6)

    /**
     * underlyingV6'yi verilen ag icin yeniden hesaplar; gerekirse deneme baslatir (yalnizca ayar
     * acikken ve ag kapidan geciyorsa). Ag yoksa (onLost) son deger korunur.
     * @return underlyingV6 degisti mi
     */
    private fun evaluateIpv6(net: Network? = underlying, link: V6Link = underlyingLink): Boolean {
        if (net == null) {
            publishIpv6()
            return false
        }
        val gate = link.gate
        val key = if (gate) ProbeKey(net, link.global) else null
        val now = SystemClock.elapsedRealtime()
        // Ayni agda bayat basari (PROBE_OK_TTL_MS) karar icin gecerli kalir; yeniden deneme arka
        // planda. Baska bir agdan gecildiyse (Wi-Fi -> mobil -> Wi-Fi, 10 dk'dan uzun) ona
        // guvenilmez: yeni baslatmadaki gibi taze deneme beklenir, o zamana kadar IPv4.
        if (key != null && net != v6Network) probeCache.dropStale(key, now)
        val cached = key?.let { probeCache.get(it, now) }
        if (key != null && settings.current.ipv6 && probeCache.needsProbe(key, now)) startProbe(net, key)
        val next = Ipv6Gate.decide(gate, cached?.ok, sameNetwork = net == v6Network, current = underlyingV6)
        v6Network = net
        v6Link = link
        v6Key = key
        val changed = next != underlyingV6
        underlyingV6 = next
        if (changed) Log.i(TAG, "ipv6: alttaki ag kullanilabilir=$next")
        publishIpv6()
        return changed
    }

    /**
     * Ilk kurulumdan once: ag IPv6'li gorunuyorsa denemenin sonucunu en cok START_PROBE_WAIT_MS
     * bekler; yetismezse IPv4'le kurulur, deneme gecince tun bir kez yenilenir. Servis yeni
     * dogduysa ag geri cagirisi henuz gelmemis olabilir: o zaman kendi (VPN disi) varsayilan
     * agimiza bakilir; paketimiz VPN'den haric oldugu icin bu fiziksel agdir.
     */
    private fun prepareIpv6ForStart() {
        var net = underlying
        var link = underlyingLink
        if (net == null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            net = cm?.activeNetwork?.takeIf { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
            }
            link = v6LinkOf(net?.let { cm?.getLinkProperties(it) })
        }
        if (net == null) return
        if (settings.current.ipv6 && link.gate) {
            val key = ProbeKey(net, link.global)
            // Yeni baglantida bayat basariya guvenilmez (kapaliyken IPv6 bozulmus olabilir):
            // sonuc yokmus gibi taze deneme beklenir, yetismezse IPv4 ile baslanir.
            val now = SystemClock.elapsedRealtime()
            probeCache.dropStale(key, now)
            if (probeCache.get(key, now) == null) {
                val pending = startProbe(net, key)
                val r = runBlocking { withTimeoutOrNull(START_PROBE_WAIT_MS) { pending.await() } }
                if (r != null) {
                    recordProbe(key, r)
                } else {
                    Log.i(TAG, "ipv6: deneme yetismedi, IPv4 ile baslaniyor")
                    // Ayni servis orneginde yeniden baglanmada eski deger (true) kalmis olabilir;
                    // Ipv6Gate.decide ayni agda onu korurdu. Yeni kurulan tun sonucsuz IPv6 sunmasin.
                    underlyingV6 = false
                }
            }
        }
        evaluateIpv6(net, link)
    }

    private fun startProbe(net: Network, key: ProbeKey): CompletableDeferred<Ipv6Probe.Result> {
        probesRunning[key]?.let { return it }
        val done = CompletableDeferred<Ipv6Probe.Result>()
        probesRunning[key] = done
        Log.i(TAG, "ipv6: erisim denemesi ($net, ${key.global.size} kuresel adres)")
        probeScope.launch {
            val r = runCatching { Ipv6Probe.run(net) }
                .getOrElse { Ipv6Probe.Result(false, it.javaClass.simpleName) }
            done.complete(r)
            scope.launch { onProbeDone(key, r) }
        }
        return done
    }

    private fun recordProbe(key: ProbeKey, r: Ipv6Probe.Result) {
        val wasRunning = probesRunning.remove(key) != null
        val stored = probeCache.put(key, r, SystemClock.elapsedRealtime())
        if (wasRunning) {
            // Basarinin ustune tek basarisizlik yazilmaz (Ipv6ProbeCache.confirmFails): saglik
            // dongusu (~20 sn) yeniden dener, ikinci kez de basarisizsa IPv6 kapanir.
            val what = if (r.ok) "gecti" else if (stored) "basarisiz" else "basarisiz, dogrulanacak"
            Log.i(TAG, "ipv6: deneme $what (${r.detail})")
        }
    }

    /**
     * Bayat basariya guvenme: atilir ve karar false'a cekilir (sonuc yokken Ipv6Gate.decide ayni
     * agda eski degeri korurdu). Taze deneme gecince tun bir kez IPv6'ya yenilenir.
     */
    private fun distrustStaleSuccess(key: ProbeKey?) {
        if (key == null || !probeCache.dropStale(key, SystemClock.elapsedRealtime())) return
        Log.i(TAG, "ipv6: bayat basari atildi, taze deneme beklenecek")
        underlyingV6 = false
    }

    private fun onProbeDone(key: ProbeKey, r: Ipv6Probe.Result) {
        // Baslangicta beklenip zaten kaydedildiyse bir daha yazma (zaman damgasi ilerlemesin).
        if (probesRunning.containsKey(key)) recordProbe(key, r)
        // Eski ag / eski adres kumesinin sonucu: yalnizca onbellege.
        if (key != v6Key) {
            publishIpv6()
            return
        }
        evaluateIpv6()
        maybeRefreshTun()
    }

    private fun probePendingForCurrent(): Boolean = v6Key?.let { probesRunning.containsKey(it) } == true

    /** Ipv6StatusHolder'i gunceller; degistiyse tek satir gunluk. */
    private fun publishIpv6() {
        val key = v6Key
        val result = key?.let { probeCache.peek(it) }
        val running = key != null && probesRunning.containsKey(key)
        val st = Ipv6Status(
            setting = settings.current.ipv6,
            underlyingGlobal = v6Link.global.isNotEmpty(),
            underlyingDefaultRoute = v6Link.defaultRoute,
            probeOk = result?.ok,
            // Eski sonucun ustune suren deneme (bayat basarinin tazelenmesi) raporda da gorunsun.
            probeDetail = when {
                result == null -> if (running) "deneniyor" else null
                running -> "${result.detail}; yeniden deneniyor"
                else -> result.detail
            },
            tunV6 = engine.runningConfig?.ipv6 == true,
        )
        Ipv6StatusHolder.set(st)
        // Holder'la degil bu servisin son yazdigiyla karsilastir: IPv6'siz agda durum holder'in
        // baslangic degeriyle ayni cikiyor ve tek satir hic yazilmiyordu.
        if (st == loggedIpv6) return
        loggedIpv6 = st
        Log.i(
            TAG,
            "ipv6: ayar=${st.setting} ag=${v6Link.gate} (kuresel=${st.underlyingGlobal} " +
                "yol=${st.underlyingDefaultRoute}) test=${st.probeOk ?: "-"}" +
                "${st.probeDetail?.let { " ($it)" } ?: ""} tun=${st.tunV6}",
        )
    }

    /** Alttaki agin IPv6 ozeti; kume esitligi ile karsilastirilir (sira onemsiz). */
    private data class V6Link(val global: Set<InetAddress>, val defaultRoute: Boolean) {
        val gate: Boolean get() = Ipv6Gate.hasGlobalV6(global, defaultRoute)

        companion object {
            val NONE = V6Link(emptySet(), false)
        }
    }

    /** Deneme sonucu bu ag ve bu kuresel adres kumesi icin gecerli (adres degisince yeniden). */
    private data class ProbeKey(val network: Network, val global: Set<InetAddress>)

    private fun v6LinkOf(lp: LinkProperties?): V6Link {
        if (lp == null) return V6Link.NONE
        val route = lp.routes.any { r -> r.isDefaultRoute && r.destination.address is Inet6Address }
        return V6Link(Ipv6Gate.globalAddresses(lp.linkAddresses.map { it.address }), route)
    }

    companion object {
        private const val TAG = "GdpiVpnService"

        const val ACTION_START = "io.github.unsalable.goodbyedpi.action.START"
        const val ACTION_STOP = "io.github.unsalable.goodbyedpi.action.STOP"
        const val ACTION_RESTART = "io.github.unsalable.goodbyedpi.action.RESTART"

        /** Yalnizca on plan bildirimini yeniden gonderir (bkz. ServiceController.refreshNotification). */
        const val ACTION_REFRESH_NOTIFICATION = "io.github.unsalable.goodbyedpi.action.REFRESH_NOTIFICATION"

        /** RESTART ile: yapilandirma ayni olsa da yeniden kur. */
        const val EXTRA_FORCE = "io.github.unsalable.goodbyedpi.extra.FORCE"

        private const val SETTINGS_DEBOUNCE_MS = 400L
        private const val HEALTH_INTERVAL_MS = 20_000L
        private const val NETWORK_SETTLE_MS = 1_000L

        /** Ilk kurulumda IPv6 denemesi en cok bu kadar beklenir; sonra IPv4 ile baslanir. */
        private const val START_PROBE_WAIT_MS = 2_000L

        /** Basarisiz deneme bu kadar sonra (saglik dongusunde) yeniden denenir. */
        private const val PROBE_FAIL_TTL_MS = 5 * 60_000L

        /**
         * Basarili deneme bu kadar sonra arka planda yeniden denenir; sonuc gelene kadar IPv6
         * acik kalir. Sonradan bozulan IPv6 en gec bu sure + saglik dongusu (20 sn) sunulur;
         * bedeli 10 dk'da bir kucuk TLS el sikismasi.
         */
        private const val PROBE_OK_TTL_MS = 10 * 60_000L
        private const val PROBE_CACHE_SIZE = 8
    }
}
