package io.github.unsalable.goodbyedpi.service

import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * VPN izni ve "baska bir VPN etkin mi" sorulari, HICBIR SEYI DEGISTIRMEDEN.
 *
 * VpnService.prepare() saf bir denetim DEGIL: platformun Vpn.prepare(eski, yeni=null) yolunda
 * cagiran su anki VPN degilse ama onayi onceden verilmisse (isVpnPreConsented: ACTIVATE_VPN
 * izinli) prepareInternal ile cagirani hazirlar ve etkin VPN'i dusurur (onRevoke, durum
 * DISCONNECTED, reason=prepare). Yani surecimizin "izin var mi" diye yaptigi her prepare,
 * kullanicinin baska bir VPN'ini (WireGuard, is VPN'i...) sessizce kapatiyordu: API 36
 * emulatorde hizli ayar paneli acilinca (karo -> recoverIfNeeded) goruldu. prepare() yalnizca
 * gercekten baglanmak istedigimiz yerde (kullanici "baglan" dedi ya da bu dosyadaki denetimden
 * gecen sessiz baslatma) cagrilir. Bkz. SPEC 8 "Passive VPN checks".
 */
internal object VpnGate {
    private const val TAG = "GdpiVpnGate"

    /** AppOpsManager.OPSTR_ACTIVATE_VPN (gizli sabit); platform onayi bu izinle tutuyor. */
    private const val OP_ACTIVATE_VPN = "android:activate_vpn"

    /** Kullanici istemeden (kurtarma, acilis, otomatik baglanma) baslatilabilir mi? */
    enum class Unattended {
        /** Izin var, baska VPN yok: prepare() artik yalnizca bosta duran (bagli olmayan) bir paketi birakir. */
        OK,
        NO_CONSENT,
        /** Baska bir uygulamanin VPN'i etkin: onu dusurmeyelim. */
        OTHER_VPN,
    }

    /**
     * Sessiz baslatmadan once. Once baska VPN'e bakilir: etkinse prepare'e hic gidilmez.
     * Denetimle servisin prepare'i arasinda baska bir VPN baglanirsa (milisaniyeler) yine
     * dusebilir; bu pencere kabul edildi.
     */
    fun unattendedStart(context: Context): Unattended =
        decide(
            otherVpn = otherVpnActive(context),
            consentOp = { consentOp(context) },
            prepareIsNull = { prepareIsNull(context) },
        )

    /**
     * VPN izni (onay) var mi. Degistirmeyen yol ACTIVATE_VPN izni; okunamazsa (eski surumlerde
     * dize tanimsiz olabilir) prepare()'e ancak baska VPN yokken dusulur, o zaman prepare en
     * fazla bosta duran bir paketi birakir.
     */
    fun hasConsent(context: Context): Boolean =
        consentOp(context) ?: (!otherVpnActive(context) && prepareIsNull(context))

    /**
     * Saf karar (birim testli). [prepareIsNull] yalnizca baska VPN yokken VE izin baska yoldan
     * okunamadiysa cagrilir: prepare()'in yan etkisi etkin bir VPN'i dusurmek.
     */
    internal fun decide(otherVpn: Boolean, consentOp: () -> Boolean?, prepareIsNull: () -> Boolean): Unattended {
        if (otherVpn) return Unattended.OTHER_VPN
        val consent = consentOp() ?: prepareIsNull()
        return if (consent) Unattended.OK else Unattended.NO_CONSENT
    }

    /**
     * Kendi uid'imize ait olmayan bir VPN agi var mi. Sahip uid'i API 30+'da var ve baskasinin
     * VPN'inde gizlenir (INVALID_UID), bizimkinde bizim uid'imiz. API 30 oncesi sahip
     * bilinmez: her VPN "baska" sayilir (cagiranlarda motor bu surecte kapali; tun sureciyle
     * birlikte kapanir). Baska kullanici profilindeki bir VPN de sayilir: yanlis tarafta kalmak
     * birinin VPN'ini dusurmekten iyidir.
     */
    fun otherVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val myUid = Process.myUid()
        return runCatching {
            @Suppress("DEPRECATION") // allNetworks: kisitli olmayan tum aglar; geri cagri gerektirmiyor
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) caps.ownerUid else null
                isForeignVpn(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), owner, myUid)
            }
        }.onFailure { Log.w(TAG, "aglar okunamadi", it) }.getOrDefault(false)
    }

    /** [ownerUid] null: bilinmiyor (API 30 oncesi). */
    internal fun isForeignVpn(isVpn: Boolean, ownerUid: Int?, myUid: Int): Boolean =
        isVpn && ownerUid != myUid

    /** ACTIVATE_VPN izinli mi; okunamazsa null. Platformun isVpnPreConsented'i ile ayni olcut. */
    private fun consentOp(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OP_ACTIVATE_VPN, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_ACTIVATE_VPN, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.onFailure { Log.w(TAG, "ACTIVATE_VPN okunamadi", it) }.getOrNull()

    private fun prepareIsNull(context: Context): Boolean =
        runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
}
