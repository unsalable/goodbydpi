package io.github.unsalable.goodbyedpi.engine

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.service.TrafficStats
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * byedpi + hev-socks5-tunnel ikilisinin baslatma/durdurma sirasi (SPEC 3, HEV_NOTES 7).
 *
 * Is parcacigi modeli: [start], [stop] ve [checkHealth] TEK bir is parcacigindan (servisin motor
 * is parcacigi) cagrilmali; icerde kilit yok, sira oradan geliyor. [stats] ve [isRunning] her
 * yerden okunabilir. Hicbiri ana is parcacigindan cagrilmamali (bekleme ve yerel cagri var).
 *
 * Temel kural: hicbir hata yolu yarim motor birakmaz. Baslatma bir adimda duserse o ana kadar
 * acilan her sey ters sirayla kapatilir ve [StartException] firlatilir.
 *
 * @param tun VpnService.Builder.establish()'i saran uretici; testlerde soket cifti verilir.
 * @param hevLogFile hata ayiklama derlemesinde hev gunlugu; null = kapali.
 */
class DpiEngine(
    private val filesDir: File,
    private val tun: TunFactory,
    private val hevLogFile: File? = null,
) {
    /** Tun arayuzunu kurar; izin yoksa null (VpnService.Builder.establish sozlesmesi). */
    fun interface TunFactory {
        fun establish(config: EngineConfig): ParcelFileDescriptor?
    }

    /** Calisirken motorun bir parcasi kendiliginden durdu. Hangi is parcaciginda geldigi belirsiz. */
    fun interface ExitListener {
        fun onUnexpectedExit(reason: String)
    }

    /**
     * @param retryable watchdog yeniden denemeli mi (izin yok / gecersiz arguman gibi kalici
     *   hatalarda false)
     * @param portBusy byedpi portu acamadi; motor bos port secmisse baska portla hemen yeniden dener
     */
    class StartException(
        message: String,
        val retryable: Boolean,
        val portBusy: Boolean = false,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private data class Run(
        val config: EngineConfig,
        val byedpi: ByeDpiRunner,
        val pfd: ParcelFileDescriptor,
        val sinceElapsed: Long,
        /** byedpi'ye gercekte verilen argv (secilen port dahil). */
        val argv: List<String>,
        /** Bkz. [generation]. */
        val generation: Int = GENERATIONS.incrementAndGet(),
    )

    @Volatile
    var exitListener: ExitListener? = null

    @Volatile
    private var run: Run? = null

    val isRunning: Boolean
        get() = run != null

    /** Calisan yapilandirma (secilen port dahil); calismiyorsa null. */
    val runningConfig: EngineConfig?
        get() = run?.config

    /** Calisan byedpi'nin argv'si (program adi haric); calismiyorsa bos. */
    val runningArgv: List<String>
        get() = run?.argv.orEmpty()

    val sinceElapsed: Long
        get() = run?.sinceElapsed ?: 0L

    /**
     * Calisan motorun kimligi: her [start] ve byedpi/tun'u yenileyen her [reconfigure] yeni bir
     * deger verir; yalnizca ad degisimi vermez. Yerinde guncelleme portu (ve sinceElapsed'i)
     * korudugu icin arayuz eski motorla alinmis baglanti testi sonuclarini ancak buna bakarak
     * silebiliyor (conntest-stale-after-inplace-update). 0 = calismiyor.
     */
    val generation: Int
        get() = run?.generation ?: 0

    val hevConfigFile: File
        get() = File(filesDir, HEV_CONFIG_NAME)

    /**
     * Motoru baslatir. Zaten calisiyorsa once durdurur.
     * @return gercekte kullanilan yapilandirma (secilen socksPort ile)
     * @throws StartException nedeni Turkce, kullaniciya gosterilebilir
     */
    fun start(config: EngineConfig): EngineConfig {
        if (run != null) stop()
        resetCounters()

        // 1) byedpi.
        val (proxy, cfg) = launchByeDpi(config)

        // 2) Tun. null = VPN izni yok (ya da baska bir uygulama her zaman acik VPN).
        val pfd: ParcelFileDescriptor = try {
            establishTun(cfg)
        } catch (t: Throwable) {
            proxy.stop()
            throw t
        }

        // 3) hev. fd'nin sahibi biziz: hev kapatmaz, getFd() verilir (detach degil); durdurunca
        //    once hev, sonra pfd.close (HEV_NOTES 2).
        try {
            startHev(cfg, pfd)
            if (!proxy.isAlive) {
                runCatching { TProxy.TProxyStopService() }
                throw StartException(ByeDpiRunner.describeExit(proxy.exitCode ?: NativeBridge.ERR_EXITED), retryable = true)
            }
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            proxy.stop()
            throw t
        }

        run = Run(cfg, proxy, pfd, SystemClock.elapsedRealtime(), ByeDpiArgs.build(cfg))
        Log.i(TAG, "motor calisiyor: port ${cfg.socksPort}, yontem ${cfg.methodName}, dns ${cfg.dnsName}")
        return cfg
    }

    /**
     * Calisan motoru VPN agini DUSURMEDEN yeni yapilandirmaya gecirir. Tun fd'si kapanmadigi
     * surece sistemin VPN agi ayni kalir; uygulamalar "ag koptu" gormez (e2e RT-2).
     *
     * - Yalnizca adlar farkliysa: hicbir sey yeniden kurulmaz, adlar guncellenir.
     * - byedpi argv'si farkliysa (yontem, yedekler, DNS yonlendirmesi): yalnizca byedpi AYNI
     *   portta yeniden baslar; hev ve tun yerinde kalir (hev portu zaten biliyor).
     * - [rebuildTun] (yollar, adresler ya da VPN'e verilen DNS sunuculari degisti): yeni tun
     *   eski fd ACIKKEN kurulur. Vpn sinifi bu durumda ayni agin LinkProperties'ini yerinde
     *   gunceller; sonra hev yeni fd'ye tasinir ve eski fd kapatilir.
     *
     * Calismiyorsa [start] gibi davranir. Herhangi bir adim basarisiz olursa motor TAMAMEN
     * durur (yarim motor yok) ve [StartException] firlatilir; cagiran normal yeniden deneme
     * yoluna gider.
     */
    fun reconfigure(config: EngineConfig, rebuildTun: Boolean): EngineConfig {
        val r = run ?: return start(config)
        val port = r.config.socksPort
        val samePort = config.copy(socksPort = port)
        val swapByeDpi = ByeDpiArgs.build(samePort) != r.argv
        if (!swapByeDpi && !rebuildTun) {
            run = r.copy(config = samePort)
            return samePort
        }

        // Bundan sonra byedpi'nin (eski ya da yeni) donusu "beklenmedik" sayilmasin; kaynaklar
        // yerel degiskenlerde, hata olursa hepsi kapatilir.
        run = null
        var proxy: ByeDpiRunner? = r.byedpi
        var pfd: ParcelFileDescriptor? = r.pfd
        var newPfd: ParcelFileDescriptor? = null
        var hevRunning = true
        try {
            // 1) Yeni tun, eski hala acikken.
            if (rebuildTun) newPfd = establishTun(samePort)

            // 2) byedpi degisimi: once eskisi tamamen durmali (surec basina tek vekil).
            var cfg = samePort
            if (swapByeDpi) {
                val old = checkNotNull(proxy)
                proxy = null
                if (!old.stop()) throw StartException("byedpi durdurulamadı.", retryable = true)
                // Once ayni port (hev'e dokunmadan). Baska bir uygulama araya girip kaptiysa ve
                // port motorun seciminde ise bos bir portla devam; o zaman hev de yeniden kurulur.
                val (runner, used) = launchByeDpi(config, preferredPort = port)
                proxy = runner
                cfg = used
            }

            // 3) hev: yeni fd'ye ya da yeni porta tasinmasi gerekiyorsa.
            if (newPfd != null || cfg.socksPort != port) {
                runCatching { TProxy.TProxyStopService() }.onFailure { Log.e(TAG, "hev durdurulamadi", it) }
                hevRunning = false
                if (newPfd != null) {
                    runCatching { pfd?.close() }
                    pfd = newPfd
                    newPfd = null
                }
                resetCounters()
                startHev(cfg, checkNotNull(pfd))
                hevRunning = true
            }
            val p = checkNotNull(proxy)
            if (!p.isAlive) {
                throw StartException(ByeDpiRunner.describeExit(p.exitCode ?: NativeBridge.ERR_EXITED), retryable = true)
            }
            run = Run(cfg, p, checkNotNull(pfd), r.sinceElapsed, ByeDpiArgs.build(cfg))
            Log.i(TAG, "motor yerinde guncellendi: byedpi ${if (swapByeDpi) "yeni" else "ayni"}, tun ${if (rebuildTun) "yeni" else "ayni"}, port ${cfg.socksPort}")
            return cfg
        } catch (t: Throwable) {
            if (hevRunning) runCatching { TProxy.TProxyStopService() }
            runCatching { newPfd?.close() }
            runCatching { pfd?.close() }
            proxy?.stop()
            Log.w(TAG, "yerinde guncelleme basarisiz, motor durdu", t)
            if (t is StartException) throw t
            // Ic istisna metni (Ingilizce/ASCII) kullaniciya gosterilmez; ayrinti yukaridaki gunlukte.
            throw StartException("Motor yeniden yapılandırılamadı.", retryable = true, cause = t)
        }
    }

    /**
     * byedpi'yi baslatir ve hazir olmasini bekler. [EngineConfig.socksPort] 0 degilse port
     * sabit; 0 ise motor secer ([preferredPort] once denenir). Secimle bind arasinda baska bir
     * uygulama portu kapabilir; o durumda baska portla birkac kez daha denenir.
     */
    private fun launchByeDpi(config: EngineConfig, preferredPort: Int = 0): Pair<ByeDpiRunner, EngineConfig> {
        val fixed = config.socksPort != 0
        val attempts = if (fixed) 1 else PORT_ATTEMPTS
        for (attempt in 1..attempts) {
            val port = when {
                fixed -> config.socksPort
                attempt == 1 && preferredPort != 0 -> preferredPort
                else -> freeLoopbackPort()
            }
            val cfg = config.copy(socksPort = port)
            if (BuildConfig.DEBUG) Log.d(TAG, ByeDpiArgs.describe(cfg))
            val runner = ByeDpiRunner(ByeDpiArgs.build(cfg), port, ::onByeDpiExit)
            try {
                runner.start()
                return runner to cfg
            } catch (e: StartException) {
                runner.stop()
                if (!e.portBusy || attempt == attempts) throw e
                Log.w(TAG, "port $port dolu, yeniden deneniyor")
            } catch (t: Throwable) {
                // Kesinti vb.: is parcacigi basladiysa geride calisan vekil kalmasin.
                runner.stop()
                throw StartException("byedpi başlatılamadı.", retryable = true, cause = t)
            }
        }
        error("ulasilamaz")
    }

    /** Tun'u kurar; null (VPN izni yok) kalici hatadir. */
    private fun establishTun(cfg: EngineConfig): ParcelFileDescriptor {
        val pfd = try {
            tun.establish(cfg)
        } catch (t: Throwable) {
            // Sistem istisnasinin metni kullaniciya gitmez (Failed durumu ve bildirim); servis
            // StartException'i nedeniyle (cause) birlikte gunluge yaziyor.
            throw StartException("VPN arayüzü kurulamadı.", retryable = true, cause = t)
        }
        return pfd ?: throw StartException("VPN izni yok", retryable = false)
    }

    /** hev'i [pfd] uzerinde baslatir; basarisizsa kendi yarim kalanini durdurup firlatir. */
    private fun startHev(cfg: EngineConfig, pfd: ParcelFileDescriptor) {
        var hevStarted = false
        try {
            hevLogFile?.let { runCatching { HevConfig.rotateLog(it) } }
            HevConfig.write(hevConfigFile, HevConfig.yaml(cfg.socksPort, cfg.ipv6, hevLogFile?.path))
            if (!TProxy.TProxyStartService(hevConfigFile.path, pfd.fd)) {
                throw StartException("Tünel yapılandırması geçersiz.", retryable = true)
            }
            hevStarted = true
            // true donmesi is parcaciginin kuruldugunu soyler; log dosyasi/fd hatalari birkac ms
            // sonra IsRunning=false olarak gorunur.
            Thread.sleep(HEV_SETTLE_MS)
            if (!TProxy.TProxyIsRunning()) throw StartException("Tünel başlatılamadı.", retryable = true)
        } catch (t: Throwable) {
            if (hevStarted) runCatching { TProxy.TProxyStopService() }
            if (t is StartException) throw t
            // Ornek: hev.yml yazilamadi (check mesaji ASCII, ic ayrinti). Kullaniciya duz bir
            // cumle; ayrinti servisin gunlugunde cause olarak (e2e E2E-V6-2).
            throw StartException("Tünel başlatılamadı.", retryable = true, cause = t)
        }
    }

    /** Ters sira: hev (tun is parcacigini join eder) -> tun fd -> byedpi (join, sinirli sure). */
    fun stop() {
        val r = run ?: return
        // Once null: byedpi'nin bundan sonraki donusu "beklenmedik" sayilmasin.
        run = null
        runCatching { TProxy.TProxyStopService() }.onFailure { Log.e(TAG, "hev durdurulamadi", it) }
        runCatching { r.pfd.close() }.onFailure { Log.w(TAG, "tun fd kapatilamadi", it) }
        r.byedpi.stop()
        Log.i(TAG, "motor durdu")
    }

    /**
     * Ucuz saglik denetimi (atomik okuma + is parcacigi durumu). null = saglikli ya da calismiyor;
     * aksi halde neden. hev, byedpi dusse de calismaya devam ettigi icin ikisine ayri bakilir.
     */
    fun checkHealth(): String? {
        val r = run ?: return null
        if (!r.byedpi.isAlive) return ByeDpiRunner.describeExit(r.byedpi.exitCode ?: NativeBridge.ERR_EXITED)
        if (!TProxy.TProxyIsRunning()) return "Tünel beklenmedik şekilde kapandı."
        return null
    }

    /** Tunelin toplam sayaclari (tx = uygulamalardan aga). Calismiyorsa sifir. */
    fun stats(): TrafficStats {
        if (run == null) return TrafficStats.ZERO
        val raw = runCatching { TProxy.TProxyGetStats() }.getOrNull() ?: return TrafficStats.ZERO
        if (raw.size < 4) return TrafficStats.ZERO
        val v = unwrap(raw)
        return TrafficStats(txBytes = v[1], rxBytes = v[3], txPackets = v[0], rxPackets = v[2])
    }

    private fun onByeDpiExit(runner: ByeDpiRunner, code: Int) {
        // Yalnizca calisan motorun byedpi'si; baslatma sirasindaki cikislari start() kendisi goruyor.
        if (run?.byedpi !== runner) return
        exitListener?.onUnexpectedExit(ByeDpiRunner.describeExit(code))
    }

    // ------------------------------------------------------------------ sayaclar

    // hev sayaclari size_t: 32 bit ABI'lerde 2^32'de sarar. Her calismada sifirdan basladigi
    // icin sarma yalnizca ayni calisma icindeki geri gidis olabilir.
    private val counterLock = Any()
    private val lastRaw = LongArray(4)
    private val offset = LongArray(4)

    private fun resetCounters() = synchronized(counterLock) {
        lastRaw.fill(0)
        offset.fill(0)
    }

    private fun unwrap(raw: LongArray): LongArray = synchronized(counterLock) {
        LongArray(4) { i ->
            val x = raw[i]
            if (!IS_64_BIT && x < lastRaw[i]) offset[i] += WRAP
            lastRaw[i] = x
            x + offset[i]
        }
    }

    companion object {
        private const val TAG = "GdpiEngine"
        const val HEV_CONFIG_NAME = "hev.yml"
        private const val PORT_ATTEMPTS = 3
        private const val HEV_SETTLE_MS = 250L
        private const val WRAP = 1L shl 32
        private val IS_64_BIT: Boolean = runCatching { Process.is64Bit() }.getOrDefault(true)

        /** Surec genelinde artan motor kimligi: servis yeniden dogsa da tekrar etmesin. */
        private val GENERATIONS = AtomicInteger(0)

        /** Geri dongu adresinde o an bos bir port. */
        fun freeLoopbackPort(): Int =
            try {
                ServerSocket(0, 1, ByeDpiRunner.LOOPBACK).use { it.localPort }
            } catch (e: Exception) {
                // Tipik neden fd siniri (EMFILE); gecici olabilir, watchdog yeniden dener.
                throw StartException("Yerel port açılamadı.", retryable = true, cause = e)
            }
    }
}
