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
 *
 * Basariyi tek bir basarisizlik silmez ([confirmFails]): basarinin ustune gelen basarisizlik
 * ancak ust uste [confirmFails] kez tekrarlanirsa yazilir, o zamana kadar eski basari (bayat
 * haliyle, yani yeniden denenmek uzere) kalir. Tek basarisizlikla dusurmek calisan cift yiginli
 * agda asansordeki 5 sn'lik sinyal kaybini bile iki tam motor yenilemesine (IPv6 kapat, 5 dk
 * sonra ac) ceviriyordu; her yenileme tum baglantilari koparir.
 */
internal class Ipv6ProbeCache<K : Any>(
    private val okTtlMs: Long,
    private val failTtlMs: Long,
    private val maxSize: Int,
    private val confirmFails: Int = 2,
    /**
     * Dogrulanmamis bir basarisizliktan sonra ikinci denemeye kadar beklenecek sure. Beklemeden
     * denenirse (onProbeDone -> evaluateIpv6 hemen yeniden deniyordu) anlik kopmada iki deneme
     * ayni milisaniyelerde basarisiz olur ve [confirmFails] hicbir seyi korumazdi.
     */
    private val reconfirmDelayMs: Long = 0,
) {
    /**
     * [unconfirmedFails]: basarinin ustune gelmis, henuz yazilmamis ardisik basarisizlik sayisi;
     * [lastFailMs]: bunlarin sonuncusunun zamani.
     */
    private class Entry(
        val result: Ipv6Probe.Result,
        val atMs: Long,
        val unconfirmedFails: Int = 0,
        val lastFailMs: Long = 0,
    )

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

    /**
     * Yeni deneme gerekli mi: sonuc yok ya da basari bayatladi. Dogrulanmamis bir basarisizliktan
     * hemen sonra degil: ikinci deneme [reconfirmDelayMs] sonra (saglik dongusunde) yapilir.
     */
    fun needsProbe(key: K, nowMs: Long): Boolean {
        val e = map[key]
        if (e != null && e.unconfirmedFails > 0 && nowMs - e.lastFailMs < reconfirmDelayMs) return false
        return get(key, nowMs) == null || isStale(key, nowMs)
    }

    /**
     * Sonucu yazar. Onceki sonuc basariysa ve bu bir basarisizliksa, ardisik [confirmFails]'inci
     * basarisizliga kadar yazilmaz (eski basari ve zamani kalir; bayatsa yeniden denenir).
     * @return sonuc yazildi mi (false: basarisizlik henuz dogrulanmadi)
     */
    fun put(key: K, result: Ipv6Probe.Result, nowMs: Long): Boolean {
        val prev = map[key]
        if (!result.ok && prev != null && prev.result.ok && prev.unconfirmedFails + 1 < confirmFails) {
            // Sira (en eski atilir) degismesin diye yerinde guncellenir.
            map[key] = Entry(prev.result, prev.atMs, prev.unconfirmedFails + 1, nowMs)
            return false
        }
        map.remove(key)
        map[key] = Entry(result, nowMs)
        while (map.size > maxSize) map.remove(map.keys.first())
        return true
    }

    /** Bayat basariyi atar (yeni baglanti / yeni ag / ayar yeniden acildi): taze deneme beklenir. */
    fun dropStale(key: K, nowMs: Long): Boolean {
        if (!isStale(key, nowMs)) return false
        map.remove(key)
        return true
    }

    fun remove(key: K) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
