package io.github.unsalable.goodbyedpi.update

import android.content.Context

/**
 * Guncelleyicinin surecler arasi hafizasi (AppSettings'ten ayri: kullanici tercihi degil, akisin
 * ic durumu). Saf veri + kurallar; JVM testleri Android'siz calistirir.
 *
 * - [available]: GitHub'da bulunmus ama henuz kurulmamis surum. Yalnizca bellekte tutulunca surec
 *   olunce (LMK, VPN kapaninca, izin ekranindan donus) 6 saat boyunca hic onerilmiyordu (UPD-3).
 * - [snoozedVersion]/[snoozedAt]: "Daha sonra" / "Vazgec" bir ERTELEMEDIR, kalici atlama degil
 *   (UPD-4). Hata afisini kapatmak hic kaydedilmez: bir sonraki denetim yeniden dener.
 * - [badVersion]: indirilip dogrulamada reddedilen surum (eski versionCode, baska imza, baska
 *   paket). Otomatik denetim onu tekrar tekrar indirmesin (REL-2); elle denetim yine dener.
 */
internal data class UpdateMemo(
    val available: String? = null,
    val snoozedVersion: String? = null,
    val snoozedAt: Long = 0L,
    val badVersion: String? = null,
) {
    /** "Daha sonra" suresi dolmadi mi? Saat geri alinmissa (gelecekte kayit) erteleme bitmis sayilir. */
    fun isSnoozed(version: String?, now: Long): Boolean =
        VersionUtil.sameVersion(version, snoozedVersion) && now >= snoozedAt && now - snoozedAt < SNOOZE_MS

    fun isBad(version: String?): Boolean = VersionUtil.sameVersion(version, badVersion)

    /** Otomatik akis bu surumu indirmemeli mi? */
    fun blocksAuto(version: String?, now: Long): Boolean = isSnoozed(version, now) || isBad(version)

    /** Hatirlanan, calisan surumden yeni ve otomatik olarak kurulabilecek surum (yoksa null). */
    fun pendingFor(current: String, now: Long): String? =
        available?.takeIf { VersionUtil.isNewer(it, current) && !blocksAuto(it, now) }

    companion object {
        /** "Daha sonra" bir gun gecerli; sonra otomatik guncelleme yeniden dener. */
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
    }
}

/** [UpdateMemo]'nun kalici kopyasi (SharedPreferences; kucuk ve yalnizca bu paket yazar). */
internal object UpdateMemoStore {
    private const val FILE = "gdpi_update"
    private const val K_AVAILABLE = "available"
    private const val K_SNOOZED = "snoozedVersion"
    private const val K_SNOOZED_AT = "snoozedAt"
    private const val K_BAD = "badVersion"

    private val lock = Any()

    fun read(context: Context): UpdateMemo = synchronized(lock) {
        val p = prefs(context)
        UpdateMemo(
            available = p.getString(K_AVAILABLE, null),
            snoozedVersion = p.getString(K_SNOOZED, null),
            snoozedAt = p.getLong(K_SNOOZED_AT, 0L),
            badVersion = p.getString(K_BAD, null),
        )
    }

    /** Oku-degistir-yaz; commit() senkron: surec hemen ardindan (kurulum) oldurulebilir. */
    fun update(context: Context, transform: (UpdateMemo) -> UpdateMemo): UpdateMemo = synchronized(lock) {
        val old = read(context)
        val new = transform(old)
        if (new != old) {
            prefs(context).edit()
                .putString(K_AVAILABLE, new.available)
                .putString(K_SNOOZED, new.snoozedVersion)
                .putLong(K_SNOOZED_AT, new.snoozedAt)
                .putString(K_BAD, new.badVersion)
                .commit()
        }
        new
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
