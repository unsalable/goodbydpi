package io.github.unsalable.goodbyedpi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

/**
 * filesDir/settings.json icinde saklanan kullanici tercihleri. Bilinmeyen alanlar yok
 * sayilir, eksik alanlar asagidaki varsayilanlarla dolar (SettingsRepository'nin Json ayari).
 */
@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Secili internet saglayicisi (IspProfile.id); yontem listesini ve onerileri belirler. */
    val isp: String = IspProfile.GENERAL_ID,
    /** Secili yontem: MethodPreset.id ya da bir ozel profilin kimligi. */
    val method: String = MethodPreset.DEFAULT_ID,
    /** Kullanicinin olusturdugu, adlandirilmis ozel yontem profilleri. */
    val customProfiles: List<CustomMethodProfile> = emptyList(),
    /**
     * Secili DNS: DnsProfile.id ya da bir ozel DNS girisinin kimligi. Varsayilan Yandex
     * (1253): Turk ISS'leri 53. portu kaciriyor, Cloudflare:53 engelli sitelerde ISS'in
     * zehirli cevabini dondururdu. Eski dosyalar icin bkz. [upgradedFrom].
     */
    val dns: String = DnsProfile.DEFAULT_ID,
    /** Kullanicinin olusturdugu, adlandirilmis ozel DNS sunuculari. */
    val customDns: List<CustomDnsEntry> = emptyList(),
    /**
     * Uygulama acilinca baglantiyi da baslat. Masaustunun aksine varsayilan kapali: ilk
     * acilista kullanici istemeden bir VPN kurulmasin, kullanici kendisi acsin.
     */
    val autoConnect: Boolean = false,
    /** Cihaz acilinca, son durum "acik" idiyse baglantiyi geri getir. */
    val startOnBoot: Boolean = true,
    /** GitHub'da yeni surum var mi diye bak ve guncellemeyi oner. */
    val autoUpdate: Boolean = true,
    /** Secili yontem bir sitede takilirsa ISS'in diger yontemleriyle kendiliginden yeniden dene. */
    val autoFallback: Boolean = true,
    /**
     * Akilli mod: baglantilar once atlatmasiz gider, secili yontem yalnizca engel algilaninca
     * (RST/zaman asimi, ServerHello yok) devreye girer. Sahte paket yakin sunucuya (ISS icindeki
     * Google onbellegi) ulasip engelsiz siteleri bozmasin. Eski dosyada alan yoksa acik gelir.
     */
    val smartMode: Boolean = true,
    /** Yerel ag (192.168.x.x vb.) VPN disinda kalsin: yazici, NAS, Chromecast calismaya devam etsin. */
    val excludeLan: Boolean = true,
    /** IPv6 trafigini de tun uzerinden gecir. */
    val ipv6: Boolean = true,
    /**
     * Ic durum: kullanicinin son istegi (bagli mi kalsin). Acilista, uygulama guncellemesinden
     * sonra ve sistemin servisi yeniden baslattigi durumlarda buna bakilir.
     */
    val wantRunning: Boolean = false,
    /** Son guncelleme denetiminin zamani (System.currentTimeMillis). */
    val lastUpdateCheck: Long = 0L,
    /**
     * Kurulumu baslatilan guncellemenin surumu. Uygulama yeni surumle acilinca bu deger
     * calisan surumle eslesirse "guncellendi" bildirimi gosterilir.
     */
    val pendingUpdate: String? = null,
    /** Kullanicinin "simdi degil" dedigi surum; ayni surum icin afis tekrar cikmasin. */
    val dismissedUpdate: String? = null,
    /**
     * Ayar dosyasinin bicim surumu. 1.0.0 bu alani yazmiyordu; dosyada yoksa SettingsRepository
     * onu 1 sayip [upgradedFrom] ile bir kez yukseltir. Yeni kurulumda dogrudan guncel surum.
     */
    val settingsVersion: Int = CURRENT_VERSION,
) {
    /**
     * Eski bicimli bir dosyayi bir kez yukseltir (yalnizca yuklemede, [settingsVersion] dosyada
     * yoksa ya da eskiyse). Surum 1 -> 2: 1.0.0'da varsayilan DNS Cloudflare:53 idi ve Genel
     * saglayici DNS'e dokunmuyordu; Genel + Cloudflare bu yuzden neredeyse her zaman el
     * degmemis varsayilan. Turk ISS'leri 53. portu kacirdigi icin o durumda engelli sitelerin
     * adi zehirli cozuluyordu: Yandex (1253) yapilir. Baska her secim (ISS profili, ozel DNS,
     * Kapali, bilerek Cloudflare secilmis bir ISS profili) oldugu gibi kalir. Kullanici sonra
     * Cloudflare'i yeniden secerse dosya guncel surumle yazildigi icin bu bir daha calismaz.
     */
    fun upgradedFrom(version: Int): AppSettings {
        var s = this
        if (version < 2 && s.isp.equals(IspProfile.GENERAL_ID, ignoreCase = true) &&
            s.dns.equals(DnsProfile.CLOUDFLARE_ID, ignoreCase = true)
        ) {
            s = s.copy(dns = DnsProfile.DEFAULT_ID)
        }
        return s.copy(settingsVersion = CURRENT_VERSION)
    }

    companion object {
        /** 2: smartMode eklendi, varsayilan DNS Yandex oldu (1.0.0 = 1, alan yazilmiyordu). */
        const val CURRENT_VERSION = 2
    }

    /**
     * Ayar dosyasini tutarli hale getirir; yuklemede ve her guncellemede cagrilir.
     *
     * - Her iki listede en az bir giris birakir (masaustuyle ayni): kullanici "Ozel"i secer
     *   secmez duzenleyecegi bir profil hazir olur.
     * - Bos adlari, ozel olmayan ya da yinelenen kimlikleri duzeltir (elle duzenlenmis dosya).
     * - Profil yapilandirmalarini gecerli araliga ceker.
     * - Silinmis/bilinmeyen secimleri gecerli olana dusurur ("checksum" -> "default").
     *
     * Ikinci cagri hicbir sey degistirmez.
     */
    fun migrate(): AppSettings {
        val profiles = fixIds(
            customProfiles.map {
                it.copy(
                    name = CustomIds.cleanName(it.name, CustomMethodProfile.DEFAULT_NAME),
                    config = it.config.sanitized(),
                )
            },
            idOf = { it.id },
            withId = { p, id -> p.copy(id = id) },
        ).ifEmpty { listOf(CustomMethodProfile()) }

        val dnsEntries = fixIds(
            customDns.map {
                it.copy(
                    name = CustomIds.cleanName(it.name, CustomDnsEntry.DEFAULT_NAME),
                    v4 = it.v4.trim(),
                    v6 = it.v6.trim(),
                )
            },
            idOf = { it.id },
            withId = { e, id -> e.copy(id = id) },
        ).ifEmpty { listOf(CustomDnsEntry()) }

        val methodId = if (CustomIds.isCustom(method)) {
            profiles.firstOrNull { it.id.equals(method, ignoreCase = true) }?.id ?: MethodPreset.DEFAULT_ID
        } else {
            MethodPreset.fromId(method).id
        }

        val dnsId = if (CustomIds.isCustom(dns)) {
            dnsEntries.firstOrNull { it.id.equals(dns, ignoreCase = true) }?.id ?: DnsProfile.DEFAULT_ID
        } else {
            DnsProfile.fromId(dns).id
        }

        return copy(
            isp = IspProfile.fromId(isp).id,
            method = methodId,
            customProfiles = profiles,
            dns = dnsId,
            customDns = dnsEntries,
        )
    }

    /**
     * "+ Yeni ozel ayar" / "Profili cogalt": [seed] degerli, [name] adli bir ozel profil
     * ekleyip secer. Listede migrate()'in koydugu el degmemis "Ozel" yer tutucusu varsa yenisi
     * eklenmez, o doldurulup secilir: yoksa ilk dokunusta bos "Ozel"in yanina ikinci bir
     * profil gelir ve kullanici hic olusturmadigi bir girisle kalirdi. Yer tutucu zaten
     * seciliyse (kaynak kendisi) hicbir sey degismez.
     */
    fun withNewCustomProfile(seed: DpiConfig, name: String?): AppSettings {
        val placeholder = customProfiles.firstOrNull { it.isUntouchedPlaceholder }
            ?: return CustomMethodProfile.createNew(customProfiles, seed, name).let { entry ->
                copy(customProfiles = customProfiles + entry, method = entry.id)
            }
        val others = customProfiles.filter { it.id != placeholder.id }
        val filled = placeholder.copy(
            name = CustomIds.newName(CustomIds.cleanName(name, CustomMethodProfile.DEFAULT_NAME), others.map { it.name }),
            config = seed,
        )
        return copy(
            customProfiles = customProfiles.map { if (it.id == placeholder.id) filled else it },
            method = filled.id,
        )
    }

    /**
     * "+ Yeni DNS" / "DNS girisini cogalt": secili giris ozelse onun kopyasi, degilse bos bir
     * giris ekleyip secer. Yer tutucu kurali [withNewCustomProfile] ile ayni: el degmemis
     * "Ozel DNS" varsa (bos adresli) yeni giris onun yerine gecer, "Ozel DNS 2" birikmez.
     */
    fun withNewCustomDns(): AppSettings {
        val current = customDns.firstOrNull { it.id.equals(dns, ignoreCase = true) }
        val placeholder = customDns.firstOrNull { it.isUntouchedPlaceholder }
            ?: return CustomDnsEntry.createNew(customDns, current).let { entry ->
                copy(customDns = customDns + entry, dns = entry.id)
            }
        val filled = if (current == null || current.id == placeholder.id) {
            placeholder
        } else {
            CustomDnsEntry.createNew(customDns.filter { it.id != placeholder.id }, current).copy(id = placeholder.id)
        }
        return copy(
            customDns = customDns.map { if (it.id == placeholder.id) filled else it },
            dns = filled.id,
        )
    }
}

/**
 * Ozel olmayan ("default" gibi hazir yontemlerle cakisan) ya da yinelenen kimlikleri yeni
 * kimlikle degistirir; ilk gecerli kimlik korunur ki secim kaybolmasin.
 */
private fun <T> fixIds(items: List<T>, idOf: (T) -> String, withId: (T, String) -> T): List<T> {
    val seen = HashSet<String>()
    val out = ArrayList<T>(items.size)
    for (item in items) {
        val id = idOf(item)
        if (CustomIds.isCustom(id) && seen.add(id.lowercase())) {
            out += item
        } else {
            val fresh = CustomIds.newId(items.map(idOf) + out.map(idOf))
            seen += fresh.lowercase()
            out += withId(item, fresh)
        }
    }
    return out
}
