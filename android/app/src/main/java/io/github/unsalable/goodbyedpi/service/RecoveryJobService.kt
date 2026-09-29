package io.github.unsalable.goodbyedpi.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * 15 dakikalik emniyet agi (bkz. [Recovery]): bekci ve App.onCreate bir sekilde kacirdiysa
 * baglantiyi geri getirir. Is hizli ve ana is parcaciginda biter (yalnizca bir servis
 * baslatma istegi); VPN izni olan uygulamaya arka plandan on plan servis baslatmak serbest.
 */
class RecoveryJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching { ServiceController.recoverInBackground(this, "periyodik is") }
            .onFailure { Log.w(TAG, "kurtarma basarisiz", it) }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private companion object {
        const val TAG = "GdpiRecoveryJob"
    }
}
