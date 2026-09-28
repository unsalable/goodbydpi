package io.github.unsalable.goodbyedpi.engine

// SOZLESME (wave 2): imzalar sabit. STUB govde; "runtime" ajani BYEDPI_NOTES.md'ye gore doldurur.

object ByeDpiArgs {
    /** byedpi argv'si (program adi haric). */
    fun build(config: EngineConfig): List<String> = listOf("-i", "127.0.0.1", "-p", config.socksPort.toString())

    /** Tani ekrani icin okunabilir tam komut satiri. */
    fun describe(config: EngineConfig): String = "ciadpi " + build(config).joinToString(" ")
}
