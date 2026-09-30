package io.github.unsalable.goodbyedpi

import android.Manifest
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.unsalable.goodbyedpi.ui.GoodbyeDpiApp
import io.github.unsalable.goodbyedpi.ui.MainViewModel
import io.github.unsalable.goodbyedpi.service.ServiceController
import io.github.unsalable.goodbyedpi.ui.UiEvent
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.service.EngineStateHolder
import io.github.unsalable.goodbyedpi.service.QuickTileState
import io.github.unsalable.goodbyedpi.ui.QuickTileRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tek aktivite. Arayuzun tamami Compose'da (GoodbyeDpiApp); burada yalnizca aktiviteye bagli
 * isler var: VPN izni ekrani ve bildirim izni. Ikisi de sonuc bekleyen aktivite cagrisi
 * oldugu icin ViewModel'den olay olarak gelir, sonuc yine ViewModel'e doner.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    // Kayit onCreate'ten once yapilmali; aktivite yeniden kurulunca sonuc yine buraya gelir.
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onVpnConsentResult(result.resultCode == RESULT_OK)
    }

    // Reddedilirse bir sey yapilmaz: servis bildirimsiz de calisir (bkz. MainViewModel). Izin
    // verilirse durum bildirimi yeniden gonderilir: servis coktan baslamis, izinsiz gonderdigi
    // on plan bildirimi (Durdur dugmesiyle) yoksa ilk oturum boyunca hic gorunmezdi.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onNotificationPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sistem cubugu simgelerinin rengi temaya gore Theme.kt'de yeniden ayarlaniyor;
        // buradaki ilk cagri pencereyi ilk karede kenardan kenara yapar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        vm.onAppOpen()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.events.collect(::handle)
            }
        }

        setContent { GoodbyeDpiApp(vm) }

        offerQuickTileOnce()

        // Yeniden kurulumda (dondurme vb.) ayni istegi ikinci kez isleme.
        if (savedInstanceState == null) handleConnectExtra(intent)
    }

    /**
     * Ilk basarili baglantidan sonra, bir kez: "Hizli ayarlara ekle?" sistem penceresi (Android
     * 13+). RESUMED'da bekleniyor: bildirim izni penceresi aktiviteyi duraklatir, ikisi ust uste
     * binmesin; o kapaninca kisa bir gecikmeyle sorulur. Reddedilirse bir daha sorulmaz, Ayarlar >
     * Arka plan'daki satirdan eklenebilir.
     */
    private fun offerQuickTileOnce() {
        if (!QuickTileRequest.supported) return
        if (QuickTileState.isAdded(this) || QuickTileState.wasPrompted(this)) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                EngineStateHolder.state.first { it is EngineState.Running }
                delay(1500)
                if (QuickTileState.isAdded(this@MainActivity) || QuickTileState.wasPrompted(this@MainActivity)) return@repeatOnLifecycle
                QuickTileState.markPrompted(this@MainActivity)
                QuickTileRequest.request(this@MainActivity) { }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectExtra(intent)
    }

    /**
     * Karo, izin eksikken "baglan" istegiyle acar; izin ekrani ViewModel olayi olarak gelir.
     * Istek yalnizca disa kapali ConnectRequest takma adindan gelirse gecerli (sozlesme C5):
     * MainActivity disa acik, baska bir uygulama EXTRA_CONNECT koyup VPN'i acabilirdi. Son
     * kullanilanlardan acilis (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) gorevin eski temel
     * intent'ini yeniden verir; istek coktan islenmisti, tekrar baglanmasin.
     */
    private fun handleConnectExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(ServiceController.EXTRA_CONNECT, false) != true) return
        intent.removeExtra(ServiceController.EXTRA_CONNECT)
        if (intent.component?.className != ServiceController.CONNECT_ALIAS) {
            Log.w(TAG, "baglanma istegi takma ad disindan geldi, yok sayildi: ${intent.component}")
            return
        }
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        vm.onConnectRequested()
    }

    // Bildirim izni olayi yalnizca Android 13+'ta uretiliyor (MainViewModel.askNotificationsOnce).
    @SuppressLint("InlinedApi")
    private fun handle(event: UiEvent) {
        try {
            when (event) {
                is UiEvent.RequestVpnConsent -> vpnConsent.launch(event.intent)
                UiEvent.RequestNotificationPermission ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (e: Exception) {
            // Bazi ROM'larda VPN izin ekrani yok (ActivityNotFound); uygulama dusmesin.
            Log.w(TAG, "Izin ekrani acilamadi", e)
            if (event is UiEvent.RequestVpnConsent) vm.onVpnConsentResult(false)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
