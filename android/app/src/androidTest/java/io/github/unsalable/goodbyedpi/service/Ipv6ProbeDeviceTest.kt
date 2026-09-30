package io.github.unsalable.goodbyedpi.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IPv6 erisim denemesinin TLS yolu ART/Conscrypt'te (1.0.2 F4). Emulatorun IPv6 cikisi yok;
 * ayni anycast hizmetlerin IPv4 adresleriyle "gecti" yolu sinanir: SNI'siz (IP yazisi) el
 * sikisma, zincir dogrulamasi ve sure. JVM testleri yalnizca basarisiz yollari kapsiyor.
 */
@RunWith(AndroidJUnit4::class)
class Ipv6ProbeDeviceTest {
    @Test
    fun tlsHandshakeWithoutSniPassesAgainstPublicDnsAnycast() {
        for (target in listOf("8.8.8.8", "1.1.1.1")) {
            val r = Ipv6Probe.runWith(listOf(target), Ipv6Probe.PORT, Ipv6Probe.TIMEOUT_MS) {}
            assertTrue("$target: $r", r.ok)
            assertTrue(r.detail, r.detail.endsWith(" ms"))
        }
    }
}
