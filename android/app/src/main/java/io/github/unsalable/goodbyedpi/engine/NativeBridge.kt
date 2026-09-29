package io.github.unsalable.goodbyedpi.engine

/**
 * byedpi (ciadpi SOCKS5 proxy) icin JNI kopru nesnesi.
 *
 * Yerel taraf `JNI_OnLoad` icinde bu sinifa `RegisterNatives` yapar
 * (byedpi-jni/byedpi_jni.c); paket/sinif/metot adlari ve imzalari yerel tablo
 * ile birebir ayni kalmali, R8 bunlari yeniden adlandirmamali (proguard-rules.pro).
 *
 * Davranis sozlesmesi (ayrinti: android/docs/BYEDPI_NOTES.md):
 * - Ayni surecte tek byedpi calisir; ikinci [byedpiStart] hemen -3 doner.
 * - [byedpiStart] bloklar: ayri bir is parcaciginda cagrilmali. Argumanlar program
 *   adi OLMADAN verilir (yerel taraf "ciadpi" ekler).
 * - [byedpiStop] her is parcacigindan guvenli; baslatma surerken gelen durdurma da
 *   o baslatmayi hemen bitirir. Durdurma [byedpiStart] yerele girmeden gelirse -1
 *   doner ve etkisiz kalir; baslatmayi iptal etmek icin "is parcacigi yasadikca
 *   byedpiStop(); join(50)" dongusu kullanilmali.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("byedpi")
    }

    /** Durdurma istegiyle temiz cikis. */
    const val OK = 0
    /** socket/bind/listen hatasi (orn. port dolu). */
    const val ERR_START = -1
    /** Gecersiz ya da bilinmeyen arguman (ayrinti logcat'te, etiket "ciadpi"). */
    const val ERR_ARGS = -2
    /** Bu surecte zaten bir byedpi calisiyor. */
    const val ERR_BUSY = -3
    /** Durdurma istenmeden kendiliginden bitti (watchdog yeniden baslatmali). */
    const val ERR_EXITED = -4
    /** Bellek / eventfd / JNI donusum hatasi. */
    const val ERR_INTERNAL = -5

    /** Proxy durana kadar bloklar. 0: durdurma istegiyle temiz cikis; <0: hata (ERR_* sabitleri). */
    @JvmStatic external fun byedpiStart(args: Array<String>): Int

    /** Is parcacigi guvenli, beklemez; donguyu uyandirir. 0, ya da hicbir sey calismiyorsa -1. */
    @JvmStatic external fun byedpiStop(): Int
}
