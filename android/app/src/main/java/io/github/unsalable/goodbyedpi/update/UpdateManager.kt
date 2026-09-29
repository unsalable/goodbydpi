package io.github.unsalable.goodbyedpi.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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
 * kurar; uygulama arka plandayken de (periyodik is, VPN servisi). Android 12+'da kurulum
 * kaydinin sahibi bizsek kurulum onaysiz gecer; degilse sistem onayi gerekir: arayuz
 * gorunurse onay ekrani hemen acilir, degilse "Guncelleme onay bekliyor" bildirimi cikar ve
 * uygulama bir sonraki one geldiginde onay ekrani acilir. Butun is uygulama omurlu tek bir
 * kapsamda (SupervisorJob + IO) doner; ayni anda tek bir is calisir. Hicbir public fonksiyon
 * firlatmaz ya da cagiran is parcacigini bekletmez.
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

    /** consumePendingUpdate ayni anda iki kez (ViewModel + etkinlik one gelisi) calismasin. */
    private val consumeMutex = Mutex()

    /** dismiss() baglamsiz cagrildigi icin ilk cagrida uygulama baglami saklanir. */
    @Volatile
    private var appContext: Context? = null

    private val attached = AtomicBoolean(false)

    /** Ayni surum icin arka planda tekrar tekrar bildirim cikmasin (surec omru boyunca). */
    @Volatile
    private var lastNotifiedVersion: String? = null

    // Onay bekleyen kurulum oturumu (STATUS_PENDING_USER_ACTION). Onay ekrani gosterildiyse
    // [confirmShown]; gosterilemediyse (arka plan) uygulama one gelince acilir.
    @Volatile
    private var pendingConfirm: Intent? = null

    @Volatile
    private var confirmShown = false

    @Volatile
    private var pendingSessionId: Int = -1

    // Hata ayiklama kancasi (src/debug) yerel bir test sunucusuna yonlendirebilsin diye
    // degistirilebilir; surum derlemesinde hic dokunulmaz.
    @Volatile
    internal var apiUrl: String = UpdateChecker.DEFAULT_API_URL

    @Volatile
    internal var allowHttp: Boolean = false

    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Bir denetim/indirme/kurulum hazirligi suruyor mu (hata ayiklama kancasi bekler). */
    internal val isBusy: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Uygulamanin bir etkinligi su an ekranda mi (baslatilmis)? Arka plan kurallari buna gore. */
    internal val isUiVisible: Boolean get() = UiTracker.started > 0

    /**
     * Uygulama acildiginda / one geldiginde: autoUpdate aciksa ve son kontrolden 6 saat gectiyse
     * (ya da daha once bulunmus bir surum bekliyorsa) denetler, bulursa indirip kurar.
     */
    fun onAppOpen(context: Context) {
        val app = remember(context) ?: return
        scope.launch { safely("acilis") { consumePendingUpdate(app) } }
        launchExclusive("onAppOpen") { autoFlow(app, userPresent = true) }
    }

    /** Kullanici "Guncellemeleri denetle" dedi: sinir yok. */
    fun checkNow(context: Context) {
        val app = remember(context) ?: return
        launchExclusive("checkNow") { check(app, userInitiated = true) }
    }

    /**
     * Arka plan kontrolu: VPN servisi motor ayaga kalkinca ve periyodik is (UpdateJobService)
     * 6 saatte bir cagirir. autoUpdate aciksa ve 6 saat gectiyse bakar; yeni surum varsa
     * arayuz acik olmasa da indirip kurar. Hicbir zaman firlatmaz.
     */
    fun backgroundCheck(context: Context) {
        backgroundCheck(context) {}
    }

    /** [onDone] is bittiginde cagrilir; baska bir is surdugu icin baslamadiysa false doner (cagrilmaz). */
    internal fun backgroundCheck(context: Context, onDone: () -> Unit): Boolean {
        val app = remember(context) ?: return false
        return launchExclusive("backgroundCheck", onDone) { autoFlow(app, userPresent = isUiVisible) }
    }

    /**
     * Available / Failed / NeedsPermission durumunda indir -> dogrula -> kur. Installing'de
     * (onay bekleniyor) onay ekranini yeniden acar.
     */
    fun startUpdate(context: Context) {
        val app = remember(context) ?: return
        val current = state.value
        if (current is UpdateState.Installing) {
            launchConfirm(app)
            return
        }
        val info = infoOf(current) ?: return
        if (current is UpdateState.Downloading || current is UpdateState.Verifying) return
        launchExclusive("startUpdate") {
            // Kullanici acikca istedi: erteleme ve "bozuk surum" kaydi bu surum icin kalkar.
            UpdateMemoStore.update(app) { m ->
                m.copy(
                    snoozedVersion = m.snoozedVersion.takeUnless { VersionUtil.sameVersion(it, info.version) },
                    badVersion = m.badVersion.takeUnless { VersionUtil.sameVersion(it, info.version) },
                )
            }
            performUpdate(app, info)
        }
    }

    /**
     * Seridi gizler. "Daha sonra" (Available) ve suren bir indirmeden vazgecmek 24 saatlik bir
     * ERTELEMEDIR; hata / izin afisini kapatmak hicbir sey kaydetmez (bir sonraki denetim
     * yeniden dener). Onay bekleyen kurulumda oturum birakilir (o da 24 saat erteler).
     */
    fun dismiss() {
        val current = state.value
        val info = infoOf(current)
        val app = appContext
        if (current is UpdateState.Installing) {
            abandonPendingInstall(app)
            _state.value = UpdateState.Idle
            return
        }
        synchronized(lock) { job?.cancel() }
        _state.value = UpdateState.Idle
        val snooze = current is UpdateState.Available || current is UpdateState.Downloading ||
            current is UpdateState.Verifying
        if (snooze && info != null && app != null) {
            scope.launch {
                safely("dismiss") {
                    val now = System.currentTimeMillis()
                    UpdateMemoStore.update(app) { it.copy(snoozedVersion = info.version, snoozedAt = now) }
                }
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

    /**
     * Otomatik akis (acilis, one gelis, VPN servisi, periyodik is). [userPresent]: kullanici
     * uygulamada; o zaman daha once bulunmus bir surum 6 saat beklemeden yeniden denenir.
     */
    private suspend fun autoFlow(app: Context, userPresent: Boolean) {
        val s = settings(app).current
        if (!s.autoUpdate) return
        val now = System.currentTimeMillis()
        val memo = UpdateMemoStore.read(app)
        when (val st = state.value) {
            // Sistem onayi bekleniyor: yeni oturum acmak onceki onay ekranini gecersiz kilardi.
            is UpdateState.Installing -> return
            is UpdateState.Available -> {
                if (!memo.blocksAuto(st.info.version, now)) performUpdate(app, st.info)
                return
            }
            // Izin ekranindan donuldu: izin verildiyse kaldigi yerden devam.
            is UpdateState.NeedsPermission -> if (UpdateInstaller.canInstall(app)) {
                performUpdate(app, st.info)
                return
            }
            else -> Unit
        }
        // Surec olmus olabilir (LMK, VPN kapandi): hatirlanan surum 6 saati beklemesin. Ayni
        // surecte bir hatadan (Failed) sonra her one geliste yeniden indirmeye kalkmayiz.
        val pending = if (userPresent && state.value !is UpdateState.Failed) memo.pendingFor(currentVersion, now) else null
        if (pending == null && !isDue(s.lastUpdateCheck, now)) return
        val info = check(app, userInitiated = false) ?: return
        performUpdate(app, info)
    }

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
                UpdateMemoStore.update(app) { it.copy(available = null) }
                _state.value = UpdateState.UpToDate
                null
            }
            is CheckResult.Error -> {
                // Otomatik denetimin hatasi kullaniciyi rahatsiz etmesin (masaustunde de sessiz).
                _state.value = if (userInitiated) UpdateState.Failed(null, result.message) else UpdateState.Idle
                null
            }
            is CheckResult.Found -> {
                val version = result.info.version
                val memo = UpdateMemoStore.update(app) { it.copy(available = version) }
                if (!userInitiated && memo.blocksAuto(version, System.currentTimeMillis())) {
                    Log.i(TAG, "$version otomatik akista atlaniyor (ertelendi ya da dogrulanamadi)")
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
            // Arka planda afisi goren yok: izin gerektigini bir kez bildirimle soyle.
            if (!isUiVisible && lastNotifiedVersion != info.version) {
                lastNotifiedVersion = info.version
                UpdateNotifier.showAvailable(app, info)
            }
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
            val archiveVersion = try {
                UpdateInstaller.verifyArchive(app, file)
            } catch (e: UpdateException) {
                // Paket/surum kodu/imza hatasi tekrar indirmekle duzelmez: otomatik akis bu
                // surumu bir daha indirmesin (elle "Tekrar dene" yine dener).
                if (e.message in UpdateInstaller.PERMANENT_ERRORS) {
                    UpdateMemoStore.update(app) { it.copy(badVersion = info.version) }
                }
                throw e
            }
            if (archiveVersion != null && !VersionUtil.sameVersion(archiveVersion, info.version)) {
                Log.w(TAG, "Etiket ${info.version} ama APK surumu $archiveVersion")
            }
            // Afis dogrulama sirasinda kapatildiysa kuruluma gecme.
            currentCoroutineContext().ensureActive()
            // Kurulum basarili olursa surec olduruluyor; "guncellendi" bilgisini simdiden yaz.
            settings(app).update { it.copy(pendingUpdate = info.version) }
            pendingSet = true
            clearPendingConfirm(app)
            _state.value = UpdateState.Installing(info)
            pendingSessionId = UpdateInstaller.install(app, file, info.version)
        } catch (e: UpdateException) {
            Log.w(TAG, "Kurulum hazirlanamadi: ${e.message}", e.cause)
            if (pendingSet) settings(app).update { it.copy(pendingUpdate = null) }
            file.delete()
            _state.value = UpdateState.Failed(info, e.message ?: UpdateInstaller.MSG_SESSION)
        } catch (e: CancellationException) {
            file.delete()
            throw e
        }
    }

    /**
     * InstallStatusReceiver'dan, STATUS_PENDING_USER_ACTION: sistem onayi gerekiyor (ilk
     * kendi-guncelleme ya da onaysiz kurulum kosullari tutmadi). Arayuz gorunurse onay ekrani
     * hemen acilir. Bildirim HER ZAMAN da gosterilir: VPN'in on plan servisi sureci "on planda"
     * gosterse de Android 10+ arka plandan etkinlik baslatmayi sessizce engelliyor; o durumda
     * tek yol bildirim ve uygulamanin bir sonraki one gelisi (UPD-1).
     */
    internal fun onConfirmRequired(context: Context, confirm: Intent, sessionId: Int, version: String?) {
        val app = remember(context) ?: return
        pendingConfirm = confirm
        confirmShown = false
        if (sessionId > 0) pendingSessionId = sessionId
        val info = infoOf(state.value) ?: version?.let { placeholderInfo(it) }
        if (info != null) _state.value = UpdateState.Installing(info)
        val shown = launchConfirm(app)
        UpdateNotifier.showConfirm(app, confirm, silent = shown)
    }

    /** InstallStatusReceiver'dan (onay disindaki sonuclar). [done] ayar yazimi bitince cagrilir (goAsync). */
    internal fun onInstallResult(
        context: Context,
        status: Int,
        message: String?,
        version: String? = null,
        done: () -> Unit = {},
    ) {
        val app = remember(context)
        val info = infoOf(state.value)
        if (app != null) clearPendingConfirm(app)
        pendingSessionId = -1
        when (status) {
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
                val failedVersion = version ?: info?.version
                scope.launch {
                    try {
                        safely("kurulum sonucu") {
                            settings(app).update { it.copy(pendingUpdate = null) }
                            updatesDir(app).listFiles()?.forEach { it.delete() }
                            if (failedVersion != null) rememberInstallFailure(app, status, failedVersion)
                        }
                    } finally {
                        done()
                    }
                }
            }
        }
    }

    /**
     * Kullanici onay ekraninda "Iptal" dediyse bu bir "simdi degil"dir: 24 saat ertelenir.
     * Paket sistemce reddedildiyse (imza catismasi, uyumsuz, gecersiz) otomatik akis o surumu
     * bir daha indirmez; yer yok / engellendi gibi gecici hatalar bir sonraki denetimde denenir.
     */
    private fun rememberInstallFailure(app: Context, status: Int, version: String) {
        val now = System.currentTimeMillis()
        when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                UpdateMemoStore.update(app) { it.copy(snoozedVersion = version, snoozedAt = now) }
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID ->
                UpdateMemoStore.update(app) { it.copy(badVersion = version) }
            else -> Unit
        }
    }

    /** MY_PACKAGE_REPLACED: kendi baslattigimiz guncelleme bittiyse kisa bir bildirim. */
    internal fun onPackageReplaced(context: Context) {
        val app = remember(context) ?: return
        runCatching {
            UpdateNotifier.cancelConfirm(app)
            val pending = settings(app).current.pendingUpdate
            if (VersionUtil.sameVersion(pending, currentVersion)) UpdateNotifier.showUpdated(app, currentVersion)
        }.onFailure { Log.w(TAG, "onPackageReplaced", it) }
    }

    /** Hata ayiklama kancasi: yerel bir APK'yi gercek kurulum yolundan gecirir. */
    internal fun installLocal(context: Context, file: File, version: String): Boolean {
        val app = remember(context) ?: return false
        val info = placeholderInfo(version).copy(
            assetName = file.name,
            downloadUrl = "file://${file.absolutePath}",
            sizeBytes = file.length(),
        )
        return launchExclusive("installLocal") {
            if (!UpdateInstaller.canInstall(app)) {
                _state.value = UpdateState.NeedsPermission(info)
                return@launchExclusive
            }
            installFile(app, info, file)
        }
    }

    private suspend fun consumePendingUpdate(app: Context) = consumeMutex.withLock {
        UpdateNotifier.cancelInfo(app)
        val pending = settings(app).current.pendingUpdate
        if (pending != null) {
            when {
                VersionUtil.sameVersion(pending, currentVersion) -> {
                    Log.i(TAG, "Guncelleme tamamlandi: $currentVersion")
                    _justUpdatedTo.value = currentVersion
                    settings(app).update { it.copy(pendingUpdate = null) }
                    UpdateNotifier.cancelConfirm(app)
                }
                // Kurulan surum beklenenden de yeni (elle kurulmus) ya da okunamaz: eski kayit.
                !VersionUtil.isNewer(pending, currentVersion) ->
                    settings(app).update { it.copy(pendingUpdate = null) }
                // Beklenen surum daha yeni: kurulum hala onay bekliyor olabilir, dokunma.
                else -> Unit
            }
        }
        // Artik kurulu olan (ya da daha eski) surumlerin kayitlari is gormez.
        UpdateMemoStore.update(app) { m ->
            m.copy(
                available = m.available?.takeIf { VersionUtil.isNewer(it, currentVersion) },
                snoozedVersion = m.snoozedVersion?.takeIf { VersionUtil.isNewer(it, currentVersion) },
                badVersion = m.badVersion?.takeIf { VersionUtil.isNewer(it, currentVersion) },
            )
        }
        // Kurulmus ya da yarim kalmis APK'lar yer kaplamasin (calisan bir is yoksa).
        val busy = synchronized(lock) { job?.isActive == true }
        if (!busy) updatesDir(app).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Onay ekranini one gelmis bir etkinlikten acar; acilabildiyse true. */
    private fun launchConfirm(app: Context): Boolean {
        val confirm = pendingConfirm ?: return false
        if (!isUiVisible) return false
        return try {
            val activity = UiTracker.resumed?.get()
            if (activity != null && !activity.isFinishing) {
                activity.startActivity(confirm)
            } else {
                app.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            confirmShown = true
            true
        } catch (e: Exception) {
            // Oturum suresi dolmus/birakilmis olabilir: kullanici "Tekrar dene" ile bastan baslar.
            Log.w(TAG, "Onay ekrani acilamadi", e)
            false
        }
    }

    private fun clearPendingConfirm(app: Context) {
        pendingConfirm = null
        confirmShown = false
        UpdateNotifier.cancelConfirm(app)
    }

    /**
     * Onay bekleyen oturumu birakir (afisten "Kapat"). Sistem bunu STATUS_FAILURE_ABORTED olarak
     * bildirir; onay ekraninda "Iptal" gibi 24 saatlik erteleme sayilir (rememberInstallFailure).
     */
    private fun abandonPendingInstall(app: Context?) {
        val sessionId = pendingSessionId
        pendingSessionId = -1
        if (app == null) return
        clearPendingConfirm(app)
        if (sessionId > 0) {
            runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
                .onFailure { Log.w(TAG, "Oturum $sessionId birakilamadi", it) }
        }
        scope.launch { safely("kurulum birakildi") { settings(app).update { it.copy(pendingUpdate = null) } } }
    }

    private fun launchExclusive(what: String, onDone: (() -> Unit)? = null, block: suspend () -> Unit): Boolean =
        synchronized(lock) {
            if (job?.isActive == true) {
                Log.d(TAG, "$what atlandi: baska bir guncelleme isi suruyor")
                return false
            }
            job = scope.launch {
                try {
                    safely(what) { block() }
                } finally {
                    onDone?.let { runCatching(it) }
                }
            }
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
        context.applicationContext.also {
            appContext = it
            attach(it)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Surecte bir kez: etkinlik yasam dongusunu izle (gercek gorunurluk + one geliste denetim)
     * ve periyodik isi planla. App.onCreate'e dokunmadan: guncelleyiciye ilk dokunus her zaman
     * acilis (MainActivity.onCreate), VPN servisi, periyodik is ya da bir alicidir.
     */
    private fun attach(app: Context) {
        if (!attached.compareAndSet(false, true)) return
        (app as? Application)?.registerActivityLifecycleCallbacks(UiTracker)
        UpdateJobService.ensureScheduled(app)
    }

    private fun settings(app: Context) = SettingsRepository.get(app)

    private fun updatesDir(app: Context) = File(app.cacheDir, "updates")

    internal fun isDue(lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean =
        // Saat geri alinmissa (lastCheck gelecekte) beklemek yerine denetle.
        lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS

    private fun placeholderInfo(version: String) = ReleaseInfo(
        version = version,
        tag = "android-v$version",
        assetName = ReleaseParser.PREFERRED_ASSET,
        downloadUrl = "",
        sizeBytes = 0L,
        sha256 = null,
        notes = "",
        htmlUrl = "",
    )

    private fun infoOf(s: UpdateState): ReleaseInfo? = when (s) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Verifying -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }

    /**
     * Uygulamanin baslatilmis etkinlik sayisi ve one gelen son etkinlik. Surec onceligine
     * (RunningAppProcessInfo.importance) bakmak yetmiyor: VPN'in on plan servisi onu 125'e
     * cekiyor ve ekranda hicbir etkinlik yokken "gorunur" diyordu (UPD-1).
     */
    private object UiTracker : Application.ActivityLifecycleCallbacks {
        @Volatile
        var started = 0

        @Volatile
        var resumed: WeakReference<Activity>? = null

        // Ekran donmesinde eski etkinlik durur, yenisi baslar: bu "one gelis" sayilmasin.
        private var recreating = 0

        override fun onActivityStarted(activity: Activity) {
            val first = synchronized(this) {
                if (recreating > 0) {
                    recreating--
                    false
                } else {
                    started++ == 0
                }
            }
            // Uygulama one geldi: Geri ile kapatilip VPN sureci canli tutsa bile her one geliste
            // (6 saatlik sinir icinde) denetim yapilir; ViewModel'in tek seferlik cagrisina bagli kalmaz.
            if (first) runCatching { onAppOpen(activity.applicationContext) }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(this) {
                if (activity.isChangingConfigurations) recreating++ else if (started > 0) started--
            }
        }

        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            // Onay arka planda istendiyse (bildirim) uygulama one gelir gelmez ekrani ac; bir kez.
            if (pendingConfirm != null && !confirmShown && state.value is UpdateState.Installing) {
                appContext?.let { launchConfirm(it) }
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
