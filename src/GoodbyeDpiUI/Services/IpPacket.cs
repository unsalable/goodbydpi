using System.Buffers.Binary;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Bir IPv4/IPv6 + TCP/UDP paketinin hafif cozumlemesi. Yalnizca NativeDpiService'in
/// ihtiyac duydugu alanlari (adres/port ofsetleri, payload konumu, TTL/SEQ) tutar ve
/// bunlari yerinde degistiren yardimcilar sunar. Cozumleme basarisizsa
/// <see cref="Valid"/> false olur ve paket dokunulmadan gecirilir.
///
/// Not: IPv6 uzanti basliklari yok sayilir; TLS/DNS trafigi bunlari kullanmaz.
/// </summary>
internal readonly struct IpPacket
{
    public bool Valid { get; private init; }
    public bool IsV6 { get; private init; }
    public int Protocol { get; private init; }

    private byte[] Buffer { get; init; }
    private int L4Offset { get; init; }        // TCP/UDP basliginin baslangici
    private int SrcAddrOffset { get; init; }
    private int DstAddrOffset { get; init; }
    private int AddrLen { get; init; }          // 4 (v4) veya 16 (v6)

    public int PayloadOffset { get; private init; }
    public int PayloadLength { get; private init; }

    public ushort SrcPort { get; private init; }
    public ushort DstPort { get; private init; }
    public uint TcpSeq { get; private init; }
    public uint TcpAck { get; private init; }

    /// <summary>IPv4 TTL / IPv6 Hop Limit.</summary>
    public int Ttl { get; private init; }

    /// <summary>TCP bayraklari (FIN=0x01, SYN=0x02, RST=0x04, PSH=0x08, ACK=0x10).</summary>
    public int TcpFlags { get; private init; }

    public bool IsSynAck => Protocol == ProtocolTcp && (TcpFlags & 0x12) == 0x12;

    private const int ProtocolTcp = 6;
    private const int ProtocolUdp = 17;

    public static IpPacket Parse(byte[] p, int len)
    {
        if (len < 20) return default;

        var version = p[0] >> 4;
        return version switch
        {
            4 => ParseV4(p, len),
            6 => ParseV6(p, len),
            _ => default,
        };
    }

    private static IpPacket ParseV4(byte[] p, int len)
    {
        var ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || ihl > len) return default;

        // IP parcasi (MF bayragi ya da sifir olmayan ofset): L4 basligi guvenilmez, dokunma.
        if ((BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(6)) & 0x3FFF) != 0) return default;

        int protocol = p[9];
        return BuildL4(p, len, isV6: false, l4Offset: ihl, protocol: protocol,
            srcAddrOffset: 12, dstAddrOffset: 16, addrLen: 4);
    }

    private static IpPacket ParseV6(byte[] p, int len)
    {
        const int headerLen = 40;
        if (len < headerLen) return default;

        int nextHeader = p[6];
        // Uzanti basligi varsa cozumlemeyi birak; paket dokunulmadan gecer.
        if (nextHeader is not (ProtocolTcp or ProtocolUdp)) return default;

        return BuildL4(p, len, isV6: true, l4Offset: headerLen, protocol: nextHeader,
            srcAddrOffset: 8, dstAddrOffset: 24, addrLen: 16);
    }

    private static IpPacket BuildL4(byte[] p, int len, bool isV6, int l4Offset, int protocol,
        int srcAddrOffset, int dstAddrOffset, int addrLen)
    {
        int payloadOffset;

        if (protocol == ProtocolTcp)
        {
            if (l4Offset + 20 > len) return default;
            var dataOffset = (p[l4Offset + 12] >> 4) * 4;
            if (dataOffset < 20) return default;
            payloadOffset = l4Offset + dataOffset;
        }
        else if (protocol == ProtocolUdp)
        {
            if (l4Offset + 8 > len) return default;
            payloadOffset = l4Offset + 8;
        }
        else
        {
            return default;
        }

        if (payloadOffset > len) return default;

        return new IpPacket
        {
            Valid = true,
            IsV6 = isV6,
            Protocol = protocol,
            Buffer = p,
            L4Offset = l4Offset,
            SrcAddrOffset = srcAddrOffset,
            DstAddrOffset = dstAddrOffset,
            AddrLen = addrLen,
            PayloadOffset = payloadOffset,
            PayloadLength = len - payloadOffset,
            SrcPort = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(l4Offset)),
            DstPort = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(l4Offset + 2)),
            TcpSeq = protocol == ProtocolTcp
                ? BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(l4Offset + 4))
                : 0,
            TcpAck = protocol == ProtocolTcp
                ? BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(l4Offset + 8))
                : 0,
            TcpFlags = protocol == ProtocolTcp ? p[l4Offset + 13] : 0,
            Ttl = isV6 ? p[7] : p[8],
        };
    }

    // ------------------------------------------------------ okuma

    public readonly byte[] GetDstAddrBytes()
    {
        var b = new byte[AddrLen];
        Array.Copy(Buffer, DstAddrOffset, b, 0, AddrLen);
        return b;
    }

    public readonly byte[] GetSrcAddrBytes()
    {
        var b = new byte[AddrLen];
        Array.Copy(Buffer, SrcAddrOffset, b, 0, AddrLen);
        return b;
    }

    /// <summary>Adresin ilk 8 / son 8 baytini (v4'te tamamini) akis anahtari icin okur.</summary>
    public readonly (ulong Hi, ulong Lo) ReadAddr(bool source)
    {
        var off = source ? SrcAddrOffset : DstAddrOffset;
        if (AddrLen == 4)
            return (BinaryPrimitives.ReadUInt32BigEndian(Buffer.AsSpan(off)), 0);

        return (BinaryPrimitives.ReadUInt64BigEndian(Buffer.AsSpan(off)),
                BinaryPrimitives.ReadUInt64BigEndian(Buffer.AsSpan(off + 8)));
    }

    // ------------------------------------- yerinde degistirme (hedef tampon parametreyle)
    // Tampon, cozumlenen paketle ayni yerlesime sahip olmali (orijinal ya da kopyasi).

    public readonly void SetTtl(byte[] buf, int ttl)
    {
        // v4: TTL @8, v6: Hop Limit @7.
        buf[IsV6 ? 7 : 8] = (byte)ttl;
    }

    public readonly void SetTcpSeq(byte[] buf, uint seq)
    {
        if (Protocol != ProtocolTcp) return;
        BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(L4Offset + 4), seq);
    }

    public readonly void SetTcpAck(byte[] buf, uint ack)
    {
        if (Protocol != ProtocolTcp) return;
        BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(L4Offset + 8), ack);
    }

    /// <summary>
    /// TCP saglama toplamini bilerek gecersiz kilar. Once dogru saglama hesaplanmis
    /// olmali (GoodbyeDPI gibi): boylece sonuc kesinlikle yanlis olur.
    /// </summary>
    public readonly void CorruptTcpChecksum(byte[] buf)
    {
        if (Protocol != ProtocolTcp) return;
        var span = buf.AsSpan(L4Offset + 16);
        var current = BinaryPrimitives.ReadUInt16BigEndian(span);
        BinaryPrimitives.WriteUInt16BigEndian(span, unchecked((ushort)(current - 1)));
    }

    /// <summary>IP toplam uzunlugunu (v4) / payload uzunlugunu (v6) yeni boyuta gore yazar.</summary>
    public readonly void SetTotalLength(byte[] buf, int totalLen)
    {
        if (IsV6)
            BinaryPrimitives.WriteUInt16BigEndian(buf.AsSpan(4), (ushort)(totalLen - 40));
        else
            BinaryPrimitives.WriteUInt16BigEndian(buf.AsSpan(2), (ushort)totalLen);
    }

    public readonly void SetSrcAddr(byte[] buf, byte[] addr) => Array.Copy(addr, 0, buf, SrcAddrOffset, AddrLen);

    public readonly void SetDstAddr(byte[] buf, byte[] addr) => Array.Copy(addr, 0, buf, DstAddrOffset, AddrLen);

    public readonly void SetSrcPort(byte[] buf, ushort port) =>
        BinaryPrimitives.WriteUInt16BigEndian(buf.AsSpan(L4Offset), port);

    public readonly void SetDstPort(byte[] buf, ushort port) =>
        BinaryPrimitives.WriteUInt16BigEndian(buf.AsSpan(L4Offset + 2), port);
}
