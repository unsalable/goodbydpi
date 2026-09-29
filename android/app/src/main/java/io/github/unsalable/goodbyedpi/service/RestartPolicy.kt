package io.github.unsalable.goodbyedpi.service

/**
 * Watchdog'un yeniden baslatma butcesi (SPEC 3): 1 sn, 3 sn, sonra 10 sn bekleyerek; kayan
 * 5 dakikalik pencerede en fazla 5 deneme. Pencereden cikan denemeler unutulur, yani ara sira
 * dusen bir motor surekli toparlanir; ust uste dusen motor ise pili tuketmeden Failed olur.
 *
 * Saf sinif (saat disaridan): JVM'de sinanir. Is parcacigi guvenli degil; servisin motor
 * is parcacigindan kullanilir.
 */
class RestartPolicy(
    private val delaysMs: LongArray = longArrayOf(1_000, 3_000, 10_000),
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 5 * 60_000L,
) {
    private val attempts = ArrayDeque<Long>()

    /** Penceredeki deneme sayisi. */
    fun attemptsIn(now: Long): Int {
        prune(now)
        return attempts.size
    }

    /**
     * [now] aninda yeni bir deneme ister. Butce varsa denemeyi kaydedip beklenecek sureyi,
     * yoksa null doner (Failed'a gec).
     */
    fun next(now: Long): Long? {
        prune(now)
        if (attempts.size >= maxAttempts) return null
        val delay = delaysMs[minOf(attempts.size, delaysMs.size - 1)]
        attempts.addLast(now)
        return delay
    }

    /** Kullanici kendisi baslatti/degistirdi: onceki dusmeler sayilmaz. */
    fun reset() {
        attempts.clear()
    }

    private fun prune(now: Long) {
        while (attempts.isNotEmpty() && now - attempts.first() >= windowMs) attempts.removeFirst()
    }

    companion object {
        /** Butce bitince hata metnine eklenen cumle. */
        const val GIVE_UP_SUFFIX = "Otomatik yeniden bağlanma 5 denemede başarısız oldu."

        /**
         * Son hatanin nedeni ile [GIVE_UP_SUFFIX]'i tek metinde birlestirir. Nedenler cogu zaman
         * noktayla bitiyor ama hepsi degil ("VPN izni yok", eski/ic metinler); iki cumle arada
         * nokta olmadan yapisik gorunuyordu (e2e E2E-V6-2).
         */
        fun giveUpMessage(reason: String): String {
            val r = reason.trim()
            if (r.isEmpty()) return GIVE_UP_SUFFIX
            val sep = if (r.last() in ".!?") " " else ". "
            return r + sep + GIVE_UP_SUFFIX
        }
    }
}
