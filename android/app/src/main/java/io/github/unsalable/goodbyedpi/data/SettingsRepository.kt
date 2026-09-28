package io.github.unsalable.goodbyedpi.data

import android.content.Context
import android.util.Log
import io.github.unsalable.goodbyedpi.model.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Ayarlarin tek sahibi: filesDir/settings.json.
 *
 * Dosya kucuk oldugu icin kurulurken hemen (bloklayarak) okunur; BootReceiver ve VPN servisi
 * arayuz hic acilmadan once ayarlara ihtiyac duyuyor. Yazmalar bir Mutex ile sirali ve
 * atomik (gecici dosya + fsync + rename): guc kesilse bile ya eski ya yeni dosya kalir,
 * yarim dosya kalmaz. Disk hatasi cagirana firlatilmaz; bellekteki durum gecerli kalir,
 * bir sonraki yazma yeniden dener.
 */
class SettingsRepository internal constructor(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tmpFile = File(file.path + ".tmp")
    private val bakFile = File(file.path + ".bak")
    private val writeLock = Mutex()

    private val state = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = state.asStateFlow()

    val current: AppSettings
        get() = state.value

    /**
     * Ayarlari [transform] ile degistirip kaydeder ve yeni degeri dondurur. Sonuc migrate()
     * edilir (her iki listede en az bir giris kalir, gecersiz secimler duzelir). Deger
     * degismediyse diske yazilmaz. Ayni anda gelen cagrilar sirayla uygulanir, hicbiri
     * digerinin degisikligini ezmez.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        writeLock.withLock {
            val old = state.value
            val new = transform(old).migrate()
            if (new == old) return@withLock old

            // Once bellek: arayuz diski beklemeden tepki versin.
            state.value = new
            withContext(ioDispatcher) { write(new) }
            new
        }

    private fun load(): AppSettings {
        if (!file.exists()) {
            // Ana dosya yok ama gecici dosya varsa, rename'in hedefin uzerine yazamadigi bir
            // ortamda (yalnizca Windows'taki JVM testleri) silme ile tasima arasinda kalinmistir.
            if (tmpFile.exists()) {
                val recovered = runCatching { decode(tmpFile.readText()) }.getOrNull()
                tmpFile.delete()
                if (recovered != null) return recovered
            }
            return AppSettings().migrate()
        }

        return try {
            decode(file.readText())
        } catch (e: Exception) {
            // Bozuk dosyayi atmiyoruz: kullanicinin profilleri elle kurtarilabilsin.
            Log.w(TAG, "settings.json okunamadi, varsayilanlara donuluyor", e)
            runCatching { file.copyTo(bakFile, overwrite = true) }
                .onFailure { Log.w(TAG, "settings.json.bak yazilamadi", it) }
            AppSettings().migrate()
        }
    }

    private fun decode(text: String): AppSettings {
        require(text.isNotBlank()) { "bos ayar dosyasi" }
        return json.decodeFromString(AppSettings.serializer(), text).migrate()
    }

    private fun write(settings: AppSettings) {
        try {
            file.parentFile?.mkdirs()
            val bytes = json.encodeToString(AppSettings.serializer(), settings).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmpFile).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // Android'de (POSIX rename) hedefin uzerine atomik yazar. Windows JVM'de hedef
            // varken basarisiz oluyor; testler orada da calissin diye sil + tasi.
            if (!tmpFile.renameTo(file)) {
                file.delete()
                check(tmpFile.renameTo(file)) { "settings.json.tmp tasinamadi" }
            }
        } catch (e: Exception) {
            Log.w(TAG, "settings.json yazilamadi", e)
            tmpFile.delete()
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val FILE_NAME = "settings.json"

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
            explicitNulls = false
        }

        @Volatile
        private var instance: SettingsRepository? = null

        /** Surec boyunca tek ornek; App, servis, alici ve kutucuk ayni durumu gorur. */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(File(context.applicationContext.filesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}
