package io.github.unsalable.goodbyedpi.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// SOZLESME (wave 2): imzalar sabit. STUB govde; "update" ajani doldurur.

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

object UpdateManager {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Guncellemeden sonraki ilk acilista kurulan surum; bir kez gosterilip tuketilir. */
    private val _justUpdatedTo = MutableStateFlow<String?>(null)
    val justUpdatedTo: StateFlow<String?> = _justUpdatedTo.asStateFlow()

    /** Uygulama acildiginda: autoUpdate aciksa ve son kontrolden 6 saat gectiyse kontrol eder. */
    fun onAppOpen(context: Context) {}

    /** Kullanici "Guncellemeleri denetle" dedi: sinir yok. */
    fun checkNow(context: Context) {}

    /**
     * Arka plan kontrolu: uygulama uzun sure acilmasa da VPN servisi calisiyor.
     * Servis motor ayaga kalkinca cagirir; autoUpdate aciksa ve 6 saat gectiyse bakar,
     * yeni surum varsa "Güncelleme hazır" bildirimi gosterir. Hicbir zaman firlatmaz.
     */
    fun backgroundCheck(context: Context) {}

    /** Available / Failed / NeedsPermission durumunda indir -> dogrula -> kur. */
    fun startUpdate(context: Context) {}

    /** Seridi gizler (bu surum icin tekrar sormaz). */
    fun dismiss() {}

    fun consumeJustUpdated() {
        _justUpdatedTo.value = null
    }
}
