package io.github.unsalable.goodbyedpi.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// SOZLESME (wave 2): asagidaki public imzalar sabit; yeni uyeler yalnizca eklenebilir.

/** GitHub'da bulunan, kurulabilir bir Android surumu. */
data class ReleaseInfo(
    /** "1.2.3" (onek ve 'v' atilmis) */
    val version: String,
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** kucuk harfli 64 hex; release notunda / asset digest'inde yoksa null */
    val sha256: String?,
    val notes: String,
    val htmlUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val doneBytes: Long, val totalBytes: Long) : UpdateState
    data class Verifying(val info: ReleaseInfo) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState

    /** "Bilinmeyen uygulamalari yukle" izni yok; arayuz ayar ekranina yonlendirir. */
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState

    /** [message] kullaniciya gosterilir (Turkce). */
    data class Failed(val info: ReleaseInfo?, val message: String) : UpdateState
}

/**
 * Guncelleme akisinin tek sahibi: denetle -> (otomatik) indir -> dogrula -> kur.
 *
 * Masaustundeki gibi "otomatik guncelle" aciksa kullaniciya sormadan yeni surumu indirip
 * kurar; Android'de son adimda sistem onayi gerekebilir (UpdateInstaller'a bakin). Butun is
 * uygulama omurlu tek bir kapsamda (SupervisorJob + IO) doner; ayni anda tek bir is calisir.
 * Hicbir public fonksiyon firlatmaz ya da cagiran is parcacigini bekletmez.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"

    /** Otomatik denetimler arasi en kisa sure. GitHub kimliksiz isteklere saatte 60 hak veriyor. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Guncellemeden sonraki ilk acilista kurulan surum; bir kez gosterilip tuketilir. */
    private val _justUpdatedTo = MutableStateFlow<String?>(null)
    val justUpdatedTo: StateFlow<String?> = _justUpdatedTo.asStateFlow()

    private val lock = Any()
    private var job: Job? = null // lock ile korunur

    /** dismiss() baglamsiz cagrildigi icin ilk cagrida uygulama baglami saklanir. */
    @Volatile
    private var appContext: Context? = null

    /** Ayni surum icin arka planda tekrar tekrar bildirim cikmasin (surec omru boyunca). */
    @Volatile
    private var lastNotifiedVersion: String? = null

    // Hata ayiklama kancasi (src/debug) yerel bir test sunucusuna yonlendirebilsin diye
    // degistirilebilir; surum derlemesinde hic dokunulmaz.
    @Volatile
    internal var apiUrl: String = UpdateChecker.DEFAULT_API_URL

    @Volatile
    internal var allowHttp: Boolean = false

    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Bir denetim/indirme/kurulum hazirligi suruyor mu (hata ayiklama kancasi bekler). */
    internal val isBusy: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Uygulama acildiginda: autoUpdate aciksa ve son kontrolden 6 saat gectiyse kontrol eder. */
    fun onAppOpen(context: Context) {
        val app = remember(context) ?: return
        scope.launch { safely("acilis") { consumePendingUpdate(app) } }
        launchExclusive("onAppOpen") {
            val s = settings(app).current
            if (!s.autoUpdate) return@launchExclusive

            // Arka plan denetimi (VPN servisi) yeni surumu bulup bildirim gostermis olabilir;
            // o zaman 6 saatlik sinir dolmamistir ama indirmeye simdi baslamaliyiz.
            val known = (state.value as? UpdateState.Available)?.info
            if (known != null) {
                if (!VersionUtil.sameVersion(known.version, s.dismissedUpdate)) performUpdate(app, known)
                return@launchExclusive
            }
            if (!isDue(s.lastUpdateCheck)) return@launchExclusive
            val info = check(app, userInitiated = false) ?: return@launchExclusive
            performUpdate(app, info)
        }
    }

    /** Kullanici "Guncellemeleri denetle" dedi: sinir yok. */
    fun checkNow(context: Context) {
        val app = remember(context) ?: return
        launchExclusive("checkNow") { check(app, userInitiated = true) }
    }

    /**
     * Arka plan kontrolu: uygulama uzun sure acilmasa da VPN servisi calisiyor.
     * Servis motor ayaga kalkinca cagirir; autoUpdate aciksa ve 6 saat gectiyse bakar,
     * yeni surum varsa "Güncelleme hazır" bildirimi gosterir. Hicbir zaman firlatmaz.
     */
    fun backgroundCheck(context: Context) {
        val app = remember(context) ?: return
        launchExclusive("backgroundCheck") {
            val s = settings(app).current
            if (!s.autoUpdate || !isDue(s.lastUpdateCheck)) return@launchExclusive
            val info = check(app, userInitiated = false) ?: return@launchExclusive
            if (lastNotifiedVersion != info.version) {
                lastNotifiedVersion = info.version
                UpdateNotifier.showAvailable(app, info)
            }
        }
    }

    /** Available / Failed / NeedsPermission durumunda indir -> dogrula -> kur. */
    fun startUpdate(context: Context) {
        val app = remember(context) ?: return
        val info = infoOf(state.value) ?: return
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Verifying ||
            state.value is UpdateState.Installing
        ) {
            return
        }
        launchExclusive("startUpdate") { performUpdate(app, info) }
    }

    /** Seridi gizler (bu surum icin tekrar sormaz). */
    fun dismiss() {
        val current = state.value
        val info = infoOf(current)
        synchronized(lock) {
            // Kurulum sisteme teslim edildiyse geri alinamaz; indirme ise iptal edilebilir.
            if (current !is UpdateState.Installing) job?.cancel()
        }
        if (current !is UpdateState.Installing) _state.value = UpdateState.Idle
        val app = appContext
        if (info != null && app != null) {
            scope.launch {
                safely("dismiss") { settings(app).update { it.copy(dismissedUpdate = info.version) } }
            }
        }
    }

    fun consumeJustUpdated() {
        _justUpdatedTo.value = null
    }

    /**
     * Android 8+'da "Bilinmeyen uygulamalari yukle" izin ekranini acan Intent (NeedsPermission
     * durumunda arayuz baslatir, donuste startUpdate'i yeniden cagirir). Daha eskide null.
     */
    fun installPermissionIntent(context: Context): Intent? = UpdateInstaller.permissionIntent(context)

    // ------------------------------------------------------------------ ic akis

    /** Denetim yapar, durumu gunceller; kurulacak yeni surum varsa onu dondurur. */
    private suspend fun check(app: Context, userInitiated: Boolean): ReleaseInfo? {
        _state.value = UpdateState.Checking
        val result = UpdateChecker(apiUrl).check(currentVersion)
        Log.i(TAG, "Denetim sonucu ($apiUrl): $result")

        // Cevrimdisiyken sayaci baslatmiyoruz: ag gelince (bir sonraki acilista) yeniden denensin.
        if (result !is CheckResult.Error || result.serverAnswered) {
            settings(app).update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        }

        return when (result) {
            CheckResult.NoUpdate -> {
                _state.value = UpdateState.UpToDate
                null
            }
            is CheckResult.Error -> {
                // Otomatik denetimin hatasi kullaniciyi rahatsiz etmesin (masaustunde de sessiz).
                _state.value = if (userInitiated) UpdateState.Failed(null, result.message) else UpdateState.Idle
                null
            }
            is CheckResult.Found -> {
                val dismissed = settings(app).current.dismissedUpdate
                if (!userInitiated && VersionUtil.sameVersion(result.info.version, dismissed)) {
                    _state.value = UpdateState.Idle
                    null
                } else {
                    _state.value = UpdateState.Available(result.info)
                    result.info
                }
            }
        }
    }

    private suspend fun performUpdate(app: Context, info: ReleaseInfo) {
        if (!UpdateInstaller.canInstall(app)) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        _state.value = UpdateState.Downloading(info, 0, info.sizeBytes)
        val ctx = currentCoroutineContext()
        val file = try {
            UpdateDownloader(updatesDir(app), allowHttp = allowHttp).download(info) { done, total ->
                // dismiss() isi iptal ettikten sonra gec gelen bir ilerleme Idle'i ezmesin.
                if (ctx.isActive) _state.value = UpdateState.Downloading(info, done, total)
            }
        } catch (e: UpdateException) {
            Log.w(TAG, "Indirme basarisiz: ${e.message}", e.cause)
            _state.value = UpdateState.Failed(info, e.message ?: UpdateDownloader.MSG_DOWNLOAD)
            return
        }
        installFile(app, info, file)
    }

    /**
     * Dogrulanmis dosyayi kurar. Hata ayiklama kancasi da ayni yolu kullanir ki emulatordeki
     * kendi-kendini-guncelleme denemesi gercek akisi sinasin.
     */
    internal suspend fun installFile(app: Context, info: ReleaseInfo, file: File) {
        _state.value = UpdateState.Verifying(info)
        var pendingSet = false
        try {
            val archiveVersion = UpdateInstaller.verifyArchive(app, file)
            if (archiveVersion != null && !VersionUtil.sameVersion(archiveVersion, info.version)) {
                Log.w(TAG, "Etiket ${info.version} ama APK surumu $archiveVersion")
            }
            // Kurulum basarili olursa surec olduruluyor; "guncellendi" bilgisini simdiden yaz.
            settings(app).update { it.copy(pendingUpdate = info.version) }
            pendingSet = true
            _state.value = UpdateState.Installing(info)
            UpdateInstaller.install(app, file, info.version)
        } catch (e: UpdateException) {
            Log.w(TAG, "Kurulum hazirlanamadi: ${e.message}", e.cause)
            if (pendingSet) settings(app).update { it.copy(pendingUpdate = null) }
            file.delete()
            _state.value = UpdateState.Failed(info, e.message ?: UpdateInstaller.MSG_SESSION)
        }
    }

    /** InstallStatusReceiver'dan. [done] ayar yazimi bitince cagrilir (goAsync). */
    internal fun onInstallResult(context: Context, status: Int, message: String?, done: () -> Unit = {}) {
        val app = remember(context)
        val info = infoOf(state.value)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Onay ekrani acik; kullanici karar verene kadar "Kuruluyor" gorunur kalir.
                if (info != null) _state.value = UpdateState.Installing(info)
                done()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Kendi guncellememizde buraya genelde gelinmez (surec yenilenir).
                _state.value = UpdateState.Idle
                done()
            }
            else -> {
                val msg = UpdateInstaller.statusMessage(status, message) ?: "Kurulum başarısız"
                _state.value = UpdateState.Failed(info, msg)
                if (app == null) {
                    done()
                    return
                }
                scope.launch {
                    try {
                        safely("kurulum sonucu") {
                            settings(app).update { it.copy(pendingUpdate = null) }
                            updatesDir(app).listFiles()?.forEach { it.delete() }
                        }
                    } finally {
                        done()
                    }
                }
            }
        }
    }

    /** MY_PACKAGE_REPLACED: kendi baslattigimiz guncelleme bittiyse kisa bir bildirim. */
    internal fun onPackageReplaced(context: Context) {
        val app = remember(context) ?: return
        runCatching {
            val pending = settings(app).current.pendingUpdate
            if (VersionUtil.sameVersion(pending, currentVersion)) UpdateNotifier.showUpdated(app, currentVersion)
        }.onFailure { Log.w(TAG, "onPackageReplaced", it) }
    }

    /** Hata ayiklama kancasi: yerel bir APK'yi gercek kurulum yolundan gecirir. */
    internal fun installLocal(context: Context, file: File, version: String): Boolean {
        val app = remember(context) ?: return false
        val info = ReleaseInfo(
            version = version,
            tag = "local-v$version",
            assetName = file.name,
            downloadUrl = "file://${file.absolutePath}",
            sizeBytes = file.length(),
            sha256 = null,
            notes = "",
            htmlUrl = "",
        )
        return launchExclusive("installLocal") {
            if (!UpdateInstaller.canInstall(app)) {
                _state.value = UpdateState.NeedsPermission(info)
                return@launchExclusive
            }
            installFile(app, info, file)
        }
    }

    private suspend fun consumePendingUpdate(app: Context) {
        UpdateNotifier.cancelInfo(app)
        val pending = settings(app).current.pendingUpdate
        if (pending != null) {
            when {
                VersionUtil.sameVersion(pending, currentVersion) -> {
                    _justUpdatedTo.value = currentVersion
                    settings(app).update { it.copy(pendingUpdate = null) }
                }
                // Kurulan surum beklenenden de yeni (elle kurulmus) ya da okunamaz: eski kayit.
                !VersionUtil.isNewer(pending, currentVersion) ->
                    settings(app).update { it.copy(pendingUpdate = null) }
                // Beklenen surum daha yeni: kurulum hala onay bekliyor olabilir, dokunma.
                else -> Unit
            }
        }
        // Kurulmus ya da yarim kalmis APK'lar yer kaplamasin (calisan bir is yoksa).
        val busy = synchronized(lock) { job?.isActive == true }
        if (!busy) updatesDir(app).listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun launchExclusive(what: String, block: suspend () -> Unit): Boolean = synchronized(lock) {
        if (job?.isActive == true) {
            Log.d(TAG, "$what atlandi: baska bir guncelleme isi suruyor")
            return false
        }
        job = scope.launch { safely(what) { block() } }
        true
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Guncelleme kodu uygulamayi asla dusurmemeli (servis surecinde de calisiyor).
            Log.w(TAG, "$what beklenmedik hata", e)
            val info = infoOf(state.value)
            _state.value = if (info != null) UpdateState.Failed(info, "Güncelleme başarısız") else UpdateState.Idle
        }
    }

    private fun remember(context: Context): Context? = try {
        context.applicationContext.also { appContext = it }
    } catch (e: Exception) {
        null
    }

    private fun settings(app: Context) = SettingsRepository.get(app)

    private fun updatesDir(app: Context) = File(app.cacheDir, "updates")

    internal fun isDue(lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean =
        // Saat geri alinmissa (lastCheck gelecekte) beklemek yerine denetle.
        lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS

    private fun infoOf(s: UpdateState): ReleaseInfo? = when (s) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Verifying -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }
}
