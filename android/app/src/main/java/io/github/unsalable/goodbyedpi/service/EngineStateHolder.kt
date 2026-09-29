package io.github.unsalable.goodbyedpi.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// SOZLESME (wave 2): imzalar sabit. UI yalnizca bu dosyadaki public uyeleri kullanir.

/** Servis, karo, bildirim ve arayuzun ortak gordugu motor durumu. */
sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState

    /**
     * @param sinceElapsed baglantinin kuruldugu an (SystemClock.elapsedRealtime)
     * @param socksPort byedpi'nin dinledigi yerel port; baglanti testi bunu kullanir
     */
    data class Running(
        val sinceElapsed: Long,
        val methodName: String,
        val dnsName: String,
        val socksPort: Int,
        val argv: List<String> = emptyList(),
    ) : EngineState

    data object Stopping : EngineState

    /** [message] kullaniciya gosterilir (Turkce). */
    data class Failed(val message: String) : EngineState
}

/** Tunelin toplam sayaclari (hev TProxyGetStats). */
data class TrafficStats(
    val txBytes: Long,
    val rxBytes: Long,
    val txPackets: Long,
    val rxPackets: Long,
) {
    companion object {
        val ZERO = TrafficStats(0, 0, 0, 0)
    }
}

object EngineStateHolder {
    /** Saniyede bir: arayuz hizi farktan hesapliyor, daha sik okumak pil harcar. */
    private const val SAMPLE_INTERVAL_MS = 1000L

    /**
     * Son dinleyici gittikten sonra ornekleme bu kadar daha surer: ekran dondurme ya da
     * yapilandirma degisiminde akis bosuna durup yeniden baslamasin.
     */
    private const val STOP_TIMEOUT_MS = 1500L

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Surec omru boyunca yasayan kendi kapsamimiz (GlobalScope degil). Yerel sayac okumasi ana
    // is parcaciginda yapilmasin diye Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Servis motoru kurunca baglar, kapanirken birakir; null = sayac yok. */
    @Volatile
    internal var statsSource: (() -> TrafficStats)? = null

    /**
     * Yalnizca biri dinlerken (arayuz gorunurken) ve motor calisirken saniyede bir ornekler;
     * dinleyen yoksa ya da motor kapaliysa hic uyanmaz.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val traffic: StateFlow<TrafficStats> = _state
        .map { it is EngineState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running -> if (running) ticker() else flowOf(TrafficStats.ZERO) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrafficStats.ZERO)

    private fun ticker(): Flow<TrafficStats> = flow {
        while (true) {
            emit(runCatching { statsSource?.invoke() }.getOrNull() ?: TrafficStats.ZERO)
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    internal fun set(state: EngineState) {
        _state.value = state
    }
}
