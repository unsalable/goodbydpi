package io.github.unsalable.goodbyedpi.service

/**
 * IPv6 erisim denemesi sonuclari (ag + kuresel adres kumesi basina). Saf mantik, saat disaridan
 * verilir: JVM'de sinanir; DpiVpnService yalnizca motor is parcacigindan cagirir (kilitsiz).
 *
 * Iki sure var, ikisi de ayni agda IPv6'nin sonradan degisebilmesi icin:
 * - Basarisiz sonuc [failTtlMs] sonra atilir: ag gecici olarak bozuktuysa IPv6 sonsuza dek
 *   kapali kalmasin. Atilinca "sonuc yok" olur; Ipv6Gate.decide ayni agda eski degeri (false)
 *   korur, yeni deneme bitince karar verilir.
 * - Basarili sonuc [okTtlMs] sonra BAYATLAR ama atilmaz: yeniden denenir, bu arada eski deger
 *   (IPv6 acik) kullanilmaya devam eder, tun bosuna kapanip acilmaz. Eskiden basari hic
 *   bitmiyordu: modemin IPv6 oturumu dusup adres/yol kaldiginda (ya da operatorun IPv6 cikisi
 *   bozuldugunda) tun ag degisene kadar IPv6 sunmaya devam ediyor, Chrome/YouTube 1.0.1'deki
 *   gibi RST aliyordu.
 */
internal class Ipv6ProbeCache<K : Any>(
    private val okTtlMs: Long,
    private val failTtlMs: Long,
    private val maxSize: Int,
) {
    private class Entry(val result: Ipv6Probe.Result, val atMs: Long)

    // Ekleme sirasi: en eskisi once atilir.
    private val map = LinkedHashMap<K, Entry>()

    /**
     * Karar icin kullanilacak sonuc; null = sonuc yok (hic denenmedi ya da basarisiz sonucun
     * suresi doldu ve atildi). Bayat basari burada hala gecerli: [needsProbe] onu tazeletir.
     */
    fun get(key: K, nowMs: Long): Ipv6Probe.Result? {
        val e = map[key] ?: return null
        if (!e.result.ok && nowMs - e.atMs > failTtlMs) {
            map.remove(key)
            return null
        }
        return e.result
    }

    /** Suresi dolmamis kaydi degistirmeden okur (durum satiri ve gunluk icin). */
    fun peek(key: K): Ipv6Probe.Result? = map[key]?.result

    /** Basarili ama [okTtlMs]'den eski: arka planda yeniden denenmeli. */
    fun isStale(key: K, nowMs: Long): Boolean {
        val e = map[key] ?: return false
        return e.result.ok && nowMs - e.atMs > okTtlMs
    }

    /** Yeni deneme gerekli mi: sonuc yok ya da basari bayatladi. */
    fun needsProbe(key: K, nowMs: Long): Boolean = get(key, nowMs) == null || isStale(key, nowMs)

    fun put(key: K, result: Ipv6Probe.Result, nowMs: Long) {
        map.remove(key)
        map[key] = Entry(result, nowMs)
        while (map.size > maxSize) map.remove(map.keys.first())
    }

    fun remove(key: K) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
