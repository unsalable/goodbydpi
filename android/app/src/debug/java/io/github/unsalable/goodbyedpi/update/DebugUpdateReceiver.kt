package io.github.unsalable.goodbyedpi.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.diag.ConnectionTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YALNIZCA HATA AYIKLAMA DERLEMESI (src/debug). Guncelleyiciyi ve baglanti testini adb'den
 * surmek icin. Alici disa acik ama android.permission.DUMP istiyor: bu izin yalnizca shell/
 * sistemde var, baska uygulamalar tetikleyemez. Sonuclar logcat'te GDPI_UPDATE etiketiyle.
 *
 *   adb shell am broadcast -n <pkg>/io.github.unsalable.goodbyedpi.update.DebugUpdateReceiver \
 *       --es cmd <komut> [secenekler]
 *
 * Komutlar:
 *   state                         o anki durumu yazar
 *   reset                         lastUpdateCheck / dismissedUpdate / pendingUpdate'i sifirlar
 *   check  [--es api URL]         UpdateManager.checkNow (sinirsiz denetim)
 *   open   [--es api URL]         lastUpdateCheck=0 yapip UpdateManager.onAppOpen: otomatik akis
 *   update                        UpdateManager.startUpdate (Available/Failed durumunda)
 *   install --es apk YOL          yerel APK'yi gercek kurulum yolundan (dogrula + oturum) gecirir;
 *                                 YOL mutlak ya da uygulamanin cache klasorune gore
 *   abandon                       bu uygulamanin acik kurulum oturumlarini birakir
 *   conntest [--ei socks PORT] [--es hosts a,b]   ConnectionTester.run
 *
 * api verilirse http:// adreslere de izin verilir (yerel test sunucusu, 10.0.2.2).
 */
class DebugUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        startStateLogger()
        val cmd = intent.getStringExtra("cmd") ?: "state"
        Log.i(TAG, "komut: $cmd (calisan surum ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}, ${app.packageName})")

        intent.getStringExtra("api")?.let { api ->
            UpdateManager.apiUrl = api
            UpdateManager.allowHttp = api.startsWith("http://")
            Log.i(TAG, "api=$api")
        }

        // Yayin donunce surec onbellege alinip dondurulabiliyor (Android 12+ freezer); is
        // bitene kadar yayini acik tutariz. Arka plan yayininin siniri 60 sn.
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(55_000) { runCommand(app, cmd, intent) }
                    ?: Log.w(TAG, "komut zaman asimina ugradi: $cmd")
            } catch (e: Throwable) {
                Log.e(TAG, "komut hatasi: $cmd", e)
            } finally {
                Log.i(TAG, "SON durum: ${UpdateManager.state.value}")
                pending.finish()
            }
        }
    }

    private suspend fun runCommand(app: Context, cmd: String, intent: Intent) {
        when (cmd) {
            "state" -> Unit
            "reset" -> {
                SettingsRepository.get(app).update {
                    it.copy(lastUpdateCheck = 0L, dismissedUpdate = null, pendingUpdate = null)
                }
                Log.i(TAG, "ayarlar sifirlandi")
            }
            "check" -> {
                UpdateManager.checkNow(app)
                awaitSettled()
            }
            "open" -> {
                SettingsRepository.get(app).update { it.copy(lastUpdateCheck = 0L) }
                UpdateManager.onAppOpen(app)
                awaitSettled()
            }
            "update" -> {
                UpdateManager.startUpdate(app)
                awaitSettled()
            }
            "install" -> {
                val path = intent.getStringExtra("apk") ?: run {
                    Log.e(TAG, "--es apk YOL gerekli")
                    return
                }
                val file = File(path).takeIf { it.isAbsolute } ?: File(app.cacheDir, path)
                if (!file.isFile) {
                    Log.e(TAG, "dosya yok: $file")
                    return
                }
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName ?: "0"
                Log.i(TAG, "yerel kurulum: $file (${file.length()} bayt, surum $version)")
                UpdateManager.installLocal(app, file, version)
                awaitSettled()
            }
            "abandon" -> {
                // Onay bekleyen oturumlari kapatir (paylasilan emulatorde onay ekrani ortada kalmasin).
                val pi = app.packageManager.packageInstaller
                pi.mySessions.forEach {
                    Log.i(TAG, "oturum birakiliyor: ${it.sessionId}")
                    runCatching { pi.abandonSession(it.sessionId) }.onFailure { e -> Log.w(TAG, "birakilamadi", e) }
                }
            }
            "socksurl" -> {
                // Karsilastirma icin: platformun HttpURLConnection'i SOCKS vekiline adi mi IP'yi mi
                // gonderiyor? (ConnectionTester bu yuzden SOCKS yolunu elle kuruyor.)
                val port = intent.getIntExtra("socks", -1)
                val host = intent.getStringExtra("hosts") ?: "example.com"
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                val r = runCatching {
                    val c = java.net.URL("https://$host/").openConnection(proxy) as java.net.HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    try { c.responseCode } finally { c.disconnect() }
                }
                Log.i(TAG, "socksurl $host -> $r")
            }
            "conntest" -> {
                val socks = intent.getIntExtra("socks", -1).takeIf { it > 0 }
                val hosts = intent.getStringExtra("hosts")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: ConnectionTester.DEFAULT_HOSTS
                val started = System.nanoTime()
                val results = ConnectionTester.run(socks, hosts)
                val total = (System.nanoTime() - started) / 1_000_000
                results.forEach { Log.i(TAG, "conntest socks=$socks $it") }
                Log.i(TAG, "conntest bitti: ${results.count { it.ok }}/${results.size} ok, $total ms")
            }
            else -> Log.e(TAG, "bilinmeyen komut: $cmd")
        }
    }

    /** UpdateManager'in isi bitene kadar bekler (sonuc durum logger'da gorunur). */
    private suspend fun awaitSettled() {
        delay(200)
        while (UpdateManager.isBusy) delay(100)
        // Installing: sistemin sonucu (onay ekrani / hata / surecin yenilenmesi) birazdan gelir.
        if (UpdateManager.state.value is UpdateState.Installing) delay(5_000)
    }

    private companion object {
        const val TAG = "GDPI_UPDATE"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loggerStarted = AtomicBoolean(false)

        fun startStateLogger() {
            if (!loggerStarted.compareAndSet(false, true)) return
            UpdateManager.state.onEach { Log.i(TAG, "durum: $it") }.launchIn(scope)
            UpdateManager.justUpdatedTo.onEach { if (it != null) Log.i(TAG, "justUpdatedTo=$it") }.launchIn(scope)
        }
    }
}
