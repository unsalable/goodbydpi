using System.Buffers.Binary;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// TLS ClientHello icinde SNI (server_name) uzantisinin konumunu bulur; boylece
/// paketi tam SNI degerinden once bolebiliriz. Cozumleme her adimda sinir denetimi
/// yapar ve en ufak tutarsizlikta 0 doner (SNI bulunamadi).
/// </summary>
internal static class TlsParser
{
    /// <summary>
    /// SNI ana bilgisayar adinin baslangicini payload'a gore (0 tabanli) bayt ofseti
    /// olarak dondurur. Bulunamazsa 0.
    /// </summary>
    /// <param name="p">Paket tamponu.</param>
    /// <param name="payloadStart">TCP payload'unun tampon icindeki baslangici.</param>
    /// <param name="payloadLen">TCP payload uzunlugu.</param>
    public static int FindSniOffset(byte[] p, int payloadStart, int payloadLen)
    {
        // TLS kayit basligi (5) + handshake tipi (1) en az gerekir.
        if (payloadLen < 43) return 0;

        var span = new ReadOnlySpan<byte>(p, payloadStart, payloadLen);

        // Kayit: [0]=0x16 handshake, [1..2]=surum, [3..4]=uzunluk
        if (span[0] != 0x16) return 0;

        var pos = 5; // handshake mesaji buradan baslar

        // Handshake: [0]=0x01 ClientHello, [1..3]=uzunluk
        if (pos + 4 > span.Length || span[pos] != 0x01) return 0;
        pos += 4;

        // client_version (2) + random (32)
        pos += 2 + 32;
        if (pos + 1 > span.Length) return 0;

        // session_id
        int sessionIdLen = span[pos];
        pos += 1 + sessionIdLen;
        if (pos + 2 > span.Length) return 0;

        // cipher_suites
        int cipherLen = BinaryPrimitives.ReadUInt16BigEndian(span[pos..]);
        pos += 2 + cipherLen;
        if (pos + 1 > span.Length) return 0;

        // compression_methods
        int compLen = span[pos];
        pos += 1 + compLen;
        if (pos + 2 > span.Length) return 0;

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
                // server_name_list uzunlugu (2) + [tip(1)][ad uzunlugu(2)][ad]
                var q = extData;
                if (q + 5 > span.Length) return 0;

                // q: list length (2). tip @q+2, ad uzunlugu @q+3..4, ad @q+5.
                int nameType = span[q + 2];
                if (nameType != 0) return 0; // host_name degil

                var nameStart = q + 5;
                if (nameStart >= span.Length) return 0;

                return nameStart; // payload'a gore ofset
            }

            pos = extData + extLen;
        }

        return 0;
    }
}
