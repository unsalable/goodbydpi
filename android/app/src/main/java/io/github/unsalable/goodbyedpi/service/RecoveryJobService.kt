package io.github.unsalable.goodbyedpi.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Iki is (bkz. [Recovery]): 15 dakikalik periyodik emniyet agi ve her arka plan kurtarmasindan
 * sonraki tek seferlik kontrol isi ([Recovery.CHECK_JOB_ID]). Ikisi de bekci ve App.onCreate
 * bir sekilde kacirdiysa (orn. ActivityManager cokme cezasiyla bekciyi 30 dk erteledi)
 * baglantiyi geri getirir. Is hizli ve ana is parcaciginda biter (yalnizca bir servis
 * baslatma istegi); VPN izni olan uygulamaya arka plandan on plan servis baslatmak serbest.
 */
class RecoveryJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val source = if (params?.jobId == Recovery.CHECK_JOB_ID) "kontrol isi" else "periyodik is"
        runCatching { Recovery.runCheck(this, source) }
            .onFailure { Log.w(TAG, "kurtarma basarisiz", it) }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private companion object {
        const val TAG = "GdpiRecoveryJob"
    }
}
