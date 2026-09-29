package io.github.unsalable.goodbyedpi.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream

/**
 * Indirilmis APK'yi PackageInstaller oturumuyla kurar.
 *
 * Neden oturum (ACTION_VIEW + FileProvider degil): sonuc bir PendingIntent ile bize geri
 * doner (iptal, catisma, yer yok ayirt edilebilir) ve Android 12+'da kurulum kaydinin sahibi
 * bizsek setRequireUserAction(USER_ACTION_NOT_REQUIRED) ile onay sormadan guncelleyebiliriz.
 * Uygulama adb ya da tarayicidan kurulduysa ilk guncelleme yine onay ister; o kurulumdan sonra
 * kaydin sahibi uygulamanin kendisi olur ve sonrakiler sessiz gecer.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"

    const val ACTION_INSTALL_STATUS = "io.github.unsalable.goodbyedpi.update.INSTALL_STATUS"
    const val EXTRA_VERSION = "gdpi_version"

    const val MSG_PACKAGE = "Paket bu uygulamaya ait değil"
    const val MSG_OLDER = "İndirilen sürüm yeni değil"
    const val MSG_SIGNATURE = "Paket imzası uyuşmuyor"
    const val MSG_INVALID = "Paket okunamadı"
    const val MSG_SESSION = "Kurulum başlatılamadı"

    /**
     * Yalnizca hata ayiklama kancasi (src/debug): onay ekranini zorlar. Emulatorde onaysiz
     * kurulum kosullari hep tuttugu icin "onay gerekiyor" yolu (bildirim, one geliste onay
     * ekrani) baska turlu denenemiyor. Surum derlemesinde hic degismez.
     */
    @Volatile
    internal var debugRequireUserAction = false

    /** Yeniden indirmekle duzelmeyen dogrulama hatalari (yayinin kendisi yanlis). */
    val PERMANENT_ERRORS = setOf(MSG_PACKAGE, MSG_OLDER, MSG_SIGNATURE)

    /** Android 8+ "bilinmeyen uygulamalari yukle" izni; oncesinde oturum kendisi onay sorar. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Izin ekranina giden Intent (Android 8+); izin yoksa arayuz bunu acar. */
    fun permissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Kurulumdan once dosyayi kendimiz kontrol ederiz: yanlis paket / eski surum / baska imza
     * zaten reddedilecekti ama sistem bunu ancak kullanici onay verdikten sonra, anlasilmaz bir
     * hatayla soyluyor. Donen deger dosyadaki versionName (bilinmiyorsa null).
     */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    fun verifyArchive(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateException(MSG_INVALID)
        if (archive.packageName != context.packageName) throw UpdateException(MSG_PACKAGE)

        val installed = pm.getPackageInfo(context.packageName, flags)
        if (versionCodeOf(archive) <= versionCodeOf(installed)) throw UpdateException(MSG_OLDER)

        val a = signaturesOf(archive)
        val b = signaturesOf(installed)
        // Arsivden imza okunamadiysa (eski cihazlarda olabiliyor) karari sisteme birakiriz;
        // okunduysa ve ortak sertifika yoksa kullaniciyi bosuna onay ekranina gondermeyelim.
        // (Anahtar dondurulmusse gecmisler farkli uzunlukta olur; bir ortak eleman yeter.)
        if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) throw UpdateException(MSG_SIGNATURE)
        return archive.versionName
    }

    /**
     * Oturumu acar, APK'yi yazar ve commit eder. Sonuc InstallStatusReceiver'a gelir.
     * Basari halinde sistem sureci sonlandirip yeni surumu yerlestirir; bu fonksiyon donduktan
     * sonra her an oldurulebiliriz. Bloklar (dosya kopyalama): IO is parcacigindan cagrilmali.
     */
    fun install(context: Context, apk: File, version: String): Int {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Kosullar tutarsa (kaydin sahibi biziz, izin var, hedef SDK guncel) onaysiz;
                // tutmazsa sistem sessizce STATUS_PENDING_USER_ACTION'a duser, hata olmaz.
                setRequireUserAction(
                    if (debugRequireUserAction) {
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    } else {
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    },
                )
            }
            // setRequestUpdateOwnership (API 34) bilincli olarak kullanilmiyor: sahiplik alinca
            // kullanicinin tarayicidan ya da dosya yoneticisinden elle kurdugu surumler de
            // "sahibi degistirilsin mi" onayina takiliyor; bize sagladigi bir sey yok (magaza yok).
        }

        // Onceki denemelerden kalan kendi oturumlarimiz (onayi hic verilmemis, surec olmus):
        // yenisi onlarin yerini aliyor. Birakilmazsa sistem gunlerce tutar ve oturum sinirina sayilir.
        // UpdateManager buraya onay bekleyen bir oturum varken gelmez (Installing'de yeni is yok).
        runCatching {
            installer.mySessions.forEach { old ->
                runCatching { installer.abandonSession(old.sessionId) }
                Log.i(TAG, "Eski kurulum oturumu ${old.sessionId} birakildi")
            }
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            throw UpdateException(MSG_SESSION, e)
        }
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            FileInputStream(apk).use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, InstallStatusReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .setPackage(app.packageName)
                .putExtra(EXTRA_VERSION, version)
            // Sistem durum bilgisini bu Intent'e ekleyerek gonderir: Android 12+'da bunun icin
            // PendingIntent degistirilebilir (MUTABLE) olmali. Alici disa kapali ve Intent acik
            // bilesen adli oldugu icin baskasi icerigini yonlendiremez.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pending.intentSender)
            Log.i(TAG, "Kurulum oturumu $sessionId gonderildi (${apk.length()} bayt, surum $version)")
            return sessionId
        } catch (e: Exception) {
            // openSession'in kendisi firlattiysa elimizde Session yok ama oturum olusturuldu:
            // kimligiyle birakilmazsa her yeniden denemede bir tane daha sizar (UPD-6).
            runCatching {
                val s = session
                if (s != null) s.abandon() else installer.abandonSession(sessionId)
            }
            throw if (e is UpdateException) e else UpdateException(MSG_SESSION, e)
        } finally {
            runCatching { session?.close() }
        }
    }

    /** PackageInstaller durum kodunu kisa bir Turkce mesaja cevirir (null = hata degil). */
    fun statusMessage(status: Int, systemMessage: String?): String? = when (status) {
        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Kurulum iptal edildi"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Kurulum engellendi"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Yüklü sürümle çakışıyor"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Paket bu cihazla uyumsuz"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Paket geçersiz"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Yetersiz depolama alanı"
        else -> "Kurulum başarısız" + (systemMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptySet()
            // Anahtar dondurme yapildiysa gecmisteki tum imzalar gecerli sayilir.
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        }
        return sigs?.map { it.toCharsString() }?.toSet().orEmpty()
    }
}
