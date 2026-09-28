package io.github.unsalable.goodbyedpi.probe

import android.app.Activity
import android.os.Bundle

/**
 * Yer tutucu. Sonraki katman: `am start -n io.github.unsalable.goodbyedpi.probe/.ProbeActivity
 * --es urls a,b,c --ei parallel N` ile istekleri atip sonuclari GDPI_PROBE etiketiyle logcat'e
 * ve files/probe.json'a yazacak.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
