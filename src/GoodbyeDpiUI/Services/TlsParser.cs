using System.Buffers.Binary;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// TLS ClientHello icinde SNI (server_name) uzantisinin konumunu bulur; boylece
/// paketi SNI degerinin ortasindan bolebiliriz. Cozumleme her adimda sinir denetimi
/// yapar ve en ufak tutarsizlikta "bulunamadi" doner.
///
/// Buyuk ClientHello'lar (orn. Chromium'un ML-KEM anahtar paylasimli ~1.8 KB'lik
/// istegi) iki TCP parcasina yayilir; SNI ikinci parcadaysa burada bulunamaz ve
/// cagiran taraf yalnizca sabit konumdan boler.
/// </summary>
internal static class TlsParser
{
    /// <summary>ClientHello mi? Kayit tipi 0x16 (handshake), surum 0x03xx, handshake tipi 0x01.</summary>
    public static bool IsClientHello(byte[] p, int payloadStart, int payloadLen) =>
        payloadLen >= 6 &&
        p[payloadStart] == 0x16 &&
        p[payloadStart + 1] == 0x03 &&
        p[payloadStart + 5] == 0x01;

    /// <summary>
    /// SNI ana bilgisayar adinin baslangicini payload'a gore (0 tabanli) bayt ofseti
    /// olarak dondurur. Bulunamazsa 0.
    /// </summary>
    public static int FindSniOffset(byte[] p, int payloadStart, int payloadLen) =>
        TryFindSni(p, payloadStart, payloadLen, out var offset, out _) ? offset : 0;

    /// <summary>
    /// SNI adinin payload icindeki ofsetini ve (bu pakette gorunen kismin) uzunlugunu bulur.
    /// </summary>
    public static bool TryFindSni(byte[] p, int payloadStart, int payloadLen, out int nameOffset, out int nameLength)
    {
        nameOffset = 0;
        nameLength = 0;

        // Kayit basligi (5) + handshake basligi (4) + surum/random (34) en az gerekir.
        if (payloadLen < 43 || payloadStart < 0 || payloadStart + payloadLen > p.Length) return false;

        var span = new ReadOnlySpan<byte>(p, payloadStart, payloadLen);

        // Kayit: [0]=0x16 handshake, [1..2]=surum, [3..4]=uzunluk
        if (span[0] != 0x16) return false;

        var pos = 5; // handshake mesaji buradan baslar

        // Handshake: [0]=0x01 ClientHello, [1..3]=uzunluk
        if (span[pos] != 0x01) return false;
        pos += 4;

        // client_version (2) + random (32)
        pos += 2 + 32;
        if (pos + 1 > span.Length) return false;

        // session_id
        int sessionIdLen = span[pos];
        pos += 1 + sessionIdLen;
        if (pos + 2 > span.Length) return false;

        // cipher_suites
        int cipherLen = BinaryPrimitives.ReadUInt16BigEndian(span[pos..]);
        pos += 2 + cipherLen;
        if (pos + 1 > span.Length) return false;

        // compression_methods
        int compLen = span[pos];
        pos += 1 + compLen;
        if (pos + 2 > span.Length) return false;

        // extensions
        int extTotal = BinaryPrimitives.ReadUInt16BigEndian(span[pos..]);
        pos += 2;
        var extEnd = Math.Min(span.Length, pos + extTotal);

        while (pos + 4 <= extEnd)
        {
            int extType = BinaryPrimitives.ReadUInt16BigEndian(span[pos..]);
            int extLen = BinaryPrimitives.ReadUInt16BigEndian(span[(pos + 2)..]);
            var extData = pos + 4;

            if (extType == 0x0000) // server_name
            {
                // list_len(2) + name_type(1) + name_len(2) + name
                if (extData + 5 > span.Length) return false;
                if (span[extData + 2] != 0) return false; // host_name degil

                int len = BinaryPrimitives.ReadUInt16BigEndian(span[(extData + 3)..]);
                var start = extData + 5;
                if (len == 0 || start >= span.Length) return false;

                nameOffset = start;
                nameLength = Math.Min(len, span.Length - start);
                return true;
            }

            pos = extData + extLen;
        }

        return false;
    }
}
