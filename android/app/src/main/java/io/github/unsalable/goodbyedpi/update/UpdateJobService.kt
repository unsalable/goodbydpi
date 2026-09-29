package io.github.unsalable.goodbyedpi.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 6 saatte bir arka plan guncelleme denetimi (UPD-2). VPN servisi sureci gunlerce ayakta
 * tuttugunda ne etkinlik yeniden olusuyor ne de motor yeniden basliyordu; denetim yalnizca
 * cihaz acilisinda ya da elle ac/kapa ile yapiliyordu.
 *
 * JobScheduler (WorkManager degil): tek bir periyodik is icin ek kutuphane gerekmez; sistem isi
 * diger islerle toplu calistirir, pil maliyeti yok denecek kadar az. Is kalici (cihaz acilisindan
 * sonra da surer) ve yalnizca ag varken calisir. 6 saatlik sinir (isDue) yine UpdateManager'da:
 * is erken ya da ust uste calisirsa GitHub'a fazladan istek gitmez.
 */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "periyodik denetim")
        val started = UpdateManager.backgroundCheck(applicationContext) {
            // Kurulum oturumu sisteme verildi ya da is bitti; sonuc InstallStatusReceiver'a gelir.
            runCatching { jobFinished(params, false) }
        }
        // Baska bir guncelleme isi zaten suruyorsa beklenecek bir sey yok.
        return started
    }

    // Sistem isi durdurursa (ag gitti vb.) yeniden planlamaya gerek yok: periyodik is zaten
    // tekrar gelecek; yarim indirme bir sonraki denetimde bastan yapilir.
    override fun onStopJob(params: JobParameters): Boolean = false

    internal companion object {
        private const val TAG = "UpdateJob"

        /** Uygulamadaki tek JobScheduler isi; baska is eklenirse farkli kimlik alsin. */
        const val JOB_ID = 4201

        private const val PERIOD_MS = UpdateManager.CHECK_INTERVAL_MS
        private const val FLEX_MS = 60L * 60 * 1000

        /** Is planli degilse (ilk calisma, uygulama verisi silindi) planlar; varsa dokunmaz. */
        fun ensureScheduled(context: Context) {
            try {
                val js = context.getSystemService(JobScheduler::class.java) ?: return
                val existing = js.getPendingJob(JOB_ID)
                if (existing != null && existing.intervalMillis == PERIOD_MS && existing.isPersisted) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                    .setPeriodic(PERIOD_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                val r = js.schedule(job)
                Log.i(TAG, "periyodik is planlandi (sonuc $r)")
            } catch (e: Exception) {
                // Plansiz kalsa da acilis ve servis denetimleri calisir; uygulamayi dusurmesin.
                Log.w(TAG, "periyodik is planlanamadi", e)
            }
        }
    }
}
