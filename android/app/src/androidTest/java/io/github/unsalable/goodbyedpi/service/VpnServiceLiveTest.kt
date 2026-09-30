package io.github.unsalable.goodbyedpi.service

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.unsalable.goodbyedpi.MainActivity
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.IspProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Gercek VPN, uygulama surecinde: servis ServiceController ile baslar, kabuk (uid 2000, VPN'den
 * gecer) tun uzerinden HTTP yapar, ayarlar canli degisince motor yeniden kurulur, DNS
 * yonlendirmesi gercekten etkili, durdurunca tun0 kalkar.
 *
 * Emulator/cihazda VPN onayi gerekir: test "appops set ACTIVATE_VPN allow" ile verir.
 * Sahte paketli yontemler emulatorde baglantiyi bozdugu icin (SPEC 7) sahtesiz yontemler.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class VpnServiceLiveTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val repo = SettingsRepository.get(ctx)

    @Before
    fun setUp() {
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        // Ilk Running'den sonra acilan "hizli ayarlara ekle" sistem penceresi (1.0.1) odagi alip
        // sonraki arayuz testlerini dusurmesin.
        QuickTileState.markPrompted(ctx)
        // Kullanici arayuzden baglaniyormus gibi: uygulama on planda (Android 12+ arka plandan
        // on plan servis baslatmayi yasakliyor).
        inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        runBlocking {
            // Onceki bir calismadan kalan istek (wantRunning) sureci baslarken VPN'i geri
            // getirmis olabilir (karo, recoverIfNeeded): temiz baslangic.
            if (EngineStateHolder.state.value != EngineState.Stopped) {
                ServiceController.stop(ctx)
                awaitState(15_000) { it is EngineState.Stopped || it is EngineState.Failed }
            }
            repo.update {
                it.copy(isp = IspProfile.GENERAL_ID, method = "split2", dns = DnsProfile.YANDEX_ID, autoFallback = true, excludeLan = true, ipv6 = true)
            }
        }
    }

    @After
    fun tearDown() {
        ServiceController.stop(ctx)
        runBlocking { awaitState(15_000) { it is EngineState.Stopped || it is EngineState.Failed } }
    }

    @Test
    fun startTrafficLiveRestartDnsAndStop() = runBlocking {
        assertNull("VPN onayi yok", ServiceController.start(ctx))
        val r1 = awaitRunning { true }
        Log.i(TAG, "calisiyor: $r1")
        assertEquals("Düz bölme", r1.methodName)
        assertEquals("Yandex (1253)", r1.dnsName)
        assertTrue(repo.current.wantRunning)
        // C1: calisan argv durumda (Tanilama bunu gosterir), secilen port dahil.
        assertTrue(r1.argv.joinToString(" ").contains("-p ${r1.socksPort}"))

        assertTrue(shell("ip addr show tun0").contains("inet 198.18.0.1/32"))
        val net1 = vpnNetId()
        assertTrue("VPN agi yok", net1 != null)
        assertEquals("HTTP/1.1 200 OK", httpViaShell("example.com"))
        // Trafik tun'dan gecti: sayac artti (akisi dinleyen biz; ornekleme yalnizca dinlerken).
        val traffic = withTimeout(5_000) { EngineStateHolder.traffic.first { it.txBytes > 0 && it.rxBytes > 0 } }
        Log.i(TAG, "trafik: $traffic")

        // Ayni ayarla restartIfRunning bir sey yapmaz (port ayni kalir).
        ServiceController.restartIfRunning(ctx)
        Thread.sleep(1_000)
        assertEquals(r1, EngineStateHolder.state.value)

        // Canli yontem degisimi: arayuz yalnizca ayari yazar, servis ~400 ms sonra yalnizca
        // byedpi'yi AYNI portta degistirir; VPN agi (ve uygulamalarin baglantisi) dusmez.
        repo.update { it.copy(method = "tlsrec") }
        val r2 = awaitRunning { it.methodName == "TLS kayıt bölme" }
        Log.i(TAG, "yontem degisti: $r2")
        assertEquals(r1.socksPort, r2.socksPort)
        assertEquals(r1.sinceElapsed, r2.sinceElapsed)
        assertTrue(r2.argv.contains("--tlsrec"))
        assertEquals("VPN agi yeniden kuruldu", net1, vpnNetId())
        assertEquals("HTTP/1.1 200 OK", httpViaShell("example.com"))

        // DNS gercekten bizim yonlendirmemizden geciyor: dinlenmeyen bir porta yonlenince ad
        // cozulemez. 53. port kullanilmiyor: bu makinedeki masaustu GoodbyeDPI her 53 sorgusunu
        // kendisi cevapliyor; geri dongu:9 ise hic cevap vermez.
        val dead = CustomDnsEntry.createNew(repo.current.customDns, CustomDnsEntry(name = "Ölü DNS", v4 = "127.0.0.1", v4Port = 9))
        repo.update { s -> s.copy(customDns = s.customDns + dead, dns = dead.id) }
        val r3 = awaitRunning { it.dnsName == dead.name }
        // Yalnizca IPv4 adresli DNS: VPN'e verilen sunucular degisti (IPv6 sanal cozucu yok),
        // tun eski acikken yeniden kuruldu; ag yine ayni.
        assertEquals("VPN agi yeniden kuruldu", net1, vpnNetId())
        // C4: yalnizca ad degisimi motora dokunmaz, durum/bildirim metni tazelenir.
        repo.update { s -> s.copy(customDns = s.customDns.map { if (it.id == dead.id) it.copy(name = "Ölü DNS 2") else it }) }
        val r4 = awaitRunning { it.dnsName == "Ölü DNS 2" }
        assertEquals(r3.copy(dnsName = r4.dnsName), r4)
        val failed = httpViaShell("example.org", timeoutSec = 6)
        Log.i(TAG, "olu DNS ile: '$failed'")
        assertFalse(failed.startsWith("HTTP/"))

        // DNS Kapali: VPN'e alttaki agin DNS'i verilir, cozumleme calisir.
        repo.update { it.copy(dns = DnsProfile.OFF_ID) }
        awaitRunning { it.dnsName == "Kapalı" }
        assertEquals("VPN agi yeniden kuruldu", net1, vpnNetId())
        val lp = shell("dumpsys connectivity").lines().first { it.contains("VPN CONNECTED extra: VPN:${ctx.packageName}") }
        Log.i(TAG, "Kapali DNS: " + Regex("DnsAddresses: \\[[^]]*]").find(lp)?.value)
        assertFalse(lp.contains("198.18.0.53"))
        assertEquals("HTTP/1.1 200 OK", httpViaShell("example.net"))

        // Durdur: tun0 kalkar, istek kapanir.
        ServiceController.stop(ctx)
        awaitState(10_000) { it is EngineState.Stopped }
        Thread.sleep(500)
        assertFalse(shell("ip addr show tun0").contains("198.18.0.1"))
        assertFalse(repo.current.wantRunning)
        assertFalse(shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:${ctx.packageName}"))
    }

    /** Bizim VPN agimizin kimligi (dumpsys connectivity "network{N}"); yoksa null. */
    private fun vpnNetId(): Int? {
        val line = shell("dumpsys connectivity").lines()
            .firstOrNull { it.contains("NetworkAgentInfo{") && it.contains("VPN CONNECTED extra: VPN:${ctx.packageName}") }
            ?: return null
        return Regex("""network\{(\d+)\}""").find(line)?.groupValues?.get(1)?.toInt()
    }

    private suspend fun awaitState(timeoutMs: Long, pred: (EngineState) -> Boolean): EngineState =
        withTimeout(timeoutMs) { EngineStateHolder.state.first(pred) }

    private suspend fun awaitRunning(pred: (EngineState.Running) -> Boolean): EngineState.Running =
        awaitState(15_000) { it is EngineState.Running && pred(it) } as EngineState.Running

    /** Kabuk komutu (uid 2000); cikisin tamami. */
    private fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /**
     * uid 2000 olarak (VPN'den gecer) duz HTTP; ilk satir. stdin, cevap okunana kadar acik
     * tutulur: byedpi TCP yari-kapatmayi tam kapatma sayiyor (BYEDPI_NOTES 10).
     */
    private fun httpViaShell(host: String, timeoutSec: Int = 10): String {
        val (out, inp) = inst.uiAutomation.executeShellCommandRw("toybox nc -W $timeoutSec $host 80")
        val w = ParcelFileDescriptor.AutoCloseOutputStream(inp)
        val pool = Executors.newSingleThreadExecutor()
        return try {
            w.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            w.flush()
            // Ad cozulemeyince nc cikiyor ama kabuk borusu EOF vermeyebiliyor: sinirli bekle.
            val line = pool.submit<String> { FileInputStream(out.fileDescriptor).bufferedReader().readLine() ?: "" }
            try {
                line.get(timeoutSec + 25L, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                ""
            }
        } finally {
            runCatching { w.close() }
            runCatching { out.close() }
            pool.shutdownNow()
        }
    }

    private companion object {
        const val TAG = "GdpiTest"
    }
}
