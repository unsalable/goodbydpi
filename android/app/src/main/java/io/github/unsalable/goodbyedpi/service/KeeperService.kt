package io.github.unsalable.goodbyedpi.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.unsalable.goodbyedpi.data.SettingsRepository

/**
 * "Bekci": hicbir is yapmayan, disa kapali, duz START_STICKY servis. Tek gorevi surec
 * olunce sistemin onu yeniden baslatmasi (bkz. [Recovery]).
 *
 * DpiVpnService'in kendisi bu isi goremiyor: Vpn sinifi ona bagli oldugu icin surec olunce
 * bag kopuyor ve ActivityManager kaydi yeniden baslatma planlamadan siliyor. Bu servise kimse
 * bagli degil; sistem "cokmus servisi 1000 ms sonra yeniden baslat" yolunu izliyor ve
 * onStartCommand bos intent ile geliyor. Motor calisirken baslatilir, kullanici durdurunca
 * ya da izin geri alininca durdurulur. On plan degil: bildirim ve pil maliyeti yok.
 */
class KeeperService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val want = runCatching { SettingsRepository.get(this).current.wantRunning }.getOrDefault(false)
        if (!want) {
            // Istek kapanmis (kullanici durdurdu): yeniden baslatilacak bir sey yok.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            Log.i(TAG, "surec olmus, bekci yeniden baslatildi: baglanti geri getiriliyor")
            ServiceController.recoverInBackground(this, "bekci")
        }
        return START_STICKY
    }

    private companion object {
        const val TAG = "GdpiKeeper"
    }
}
