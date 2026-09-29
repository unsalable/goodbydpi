#!/system/bin/sh
# GoodbyeDPI Android - hev + byedpi tun gecikme testi (emulatorde root ile, APK'ya GIRMEZ).
#
# Hazirlik (host): top-level jni'yi GDPI_NATIVE_TOOLS=1 APP_ABI=x86_64 ile derle
# (hev-socks5-tunnel-bin), tools/native'i derle (ciadpi, tun_latency); ucunu ve bu
# betigi $D'ye it. Calistir: adb shell sh $D/tun_latency.sh
#
# Hedef cihazin kendisi: tun_latency 127.0.0.1:$SRV'de yankilayici acar, uygulama trafigi
# sanal 198.18.0.99:$SRV'ye gider, byedpi --redirect ile yerel yankilayiciya baglar. Boylece
# emulatorun slirp NAT'inin (disari her gidiste yuzlerce ms oynuyor) gurultusu olcume
# girmez; fark yalnizca hev + byedpi maliyetidir (her deneme yeni oturum = yeni SOCKS5
# el sikismasi). Ayrica --deny-net'in sanal agdaki diger adresleri hemen reddettigi
# denetlenir (198.18.0.98).
#
# Yalnizca uid $U (varsayilan 2999; baska testlerin kullandigi shell uid'i 2000 degil)
# trafigi tun'a yonlenir; ciadpi ve hev root olarak calisir, kendi trafikleri tun'a girmez.
# Portlar 18085-18086 (smoke araligi 18080-18099 icinde), tablo 4243.
D=${D:-/data/local/tmp/gdpi-ndpi}
U=${U:-2999}
N=${N:-10}
PORT=18085
SRV=18086
cd $D || exit 1
cat > $D/lat.yml <<EOF
tunnel:
  name: tunlat
  mtu: 8500
  ipv4: 198.18.0.1
socks5:
  address: 127.0.0.1
  port: $PORT
  udp: 'udp'
misc:
  task-stack-size: 24576
  tcp-buffer-size: 4096
  connect-timeout: 5000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-level: warn
EOF
cleanup() {
    ip rule del pref 51 2>/dev/null
    ip route flush table 4243 2>/dev/null
    [ -n "$HP" ] && kill $HP 2>/dev/null
    [ -n "$BP" ] && kill $BP 2>/dev/null
    pkill -f "tun_latency serve $SRV" 2>/dev/null
}
trap cleanup EXIT

./tun_latency serve $SRV &
sleep 0.3
echo "--- direct (no tun), uid $U"
su $U ./tun_latency udp 127.0.0.1 $SRV $N | tail -1
su $U ./tun_latency tcp 127.0.0.1 $SRV $N | tail -1

./ciadpi -i 127.0.0.1 -p $PORT -N -x 1 \
    --redirect 198.18.0.99:$SRV=127.0.0.1:$SRV --deny-net 198.18.0.0/15 \
    > lat_ciadpi.log 2>&1 &
BP=$!
sleep 0.5
./hev-socks5-tunnel-bin lat.yml > lat_hev.log 2>&1 &
HP=$!
sleep 1
ip rule add uidrange $U-$U lookup 4243 pref 51
ip route add default dev tunlat table 4243

echo "--- via tun (hev -> ciadpi --redirect -> local echo), uid $U"
su $U ./tun_latency udp 198.18.0.99 $SRV $N
su $U ./tun_latency tcp 198.18.0.99 $SRV $N
echo "--- denied virtual address (expect fast failure)"
su $U ./tun_latency tcp 198.18.0.98 $SRV 2
# Trafik gercekten tun'dan mi gecti: paket sayaclari ve byedpi'nin kabul ettigi oturumlar
ip -s link show tunlat | sed -n 3,6p
echo "ciadpi accepted: $(grep -c 'accept:' lat_ciadpi.log), denied: $(grep -c 'deny tcp' lat_ciadpi.log)"
