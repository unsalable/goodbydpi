package io.github.unsalable.goodbyedpi.engine

/**
 * hev-socks5-tunnel (tun -> SOCKS5) icin JNI kopru nesnesi.
 *
 * Yerel taraf `JNI_OnLoad` icinde bu sinifa `RegisterNatives` yapar
 * (hev-jni.c, -DPKGNAME=io/github/unsalable/goodbyedpi/engine -DCLSNAME=TProxy).
 * Bu yuzden paket/sinif/metot adlari ve imzalari yerel tablo ile birebir ayni
 * kalmali ve R8 bunlari yeniden adlandirmamali; aksi halde loadLibrary
 * UnsatisfiedLinkError ile patlar. Tum cagrilar arka plan is parcaciginda
 * yapilmali (Stop, tunel is parcacigini join eder).
 *
 * Davranis sozlesmesi (ayrinti: android/docs/HEV_NOTES.md):
 * - fd'nin sahibi Kotlin'dir; hev onu kapatmaz. ParcelFileDescriptor,
 *   [TProxyStopService] donene kadar ACIK tutulmali, sonra kapatilmali.
 * - Ayni surecte tek tunel calisir; baslat -> durdur -> baslat desteklenir.
 */
internal object TProxy {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /**
     * YAML dosyasini senkron dogrular, sonra tuneli ayri bir yerel is
     * parcaciginda baslatir. false: zaten calisiyor, fd gecersiz ya da
     * config okunamadi/gecersiz. true sonrasi erken hata (ornegin log
     * dosyasi acilamadi) ancak [TProxyIsRunning] ile gorulur.
     */
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean

    /** Durdurma sinyali gonderir ve is parcacigini join eder (tipik < 100 ms). Calismiyorsa da true. */
    @JvmStatic external fun TProxyStopService(): Boolean

    /** Tunel is parcacigi hala calisiyor mu; kendiliginden bittiyse false (watchdog icin). */
    @JvmStatic external fun TProxyIsRunning(): Boolean

    /**
     * [txPackets, txBytes, rxPackets, rxBytes]; tx = uygulamalardan tun'a gelen,
     * rx = tun'a geri yazilan. Bu calisma icin kumulatif, durdurunca sifirlanir;
     * 32 bit ABI'lerde 2^32'de sarar.
     */
    @JvmStatic external fun TProxyGetStats(): LongArray
}
