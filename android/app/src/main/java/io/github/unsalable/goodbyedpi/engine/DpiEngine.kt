package io.github.unsalable.goodbyedpi.engine

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.service.TrafficStats
import java.io.File
import java.net.ServerSocket

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

    private class Run(
        val config: EngineConfig,
        val byedpi: ByeDpiRunner,
        val pfd: ParcelFileDescriptor,
        val sinceElapsed: Long,
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

    val sinceElapsed: Long
        get() = run?.sinceElapsed ?: 0L

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

        // 1) byedpi. Port 0 ise bos port seciyoruz; secimle bind arasinda baska bir uygulama
        //    portu kapabilir, o durumda baska portla birkac kez daha deneriz.
        var cfg = config
        var byedpi: ByeDpiRunner? = null
        val attempts = if (config.socksPort == 0) PORT_ATTEMPTS else 1
        for (attempt in 1..attempts) {
            val port = if (config.socksPort != 0) config.socksPort else freeLoopbackPort()
            cfg = config.copy(socksPort = port)
            if (BuildConfig.DEBUG) Log.d(TAG, ByeDpiArgs.describe(cfg))
            val runner = ByeDpiRunner(ByeDpiArgs.build(cfg), port, ::onByeDpiExit)
            try {
                runner.start()
                byedpi = runner
                break
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
        val proxy = checkNotNull(byedpi)

        // 2) Tun. null = VPN izni yok (ya da baska bir uygulama her zaman acik VPN).
        val pfd: ParcelFileDescriptor = try {
            tun.establish(cfg)
        } catch (t: Throwable) {
            proxy.stop()
            throw StartException("VPN arayüzü kurulamadı: ${t.message ?: t.javaClass.simpleName}", retryable = true, cause = t)
        } ?: run {
            proxy.stop()
            throw StartException("VPN izni yok", retryable = false)
        }

        // 3) hev. fd'nin sahibi biziz: hev kapatmaz, getFd() verilir (detach degil); durdurunca
        //    once hev, sonra pfd.close (HEV_NOTES 2).
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
            if (!proxy.isAlive) {
                throw StartException(ByeDpiRunner.describeExit(proxy.exitCode ?: NativeBridge.ERR_EXITED), retryable = true)
            }
        } catch (t: Throwable) {
            if (hevStarted) runCatching { TProxy.TProxyStopService() }
            runCatching { pfd.close() }
            proxy.stop()
            if (t is StartException) throw t
            throw StartException("Tünel başlatılamadı: ${t.message ?: t.javaClass.simpleName}", retryable = true, cause = t)
        }

        run = Run(cfg, proxy, pfd, SystemClock.elapsedRealtime())
        Log.i(TAG, "motor calisiyor: port ${cfg.socksPort}, yontem ${cfg.methodName}, dns ${cfg.dnsName}")
        return cfg
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
