package io.github.unsalable.goodbyedpi.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// SOZLESME (wave 2): imzalar sabit. Govdeyi "runtime" ajani doldurur; UI yalnizca
// bu dosyadaki public/internal olmayan uyeleri kullanir.

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
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // STUB: runtime ajani bunu yalnizca biri dinlerken (arayuz gorunurken) saniyede bir
    // ornekleyen bir akisa cevirir.
    private val _traffic = MutableStateFlow(TrafficStats.ZERO)
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    internal fun set(state: EngineState) {
        _state.value = state
    }
}
