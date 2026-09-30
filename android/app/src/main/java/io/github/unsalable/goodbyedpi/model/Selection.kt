package io.github.unsalable.goodbyedpi.model

// Ayar dosyasindaki kimlikleri gercek nesnelere cozen yardimcilar. Arayuz, servis ve
// kutucuk ayni kurallari kullansin diye tek yerde; hepsi saf ve yan etkisiz.

/** Secili internet saglayicisi (bilinmeyen kimlik -> Genel). */
fun AppSettings.ispProfile(): IspProfile = IspProfile.fromId(isp)

/** Yontem listesi: once saglayicinin yontemleri oneri sirasiyla, sonra her ozel profil. */
fun AppSettings.methodChoices(): List<MethodPreset> =
    ispProfile().methods + customProfiles.map { it.toPreset() }

/**
 * Secili yontem. Silinmis bir ozel profil Varsayilan'a duser. Saglayici listesinde olmayan
 * bir hazir yontem de gecerlidir (kullanici saglayiciyi sonradan degistirmis olabilir).
 */
fun AppSettings.selectedMethod(): MethodPreset =
    if (CustomIds.isCustom(method)) {
        customProfiles.firstOrNull { it.id.equals(method, ignoreCase = true) }?.toPreset()
            ?: MethodPreset.Default
    } else {
        MethodPreset.fromId(method)
    }

/** Motora verilecek yapilandirma: secili yontemin ciktisi, gecerli araliga cekilmis. */
fun AppSettings.selectedConfig(): DpiConfig = selectedMethod().build().sanitized()

/** DNS listesi: yerlesikler, sonra ozel girisler. */
fun AppSettings.dnsChoices(): List<DnsProfile> =
    DnsProfile.builtIn + customDns.map { it.toProfile() }

/** Secili DNS; silinmis bir ozel giris varsayilana (Yandex) duser. */
fun AppSettings.selectedDns(): DnsProfile =
    if (CustomIds.isCustom(dns)) {
        customDns.firstOrNull { it.id.equals(dns, ignoreCase = true) }?.toProfile()
            ?: DnsProfile.Default
    } else {
        DnsProfile.fromId(dns)
    }

/**
 * Otomatik yedek yontemler (autoFallback): saglayicinin diger yontemleri oneri sirasiyla.
 * Ozel profil seciliyse saglayicinin tum yontemleri, onerilenden baslayarak.
 */
fun AppSettings.fallbackMethods(): List<MethodPreset> {
    val selected = selectedMethod()
    val ispMethods = ispProfile().methods
    return if (CustomIds.isCustom(selected.id)) ispMethods else ispMethods.filter { it != selected }
}
