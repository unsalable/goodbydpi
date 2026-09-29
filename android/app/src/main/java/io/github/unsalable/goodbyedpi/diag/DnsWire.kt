package io.github.unsalable.goodbyedpi.diag

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.InetAddress

/**
 * DNS-over-TCP icin en kucuk kodlayici/cozucu (RFC 1035 + RFC 7766 uzunluk oneki). Saf Kotlin:
 * JVM testleri Android'siz calistirir.
 *
 * Neden var: baglanti testi adlari byedpi'ye ad olarak (SOCKS ATYP=3) vermiyor; byedpi -N ile
 * bunlari reddediyor (olay dongusunde bloklayan getaddrinfo olmasin diye) ve zaten bizim uid'imiz
 * VPN disinda oldugu icin ISS'in DNS'ine giderdi. Bunun yerine sanal cozucuye (198.18.0.53:53)
 * SOCKS uzerinden TCP ile soruyoruz; byedpi --redirect onu kullanicinin sectigi DNS'e iletir,
 * yani test tun'daki uygulamalarla ayni cevabi gorur.
 */
internal object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val HEADER_LEN = 12
    private const val MAX_NAME_LEN = 253
    private const val MAX_LABEL_LEN = 63

    /** Bir cevabin ozeti: RCODE ve istenen turdeki adresler (CNAME zinciri atlanir). */
    data class Answer(val rcode: Int, val addresses: List<InetAddress>)

    /** Bicimi bozuk ya da bize ait olmayan yanit. */
    class FormatException(message: String) : IOException(message)

    /** Tek soruluk, yinelemeli (RD) sorgu mesaji; uzunluk oneki yok. */
    fun query(id: Int, name: String, type: Int): ByteArray {
        val ascii = try {
            IDN.toASCII(name.trim().trimEnd('.'))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("gecersiz alan adi: $name", e)
        }
        require(ascii.isNotEmpty() && ascii.length <= MAX_NAME_LEN) { "gecersiz alan adi: $name" }
        val out = ByteArrayOutputStream(HEADER_LEN + ascii.length + 6)
        out.u16(id and 0xFFFF)
        out.u16(0x0100) // RD: cozucu adi bizim yerimize sonuna kadar izlesin
        out.u16(1) // QDCOUNT
        out.u16(0)
        out.u16(0)
        out.u16(0)
        for (label in ascii.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= MAX_LABEL_LEN) { "gecersiz etiket: $name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.u16(type)
        out.u16(CLASS_IN)
        return out.toByteArray()
    }

    /** TCP uzerinde her mesajin onunde 2 baytlik uzunluk olur (RFC 1035 4.2.2). */
    fun writeTcp(out: OutputStream, message: ByteArray) {
        require(message.size <= 0xFFFF)
        val framed = ByteArray(message.size + 2)
        framed[0] = (message.size ushr 8).toByte()
        framed[1] = message.size.toByte()
        message.copyInto(framed, 2)
        out.write(framed)
        out.flush()
    }

    fun readTcp(input: InputStream): ByteArray {
        val din = DataInputStream(input)
        val len = din.readUnsignedShort()
        if (len < HEADER_LEN) throw FormatException("kisa DNS yaniti ($len bayt)")
        return ByteArray(len).also { din.readFully(it) }
    }

    /**
     * [id]'li sorgunun cevabini okur. Kimlik tutmazsa, cevap bayragi yoksa ya da mesaj
     * sinirlarin disina tasiyorsa [FormatException].
     */
    fun parse(message: ByteArray, id: Int, type: Int): Answer {
        if (message.size < HEADER_LEN) throw FormatException("kisa DNS yaniti")
        if (message.u16(0) != (id and 0xFFFF)) throw FormatException("DNS kimligi tutmuyor")
        val flags = message.u16(2)
        if (flags and 0x8000 == 0) throw FormatException("DNS cevabi degil")
        val rcode = flags and 0x000F
        val qd = message.u16(4)
        val an = message.u16(6)

        var pos = HEADER_LEN
        repeat(qd) {
            pos = skipName(message, pos)
            pos += 4 // QTYPE + QCLASS
        }
        val out = ArrayList<InetAddress>(an)
        repeat(an) {
            pos = skipName(message, pos)
            if (pos + 10 > message.size) throw FormatException("kesik kayit")
            val rType = message.u16(pos)
            val rClass = message.u16(pos + 2)
            val rdLen = message.u16(pos + 8)
            pos += 10
            if (pos + rdLen > message.size) throw FormatException("kesik kayit verisi")
            val wanted = when (type) {
                TYPE_A -> 4
                TYPE_AAAA -> 16
                else -> -1
            }
            if (rType == type && rClass == CLASS_IN && rdLen == wanted) {
                out += InetAddress.getByAddress(message.copyOfRange(pos, pos + rdLen))
            }
            pos += rdLen
        }
        return Answer(rcode, out)
    }

    /** Ad alanini atlar (sikistirma isaretcisi dahil); ad sonrasindaki konumu dondurur. */
    private fun skipName(m: ByteArray, start: Int): Int {
        var pos = start
        var labels = 0
        while (true) {
            if (pos >= m.size) throw FormatException("kesik ad")
            val len = m[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= m.size) throw FormatException("kesik isaretci")
                    return pos + 2
                }
                len and 0xC0 != 0 -> throw FormatException("bilinmeyen etiket turu")
                else -> pos += 1 + len
            }
            if (++labels > 128) throw FormatException("cok uzun ad")
        }
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArray.u16(at: Int): Int {
        if (at + 1 >= size) throw FormatException("kesik mesaj")
        return ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)
    }
}
