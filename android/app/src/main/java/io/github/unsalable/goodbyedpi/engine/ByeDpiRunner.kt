package io.github.unsalable.goodbyedpi.engine

import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tek bir byedpi calismasi: kendi is parcaciginda [NativeBridge.byedpiStart] (bloklar), hazir
 * olana kadar bekleme ve temiz durdurma. Ayni anda yalnizca bir ornek calisabilir (yerel kutuphane
 * surec basina tek vekil tutuyor); sirayi DpiEngine saglar.
 *
 * Beklenmedik cikis (durdurma istenmeden donus) [onUnexpectedExit] ile byedpi is parcaciginda
 * bildirilir; watchdog buradan tetiklenir.
 */
internal class ByeDpiRunner(
    private val args: List<String>,
    val port: Int,
    private val onUnexpectedExit: ((runner: ByeDpiRunner, code: Int) -> Unit)? = null,
) {
    @Volatile
    private var stopRequested = false

    @Volatile
    var exitCode: Int? = null
        private set

    private val thread = Thread(::runLoop, "gdpi-byedpi").apply { isDaemon = true }

    val isAlive: Boolean
        get() = thread.isAlive

    private fun runLoop() {
        // stop() is parcacigi daha baslamadan geldiyse (NEW durumunu gorup hemen donmustur)
        // yerel koda hic girme: yoksa kimsenin durdurmayacagi bir vekil kalirdi.
        if (stopRequested) {
            exitCode = NativeBridge.OK
            return
        }
        val code = try {
            NativeBridge.byedpiStart(args.toTypedArray())
        } catch (t: Throwable) {
            // UnsatisfiedLinkError/OOM: is parcacigi sessizce olmesin, motor hata olarak gorsun.
            Log.e(TAG, "byedpiStart firlatti", t)
            NativeBridge.ERR_INTERNAL
        }
        exitCode = code
        if (!stopRequested) {
            Log.w(TAG, "byedpi beklenmedik sekilde bitti: $code")
            runCatching { onUnexpectedExit?.invoke(this, code) }
                .onFailure { Log.e(TAG, "cikis dinleyicisi firlatti", it) }
        }
    }

    /**
     * Is parcacigini baslatir ve byedpi porta baglanti kabul edene kadar bekler. Hazir olmazsa
     * kendini durdurup [DpiEngine.StartException] firlatir; cagiranin temizleyecegi bir sey kalmaz.
     */
    fun start(timeoutMs: Long = READY_TIMEOUT_MS) {
        if (stopRequested) throw DpiEngine.StartException(describeExit(NativeBridge.OK), retryable = true)
        thread.start()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            exitCode?.let { code ->
                thread.join(JOIN_STEP_MS)
                throw DpiEngine.StartException(describeExit(code), retryable = code != NativeBridge.ERR_ARGS, portBusy = code == NativeBridge.ERR_START)
            }
            if (probe()) {
                // Baska bir surec ayni portu yeni kaptiysa baglanti ona gitmis olabilir; o durumda
                // bizim bind'imiz hemen dusup is parcacigi biter. Kisa bir bekleme bunu yakalar.
                thread.join(20)
                exitCode?.let { code ->
                    throw DpiEngine.StartException(describeExit(code), retryable = true, portBusy = code == NativeBridge.ERR_START)
                }
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                stop()
                throw DpiEngine.StartException("byedpi ${timeoutMs / 1000} saniye içinde hazır olmadı.", retryable = true)
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Durdurur ve is parcacigini bekler. byedpiStop yerel koda girmeden gelirse etkisiz kaldigi
     * icin (kilitlenemez; sonraki mesru bir baslatmayi oldururdu) is parcacigi yasadikca tekrarlanir.
     * @return is parcacigi zamaninda bittiyse true
     */
    fun stop(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        stopRequested = true
        if (thread.state == Thread.State.NEW) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (thread.isAlive && SystemClock.elapsedRealtime() < deadline) {
            runCatching { NativeBridge.byedpiStop() }
            thread.join(JOIN_STEP_MS)
        }
        val ok = !thread.isAlive
        if (!ok) Log.e(TAG, "byedpi ${timeoutMs} ms icinde durmadi")
        return ok
    }

    private fun probe(): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_CONNECT_MS) }
            true
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val TAG = "GdpiByeDpi"
        const val READY_TIMEOUT_MS = 3000L
        const val STOP_TIMEOUT_MS = 3000L
        private const val POLL_MS = 15L
        private const val JOIN_STEP_MS = 50L
        private const val PROBE_CONNECT_MS = 100

        internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Kullaniciya gosterilecek neden (Turkce). */
        fun describeExit(code: Int): String = when (code) {
            NativeBridge.OK -> "byedpi durdu."
            NativeBridge.ERR_START -> "byedpi yerel portu açamadı."
            NativeBridge.ERR_ARGS -> "byedpi ayarları geçersiz (yöntem ayarlarını kontrol edin)."
            NativeBridge.ERR_BUSY -> "byedpi zaten çalışıyor."
            NativeBridge.ERR_EXITED -> "byedpi beklenmedik şekilde kapandı."
            NativeBridge.ERR_INTERNAL -> "byedpi iç hatası."
            else -> "byedpi hata kodu $code."
        }
    }
}
