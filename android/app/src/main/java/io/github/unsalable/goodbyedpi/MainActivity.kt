package io.github.unsalable.goodbyedpi

import android.Manifest
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
import io.github.unsalable.goodbyedpi.ui.UiEvent
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

    // Reddedilirse bir sey yapilmaz: servis bildirimsiz de calisir (bkz. MainViewModel).
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
