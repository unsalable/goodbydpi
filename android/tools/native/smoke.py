#!/usr/bin/env python3
"""
GoodbyeDPI Android - byedpi duman testi (host'tan calisir).

Yaptiklari:
  1. android/tools/native/Android.mk'yi x86_64 icin ndk-build ile derler (--no-build ile atlanir)
     ve ikilileri emulatordeki /data/local/tmp/gdpi-smoke dizinine iter.
  2. BYEDPI_NOTES.md'deki her hazir yontemin TCP grubunu ciadpi ile 18080-18091 portlarinda
     calistirir, adb forward ile host'taki curl'u --socks5-hostname uzerinden gecirir.
  3. Kablo (tcpdump) kontrolleri: sahte paketin TTL'i / icerigi, disorder'in TTL 1 parcasi,
     tlsrec'in iki TLS kaydi, sahte TTL'in --ttl verilmezse 8 oldugu.
  4. DPI benzetimi (iptables string eslesmesi, yalnizca shell uid'i 2000): --auto / --timeout /
     --cache-ttl anlamlarini gercek bir DROP / RST karsisinda dogrular.
  5. Cihazda udp_socks_test (ASan'li ciadpi'ye karsi) ve restart_test (+ ASan) calistirir.
  6. --apk verilirse JniSmoke.java'yi (javac + d8) APK'nin kendi NativeBridge'ine karsi
     app_process ile calistirir: release APK'da R8'in adlari korudugunu da dogrular.
  7. PASS/FAIL tablosu basar, surecleri / yonlendirmeleri / iptables kurallarini temizler.

Emulator notu (SPEC 7): emulatorun slirp NAT'i TCP'yi host'ta yeniden baslatir, TTL'e bakmaz ve
her segmenti hemen onaylar. Sahte (fake) paket bu yuzden sunucuya ulasir ve baglanti bozulur;
disorder'in TTL 1 parcasi da hemen onaylanir (duz bolmeye doner). Tablo "arguman ayristirildi +
proxy ayakta" ile "istek basarili" sutunlarini ayirir; sahte yontemlerde istek hatasi beklenir
(EXPECTED), bunlarin dogrulugu kablo kontrolleriyle gosterilir.

Kullanim:  py -3 android/tools/native/smoke.py [--no-build] [--serial emulator-5554] [--quick] [--apk X.apk]
Yalnizca 18080-18099 portlarini kullanir.
"""

import argparse
import os
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parents[1]
DEV_DIR = "/data/local/tmp/gdpi-smoke"
NDK_VERSION = "29.0.14206865"

# -N: ByeDpiArgs gibi alan adi cozumleme kapali; curl adi host'ta cozer (--socks5, -4).
BASE = ["-i", "127.0.0.1", "-c", "2048", "-b", "16384", "-N", "-x", "1"]
DENY = ["--deny-net", "198.18.0.0/15", "--deny-net", "fd00:6764:7069::/48"]
FAKE_SNI = ["--fake-sni", "www.w3.org"]
ZEROS = ":\\x00\\x00\\x00\\x00"  # argv'de ters bolu + x + 00: byedpi parse_cform 4 sifir bayta cevirir

# BYEDPI_NOTES.md "Final mapping" tablosuyla ayni olmali (fragmentHttp acik: tls,http).
PRESETS = [
    # id, TCP grubu, sahte paket var mi (emulatorde istek hatasi beklenir)
    ("default",    ["--proto=tls,http", "--disorder", "2", "--split", "0+hm", "--fake", "-1", "--ttl", "5"] + FAKE_SNI, True),
    ("fixedttl",   ["--proto=tls,http", "--fake", "-1", "--ttl", "5"] + FAKE_SNI, True),
    ("disorder",   ["--proto=tls,http", "--disorder", "2"], False),
    ("ttl4",       ["--proto=tls,http", "--fake", "-1", "--ttl", "4"] + FAKE_SNI, True),
    ("ttl3",       ["--proto=tls,http", "--fake", "-1", "--ttl", "3"] + FAKE_SNI, True),
    ("md5sig",     ["--proto=tls,http", "--fake", "-1", "--md5sig", "--ttl", "5"] + FAKE_SNI, True),
    ("md5ttl3",    ["--proto=tls,http", "--fake", "-1", "--md5sig", "--ttl", "3"] + FAKE_SNI, True),
    ("fakesplit5", ["--proto=tls,http", "--fake", "2", "--fake", "-1", "--ttl", "5"] + FAKE_SNI, True),
    ("zerofake",   ["--proto=tls,http", "--fake", "-1", "--ttl", "5", "--fake-data", ZEROS], True),
    ("split2",     ["--proto=tls,http", "--split", "2"], False),
    ("split",      ["--proto=tls,http", "--split", "2", "--split", "0+hm"], False),
    ("tlsrec",     ["--proto=tls,http", "--tlsrec", "3+s"], False),
]

# Onerilen tam dizilim (default + Discord ses + otomatik yedek + DNS + QUIC engeli).
VOICE = []
for rng in ("50000-65535", "3478-3481", "19294-19344"):
    VOICE += ["--proto=udp", "--pf=" + rng, "--udp-fake", "6", "--ttl", "64", "--auto=none"]
FULL_LAYOUT = (
    ["--redirect", "198.18.0.53:53=77.88.8.8:1253",
     "--redirect", "[fd00:6764:7069::53]:53=[2a02:6b8::feed:0ff]:1253"]
    + DENY
    + ["--drop-udp", "443"]
    + VOICE
    + PRESETS[0][1]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--disorder", "2", "--cache-ttl", "3600"]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--split", "2", "--split", "0+hm", "--cache-ttl", "3600"]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--tlsrec", "3+s", "--cache-ttl", "3600"]
    + ["--timeout", "4:0:0:1"]
)

# Akilli mod (varsayilan): ayni dizilim, ama ilk TCP grubu (3) atlatmasiz; secili yontem (default)
# ilk yedek (4), ardindan ISS yedekleri. BYEDPI_NOTES 7.3 / ByeDpiArgsTest ile ayni olmali.
SMART_LAYOUT = (
    FULL_LAYOUT[:FULL_LAYOUT.index("--proto=tls,http")]  # taban + DNS + QUIC + ses gruplari
    + ["--proto=tls,http"]
    + ["--auto=torst,ssl_err"] + PRESETS[0][1] + ["--cache-ttl", "3600"]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--disorder", "2", "--cache-ttl", "3600"]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--split", "2", "--split", "0+hm", "--cache-ttl", "3600"]
    + ["--auto=torst,ssl_err", "--proto=tls,http", "--tlsrec", "3+s", "--cache-ttl", "3600"]
    + ["--timeout", "4:0:0:1"]
)
SMART_DIRECT_GROUP = "3"  # 3 ses grubundan sonraki ilk TCP grubu

URLS = ["https://example.com/", "https://www.google.com/", "http://example.com/"]

UDP_TEST_ARGS = [
    "--redirect", "198.18.0.53:53=77.88.8.8:1253",
    "--redirect", "[fd00:6764:7069::53]:53=127.0.0.1:18096",
    "--drop-udp", "443", "--drop-udp", "18095",
] + DENY + [
    "--proto=udp", "--pf=17900-18200", "--udp-fake", "2", "--auto=none",
    "--proto=tls", "--split", "1",
]


# ------------------------------------------------------------------ yardimcilar

def sdk_dir():
    for k in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(k):
            return Path(os.environ[k])
    lp = ANDROID / "local.properties"
    if lp.exists():
        for line in lp.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    return Path(os.path.expanduser("~")) / "AppData/Local/Android/Sdk"


class Adb:
    def __init__(self, serial):
        exe = "adb.exe" if os.name == "nt" else "adb"
        if not sdk_dir().is_dir():
            # Microsoft Store'un "python" takma adi AppData\Local'i sanallastirir, SDK'yi goremez
            raise SystemExit(f"Android SDK not visible at {sdk_dir()}; on Windows run with 'py -3' "
                             "instead of the WindowsApps python alias")
        self.exe = str(sdk_dir() / "platform-tools" / exe)
        self.serial = serial
        self.root = self.sh("id -u").strip() == "0"

    def run(self, *args, check=True, timeout=120):
        p = subprocess.run([self.exe, "-s", self.serial, *args], capture_output=True,
                           text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {p.stderr.strip()}")
        return p.stdout

    def sh(self, cmd, check=False, timeout=120):
        return self.run("shell", cmd, check=check, timeout=timeout)

    def push(self, local, remote):
        self.run("push", str(local), remote)

    def as_shell(self, cmd):
        """Komutu ayricaliksiz shell uid'i (2000) ile calistir: uygulama sureci gibi."""
        return f"su shell {cmd}" if self.root else cmd


def write_script(adb, name, body):
    """Kabuk alintilamasi Windows->adb->sh zincirinde bozulmasin diye betik dosyasi it."""
    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".sh", newline="\n") as f:
        f.write("#!/system/bin/sh\n" + body + "\n")
        tmp = f.name
    try:
        adb.push(tmp, f"{DEV_DIR}/{name}")
    finally:
        os.unlink(tmp)
    return f"{DEV_DIR}/{name}"


def shq(s):
    return "'" + s.replace("'", "'\\''") + "'"


def is_listening(adb, port):
    hexp = f":{port:04X} "
    out = adb.sh("cat /proc/net/tcp /proc/net/tcp6")
    return any(hexp in l and l.split()[3] == "0A" for l in out.splitlines()[1:] if len(l.split()) > 3)


class Proxy:
    """Cihazda arka planda ciadpi; pid dosyasi ve log ile."""

    def __init__(self, adb, name, port, args, binary="ciadpi", env=""):
        self.adb, self.name, self.port = adb, name, port
        self.log = f"{DEV_DIR}/{name}.log"
        self.pidf = f"{DEV_DIR}/{name}.pid"
        cmd = " ".join(shq(a) for a in [f"./{binary}", "-p", str(port)] + args)
        script = write_script(adb, f"run_{name}.sh",
                              f"cd {DEV_DIR} || exit 1\n{env}{cmd} > {self.log} 2>&1 &\necho $! > {self.pidf}")
        adb.sh(f"rm -f {self.pidf} {self.log}; " + adb.as_shell(f"sh {script}"))
        self.up = False
        for _ in range(40):
            if is_listening(adb, port):
                self.up = True
                break
            if not self.alive():
                break
            time.sleep(0.05)
        adb.run("forward", f"tcp:{port}", f"tcp:{port}")

    def alive(self):
        pid = self.adb.sh(f"cat {self.pidf} 2>/dev/null").strip()
        return bool(pid) and self.adb.sh(f"kill -0 {pid} 2>/dev/null && echo y").strip() == "y"

    def logtext(self):
        return self.adb.sh(f"cat {self.log}")

    def stop(self):
        pid = self.adb.sh(f"cat {self.pidf} 2>/dev/null").strip()
        if pid:
            self.adb.sh(f"kill {pid} 2>/dev/null; sleep 0.2; kill -9 {pid} 2>/dev/null")
        self.adb.run("forward", "--remove", f"tcp:{self.port}", check=False)


def curl(port, url, timeout=10, extra=()):
    exe = shutil.which("curl") or "curl"
    t0 = time.time()
    # --socks5 (hostname degil): proxy -N ile calisiyor, uygulamadaki gibi IP alir.
    p = subprocess.run([exe, "-sS", "-4", "-m", str(timeout), "-o", os.devnull, "-w", "%{http_code}",
                        "--socks5", f"127.0.0.1:{port}", *extra, url],
                       capture_output=True, text=True, timeout=timeout + 15)
    code = p.stdout.strip() or "000"
    return code, time.time() - t0, p.stderr.strip()


def ok_code(code):
    return code[:1] in ("2", "3")


# ------------------------------------------------------------------ kablo (tcpdump)

class Capture:
    def __init__(self, adb, name):
        self.adb, self.file = adb, f"{DEV_DIR}/{name}.pcap"
        # --immediate-mode: libpcap TPACKET_V3 ile paketleri blok dolunca ya da ~1 sn'lik blok
        # zaman asiminda teslim ediyor; curl'den 0.3 sn sonra gelen SIGINT o bloktakileri
        # yaziya gecirmeden kapatiyordu (dosyada yalnizca SYN kalir, kablo denetimi rastgele FAIL).
        script = write_script(adb, f"cap_{name}.sh",
                              f"tcpdump -i any --immediate-mode -U -nn -s 0 -w {self.file} 'tcp port 443' > /dev/null 2>&1 &\n"
                              f"echo $! > {self.file}.pid")
        adb.sh(f"rm -f {self.file}; sh {script}")
        time.sleep(0.8)

    def packets(self, dst_ip):
        """Hedefe giden, veri tasiyan paketler: (ttl, seq_bas, payload bytes)."""
        pid = self.adb.sh(f"cat {self.file}.pid").strip()
        self.adb.sh(f"kill -INT {pid}; sleep 0.5")
        txt = self.adb.sh(f"tcpdump -nn -v -x -r {self.file} 2>/dev/null", timeout=60)
        self.adb.sh(f"rm -f {self.file} {self.file}.pid")
        pkts, cur = [], None
        for line in txt.splitlines():
            if not line.startswith((" ", "\t")):
                if cur:
                    pkts.append(cur)
                m = re.search(r"\bttl (\d+)", line)
                cur = {"ttl": int(m.group(1)) if m else -1, "hdr": line, "hex": ""}
            elif cur is not None and re.match(r"\s+0x[0-9a-f]{4}:", line):
                cur["hex"] += "".join(line.split(":", 1)[1].split())
            elif cur is not None:
                cur["hdr"] += " " + line.strip()
        if cur:
            pkts.append(cur)
        out = []
        for p in pkts:
            raw = bytes.fromhex(p["hex"]) if p["hex"] else b""
            if len(raw) < 40 or raw[0] >> 4 != 4:
                continue
            ihl = (raw[0] & 15) * 4
            dst = ".".join(str(b) for b in raw[16:20])
            if dst != dst_ip:
                continue
            doff = (raw[ihl + 12] >> 4) * 4
            seq = int.from_bytes(raw[ihl + 4:ihl + 8], "big")
            payload = raw[ihl + doff:]
            if payload:
                out.append((p["ttl"], seq, payload))
        return out


def server_ip(log):
    m = re.search(r"new conn: .*addr=(\d+\.\d+\.\d+\.\d+):443", log)
    return m.group(1) if m else None


# ------------------------------------------------------------------ calistirma

def build(out):
    ndk = sdk_dir() / "ndk" / NDK_VERSION / ("ndk-build.cmd" if os.name == "nt" else "ndk-build")
    cmd = [str(ndk), "NDK_PROJECT_PATH=null", f"APP_BUILD_SCRIPT={HERE / 'Android.mk'}",
           f"NDK_OUT={out / 'obj'}", f"NDK_LIBS_OUT={out / 'libs'}", "APP_ABI=x86_64",
           "APP_PLATFORM=android-24", f"-j{os.cpu_count() or 4}"]
    p = subprocess.run(cmd, capture_output=True, text=True)
    warnings = [l for l in (p.stdout + p.stderr).splitlines() if "warning:" in l or "error:" in l]
    if p.returncode != 0:
        print(p.stdout[-3000:], p.stderr[-3000:])
        raise SystemExit("ndk-build failed")
    return warnings


def jni_smoke(adb, apk, out, row):
    """JniSmoke.java'yi derleyip APK'nin (release'de R8'li) NativeBridge'ine karsi calistirir."""
    jh = os.environ.get("JAVA_HOME")
    javac = str(Path(jh) / "bin" / "javac") if jh else (shutil.which("javac") or "javac")
    bts = sorted((sdk_dir() / "build-tools").iterdir())
    d8 = str(bts[-1] / ("d8.bat" if os.name == "nt" else "d8"))
    cls, dex = out / "jni" / "cls", out / "jni" / "dex"
    cls.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    subprocess.run([javac, "--release", "8", "-Xlint:-options", "-d", str(cls), str(HERE / "JniSmoke.java")],
                   check=True)
    classes = [str(p) for p in cls.rglob("*.class")]
    subprocess.run([d8, "--min-api", "24", "--output", str(dex)] + classes, check=True, shell=os.name == "nt")
    adb.push(dex / "classes.dex", f"{DEV_DIR}/jnismoke.dex")
    adb.push(apk, f"{DEV_DIR}/app.apk")
    script = write_script(adb, "run_jni.sh", "\n".join([
        f"cd {DEV_DIR} || exit 1",
        "rm -rf jnilib && mkdir -p jnilib && unzip -o -q app.apk 'lib/x86_64/*' -d jnilib && chmod -R 755 jnilib",
        (("su shell " if adb.root else "") +
         f"sh -c 'CLASSPATH={DEV_DIR}/app.apk:{DEV_DIR}/jnismoke.dex app_process "
         f"-Djava.library.path={DEV_DIR}/jnilib/lib/x86_64 / gdpitest.JniSmoke 18098' 2>&1"),
        "rm -rf jnilib app.apk jnismoke.dex",
    ]))
    txt = adb.sh(f"sh {script}", timeout=180)
    for line in txt.splitlines():
        m = re.match(r"(PASS|FAIL) ([^:\s]+)(?:: (.*))?", line)
        if m:
            row("jni", m.group(2), m.group(1), (m.group(3) or "")[:90])
    if "RESULT jni_smoke" not in txt:
        row("jni", "jni_smoke result", "FAIL", txt.strip()[-200:])


# ------------------------------------------------------------------ host sunuculari + SOCKS istemci
# Emulator host'un 127.0.0.1'ine 10.0.2.2 olarak ulasir (slirp host'ta yeniden baglar).

# Host'ta dinlenir; adb forward'larin (18080-18095) kullanmadigi portlar.
HOST_ECHO_PORT = 18097    # duz yankilayici (istemci once konusur)
HOST_TLS_PORT = 18098     # sahte ClientHello'ya TLS 1.2 ServerHello ile cevap veren sunucu
HOST_BANNER_PORT = 18099  # once konusan (SMTP benzeri) + yankilayan sunucu

# TLS 1.2 ServerHello: supported_versions (0x2b) YOK, bu yuzden byedpi'nin neq_tls_sid'i
# bir sey yakalamaz; is_tls_shello dogru oldugu icin on_response da tetiklenmez (DPI-4).
TLS12_SHELLO = (bytes.fromhex("160303003102") + bytes.fromhex("00002d0303") + bytes(32)
                + bytes.fromhex("00c02f000005ff01000100"))
TLS_ALERT = bytes.fromhex("15030300020230")  # fatal, unknown_ca


class HostServers:
    def __init__(self):
        self.socks = []
        for port, handler in ((HOST_ECHO_PORT, self._echo), (HOST_TLS_PORT, self._tls),
                              (HOST_BANNER_PORT, self._banner)):
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("127.0.0.1", port))
            s.listen(16)
            self.socks.append(s)
            threading.Thread(target=self._loop, args=(s, handler), daemon=True).start()

    @staticmethod
    def _loop(s, handler):
        while True:
            try:
                c, _ = s.accept()
            except OSError:
                return
            threading.Thread(target=handler, args=(c,), daemon=True).start()

    @staticmethod
    def _tls(c):
        # Ne gelirse gelsin (emulatorde sahte ClientHello sunucuya ulasir) ServerHello yolla,
        # sonra istemcinin kapatmasini bekle.
        try:
            c.settimeout(10)
            if c.recv(4096):
                c.sendall(TLS12_SHELLO)
                while c.recv(4096):
                    pass
        except OSError:
            pass
        finally:
            c.close()

    @staticmethod
    def _echo(c):
        HostServers._banner(c, b"")

    @staticmethod
    def _banner(c, banner=b"220 gdpi smoke\r\n"):
        try:
            c.settimeout(30)
            if banner:
                c.sendall(banner)
            while True:
                d = c.recv(4096)
                if not d:
                    break
                c.sendall(d)
        except OSError:
            pass
        finally:
            c.close()

    def close(self):
        for s in self.socks:
            s.close()


def socks_connect(port, ip, dport, timeout=8):
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise OSError("socks greeting")
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(ip) + struct.pack(">H", dport))
    r = b""
    while len(r) < 10:
        d = s.recv(10 - len(r))
        if not d:
            raise OSError("socks reply")
        r += d
    if r[1] != 0:
        raise OSError(f"socks reply {r[1]}")
    return s


def client_hello():
    """Gercek bir ClientHello (TLS 1.3 yetenekli, SNI'li); ag kullanmadan bellekte uretilir."""
    ctx = ssl.create_default_context()
    inc, out = ssl.MemoryBIO(), ssl.MemoryBIO()
    obj = ctx.wrap_bio(inc, out, server_hostname="gdpi-smoke.example")
    try:
        obj.do_handshake()
    except ssl.SSLWantReadError:
        pass
    return out.read()


def recv_until_closed(s, wait):
    """wait sn icinde karsi taraf kapatti mi? (True = kapatti / sifirladi)"""
    s.settimeout(wait)
    try:
        return s.recv(4096) == b""
    except socket.timeout:
        return False
    except OSError:
        return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--out", default=os.environ.get("GDPI_SMOKE_OUT", r"C:\t\gdpi-smoke" if os.name == "nt" else "/tmp/gdpi-smoke"))
    ap.add_argument("--quick", action="store_true", help="restart_test 10 tur, kablo/DPI testleri yok")
    ap.add_argument("--apk", help="x86_64 icerikli uygulama APK'si: NativeBridge JNI testini de calistir")
    a = ap.parse_args()

    out = Path(a.out)
    rows = []  # (grup, ad, sonuc, ayrinti)

    def row(group, name, status, detail=""):
        rows.append((group, name, status, detail))
        print(f"  [{status:8}] {group:6} {name:34} {detail}", flush=True)

    if not a.no_build:
        w = build(out)
        row("build", "ndk-build x86_64 tools", "PASS" if not w else "WARN", f"{len(w)} warnings")

    adb = Adb(a.serial)
    adb.sh(f"mkdir -p {DEV_DIR}")
    libs = out / "libs" / "x86_64"
    for f in sorted(libs.iterdir()):
        adb.push(f, f"{DEV_DIR}/{f.name}")
    adb.sh(f"chmod 755 {DEV_DIR}/*")
    print(f"device {a.serial} root={adb.root}", flush=True)

    procs = []
    iptables_used = False
    hosts = HostServers()
    try:
        # ---------------------------------------------------------- hazir yontemler
        for i, (pid_, group, fake) in enumerate(PRESETS):
            port = 18080 + i
            px = Proxy(adb, pid_, port, BASE + group)
            procs.append(px)
            log = px.logtext()
            parsed = px.up and "invalid value" not in log and "unknown option" not in log
            codes = []
            for url in URLS:
                code, dt, err = curl(port, url, timeout=8)
                codes.append(f"{code}/{dt:.1f}s")
            okc = sum(ok_code(c.split("/")[0]) for c in codes)
            row("preset", f"{pid_} argv+up", "PASS" if parsed else "FAIL", " ".join(group))
            if okc == len(URLS):
                st = "PASS"
            elif fake:
                st = "EXPECTED"  # emulator slirp: sahte segment sunucuya ulasiyor
            else:
                st = "FAIL"
            row("preset", f"{pid_} requests", st, " ".join(codes))
            px.stop()

        # ---------------------------------------------------------- tam dizilim
        px = Proxy(adb, "full", 18092, BASE + FULL_LAYOUT)
        procs.append(px)
        row("layout", "full layout argv+up", "PASS" if px.up and "invalid" not in px.logtext() else "FAIL",
            f"{len(FULL_LAYOUT)} tokens")
        for u in URLS:
            c, t, _ = curl(18092, u, timeout=20)
            # HTTP'de sahte istek (emulatorde sunucuya ulasir) 400 alir; bu bir tetikleyici
            # degil (torst/ssl_err yalnizca TLS/RST), bu yuzden HTTP emulatorde EXPECTED olabilir.
            st = "PASS" if ok_code(c) else ("EXPECTED" if u.startswith("http:") else "FAIL")
            row("layout", "full layout " + u.split("/")[2] + (" (http)" if u.startswith("http:") else ""),
                st, f"{c}/{t:.1f}s")
        log = px.logtext()
        saves = re.findall(r"save: ip=\S+, id=(\d+)", log)
        row("layout", "full layout fallback used", "PASS" if saves else "FAIL",
            f"fake primary broken by slirp -> saved groups {saves}")
        px.stop()

        # ---------------------------------------------------------- akilli mod dizilimi
        # Engelsiz sitelere hic dokunulmaz: slirp sahteyi sunucuya ulastirsa da (yakin sunucu
        # benzetimi) istek dogrudan gruptan gider, hicbir yedek tetiklenmez, HTTP de 200 alir.
        px = Proxy(adb, "smart", 18093, BASE + SMART_LAYOUT)
        procs.append(px)
        row("layout", "smart layout argv+up", "PASS" if px.up and "invalid" not in px.logtext() else "FAIL",
            f"{len(SMART_LAYOUT)} tokens")
        for u in URLS:
            c, t, _ = curl(18093, u, timeout=20)
            row("layout", "smart layout " + u.split("/")[2] + (" (http)" if u.startswith("http:") else ""),
                "PASS" if ok_code(c) else "FAIL", f"{c}/{t:.1f}s")
        log = px.logtext()
        groups = set(re.findall(r"desync TCP: group=(\d+)", log))
        saves = re.findall(r"save: ip=\S+, id=(\d+)", log)
        row("layout", "smart layout: direct group only", "PASS" if groups == {SMART_DIRECT_GROUP} and not saves else "FAIL",
            f"groups {sorted(groups)} saves {saves}")
        px.stop()

        # ---------------------------------------------------------- sahte yakin sunucuya ulasti (DPI-4)
        # Emulatorde sahte her zaman sunucuya ulasir: TTL'den yakin bir sunucunun birebir
        # benzetimi. Host'taki sunucu sahteye TLS 1.2 ServerHello ile cevap verir; istemci
        # (a) ikinci turu yollamadan kapatir ya da (b) ikinci turda bir TLS uyarisi yollar.
        # Iki durumda da sonraki baglanti yedek grupla (1) baslamali.
        fake_near = ["--proto=tls", "--fake", "-1", "--ttl", "5", "--fake-sni", "www.w3.org",
                     "--auto=torst,ssl_err", "--proto=tls", "--split", "1", "--cache-ttl", "60",
                     "--timeout", "4:0:0:1"]
        ch = client_hello()
        for case, marker in (("abort", "client closed after first server flight"),
                             ("alert", "tls alert in handshake round 2 (client)")):
            px = Proxy(adb, "near_" + case, 18095, BASE + fake_near)
            procs.append(px)
            detail = ""
            try:
                s = socks_connect(18095, "10.0.2.2", HOST_TLS_PORT)
                s.sendall(ch)
                s.settimeout(5)
                got = s.recv(4096)
                if case == "alert":
                    s.sendall(TLS_ALERT)
                s.close()
                time.sleep(0.5)
                s = socks_connect(18095, "10.0.2.2", HOST_TLS_PORT)
                s.sendall(ch)
                s.settimeout(5)
                s.recv(4096)
                s.close()
                time.sleep(0.3)
                detail = f"server flight {len(got)}B"
            except OSError as e:
                detail = f"client error {e}"
            log = px.logtext()
            px.stop()
            saves = re.findall(r"save: ip=\S+, id=(\d+)", log)
            groups = re.findall(r"desync TCP: group=(\d+)", log)
            ok = marker in log and saves == ["1"] and groups[-1:] == ["1"]
            row("dpi-sim", f"fake reached tls1.2 server ({case})", "PASS" if ok else "FAIL",
                f"{detail}; saves {saves}; groups {groups}")

        if adb.root and not a.quick:
            # ------------------------------------------------------ kablo kontrolleri
            def wire(name, group, check, url="https://example.com/"):
                slug = re.sub(r"[^a-z0-9]+", "_", name.lower())
                px = Proxy(adb, "wire_" + slug, 18093, BASE + group)
                procs.append(px)
                cap = Capture(adb, slug)
                curl(18093, url, timeout=6)
                time.sleep(0.3)
                ip = server_ip(px.logtext())
                pk = cap.packets(ip) if ip else []
                px.stop()
                ok, detail = check(pk)
                row("wire", name, "PASS" if ok else "FAIL", detail)

            def first_ttls(pk, n=4):
                return " ".join(f"ttl{t}/{len(p)}B" for t, _, p in pk[:n])

            wire("default fake+disorder", PRESETS[0][1], lambda pk: (
                any(t == 1 and len(p) == 2 for t, _, p in pk)
                and any(t == 5 and b"www.w3.org" in p for t, _, p in pk),
                "disorder ttl1/2B + fake ttl5 w/ SNI www.w3.org: " + first_ttls(pk)))
            wire("zerofake zeros", PRESETS[8][1], lambda pk: (
                any(t == 5 and len(p) > 100 and not any(p) for t, _, p in pk),
                "fake ttl5 all-zero: " + first_ttls(pk)))
            # Yamali: ikinci sahte parca sahteyi kaldigi yerden (2. bayt) surdurur; iki parca
            # birlesince tek, tutarli bir sahte ClientHello (www.w3.org) olur.
            wire("fakesplit5 coherent fake", PRESETS[7][1], lambda pk: (
                len(pk) >= 2 and pk[0][0] == 5 and len(pk[0][2]) == 2 and pk[1][0] == 5
                and (pk[0][2] + pk[1][2])[:3] == b"\x16\x03\x01"
                and pk[1][2][:3] != b"\x16\x03\x01" and b"www.w3.org" in pk[1][2],
                "fake 2B + fake continuing at byte 2: " + first_ttls(pk)
                + (f" (2nd starts {pk[1][2][:3].hex()})" if len(pk) > 1 else "")))
            wire("fake default ttl 8", ["--proto=tls", "--fake", "-1"], lambda pk: (
                any(t == 8 for t, _, _ in pk), "no --ttl -> " + first_ttls(pk)))
            wire("md5sig degrades to ttl", PRESETS[5][1], lambda pk: (
                any(t == 5 for t, _, _ in pk), first_ttls(pk)))

            def tlsrec_ok(pk):
                if not pk:
                    return False, "no packets"
                p = pk[0][2]
                l1 = int.from_bytes(p[3:5], "big")
                ok = p[:3] == b"\x16\x03\x01" and p[5 + l1:5 + l1 + 3] == b"\x16\x03\x01"
                return ok, f"record1 len={l1}, record2 header at {5 + l1}: {p[5 + l1:5 + l1 + 5].hex()}"
            wire("tlsrec two records", PRESETS[11][1], tlsrec_ok)
            wire("split2 first seg 2B", PRESETS[9][1], lambda pk: (
                bool(pk) and len(pk[0][2]) == 2 and pk[0][0] == 64, first_ttls(pk)))

            # ------------------------------------------------------ DPI benzetimi
            iptables_used = True

            def dpi(kind):
                # REJECT tcp-reset kuralin kendisinde -p tcp ister (yoksa EINVAL)
                act = "DROP" if kind == "drop" else "REJECT --reject-with tcp-reset"
                for ipt in ("iptables", "ip6tables"):
                    act6 = act
                    adb.sh(f"{ipt} -w -N gdpi_smoke 2>/dev/null; {ipt} -w -F gdpi_smoke; "
                           f"{ipt} -w -C OUTPUT -m owner --uid-owner 2000 -p tcp --dport 443 -j gdpi_smoke 2>/dev/null || "
                           f"{ipt} -w -I OUTPUT -m owner --uid-owner 2000 -p tcp --dport 443 -j gdpi_smoke; "
                           f"{ipt} -w -A gdpi_smoke -p tcp -m string --string example.com --algo bm -j {act6}")

            def sim(name, kind, group, expect_ok, curls=1, sleep_between=0.0, check_times=None,
                    want_saves=None):
                dpi(kind)
                px = Proxy(adb, "sim", 18094, BASE + group)
                procs.append(px)
                res = []
                for k in range(curls):
                    if k and sleep_between:
                        time.sleep(sleep_between)
                    res.append(curl(18094, "https://example.com/", timeout=15 if expect_ok else 6))
                log = px.logtext()
                px.stop()
                oks = [ok_code(c) for c, _, _ in res]
                good = all(o == expect_ok for o in oks)
                if good and check_times:
                    good = check_times([t for _, t, _ in res])
                saves = re.findall(r"save: ip=\S+, id=(\d+)", log)
                # Yedek grup gercekten devreye girdi mi (DPI kurali gercekten vurdu mu)?
                if good and want_saves is not None:
                    good = saves == want_saves if isinstance(want_saves, list) else bool(saves)
                row("dpi-sim", name, "PASS" if good else "FAIL",
                    " ".join(f"{c}/{t:.1f}s" for c, t, _ in res) + (f" | save->group {saves}" if saves else ""))

            sim("drop: no desync is blocked", "drop", ["--proto=tls"], False)
            sim("drop: --split 0+hm passes", "drop", ["--proto=tls", "--split", "0+hm"], True)
            sim("drop: --tlsrec 3+s passes", "drop", ["--proto=tls", "--tlsrec", "3+s"], True)
            # ayni senaryo, zamanlama: 1. yavas (zaman asimi), 2. hizli (onbellek), 7 sn sonra 3. yine yavas
            dpi("drop")
            px = Proxy(adb, "simt", 18094, BASE + ["--proto=tls", "--auto=torst,ssl_err", "--proto=tls",
                                                   "--split", "0+hm", "--cache-ttl", "5", "--timeout", "3:0:0:1"])
            procs.append(px)
            r1 = curl(18094, "https://example.com/", 15)
            r2 = curl(18094, "https://example.com/", 15)
            time.sleep(7)
            r3 = curl(18094, "https://example.com/", 15)
            px.stop()
            good = all(ok_code(r[0]) for r in (r1, r2, r3)) and r1[1] >= 2.5 and r2[1] < 1.5 and r3[1] >= 2.5
            row("dpi-sim", "timeout 3s / cache hit / cache-ttl 5s", "PASS" if good else "FAIL",
                f"1st {r1[0]}/{r1[1]:.1f}s 2nd {r2[0]}/{r2[1]:.1f}s after7s {r3[0]}/{r3[1]:.1f}s")
            sim("rst: torst->fallback", "rst",
                ["--proto=tls", "--auto=torst", "--proto=tls", "--split", "0+hm"], True,
                check_times=lambda ts: ts[0] < 2.0, want_saves=["1"])
            sim("rst: chain skips failing alt", "rst",
                ["--proto=tls", "--auto=torst", "--proto=tls", "--disorder", "2",
                 "--auto=torst", "--proto=tls", "--split", "0+hm"], True, want_saves=["1", "2"])
            sim("rst: static group ends chain", "rst",
                ["--proto=tls", "--auto=none", "--proto=tls", "--split", "0+hm"], False)
            sim("rst: full layout recovers", "rst", FULL_LAYOUT, True, want_saves=True)
            # Akilli mod: dogrudan grup RST alir, secili yontem ve ISS yedekleri ayni baglantida
            # seffaf tekrar ile denenir; ikinci istek onbellekten dogrudan calisan gruba gider.
            sim("rst: smart layout recovers + cache", "rst", SMART_LAYOUT, True, curls=2,
                check_times=lambda ts: ts[1] < ts[0], want_saves=True)

            # ------------------------------------------------------ uzun omurlu baglantilar
            def host_rule(port, action, add):
                op = "-I" if add else "-D"
                adb.sh(f"iptables -w {op} OUTPUT -m owner --uid-owner 2000 -p tcp -d 10.0.2.2 "
                       f"--dport {port} -j {action}")

            # DPI-2: --timeout'un TCP_USER_TIMEOUT'u sunucu once konustuysa kurulmamali. Istemci
            # verisi yollanirken hat takilir (DROP): istemci-once kontrolde 2 sn sonra torst
            # ("save:") beklenir, sunucu-once (SMTP benzeri) baglanti ise ayakta kalmali.
            life = ["--split", "1", "--auto=torst", "--split", "2", "--cache-ttl", "60", "--timeout", "2:0:0:1"]
            for case, port in (("client-first", HOST_ECHO_PORT), ("server-first", HOST_BANNER_PORT)):
                px = Proxy(adb, "life_" + case, 18094, BASE + life)
                procs.append(px)
                closed, detail = None, ""
                try:
                    s = socks_connect(18094, "10.0.2.2", port)
                    if port == HOST_BANNER_PORT:
                        s.settimeout(5)
                        detail = s.recv(64).decode(errors="replace").strip()
                    host_rule(port, "DROP", True)
                    s.sendall(b"stalled upload\r\n")
                    closed = recv_until_closed(s, 3.5)
                    s.close()
                except OSError as e:
                    detail = f"client error {e}"
                finally:
                    host_rule(port, "DROP", False)
                log = px.logtext()
                px.stop()
                fired = "save:" in log
                ok = fired if case == "client-first" else (closed is False and not fired)
                row("life", f"timeout with stall ({case})", "PASS" if ok else "FAIL",
                    f"{detail} torst={'yes' if fired else 'no'} closed={closed}")

            # DPI-5: yanit alinmis (2. tur) bir baglantinin ortasindaki RST onbellege yedek
            # yazmamali; sonraki baglanti yine birincil grupla (0) baslar.
            px = Proxy(adb, "life_rst", 18094, BASE + ["--split", "1", "--auto=torst", "--split", "2",
                                                       "--cache-ttl", "60"])
            procs.append(px)
            detail = ""
            try:
                s = socks_connect(18094, "10.0.2.2", HOST_ECHO_PORT)
                s.settimeout(5)
                for m in (b"a", b"b"):
                    s.sendall(m)
                    s.recv(16)
                host_rule(HOST_ECHO_PORT, "REJECT --reject-with tcp-reset", True)
                s.sendall(b"c")
                detail = f"closed={recv_until_closed(s, 3)}"
                s.close()
            except OSError as e:
                detail = f"client error {e}"
            finally:
                host_rule(HOST_ECHO_PORT, "REJECT --reject-with tcp-reset", False)
            try:
                s = socks_connect(18094, "10.0.2.2", HOST_ECHO_PORT)
                s.settimeout(5)
                s.sendall(b"d")
                s.recv(16)
                s.close()
                time.sleep(0.3)
            except OSError as e:
                detail += f" second conn error {e}"
            log = px.logtext()
            px.stop()
            groups = re.findall(r"desync TCP: group=(\d+)", log)
            ok = "save:" not in log and groups and set(groups) == {"0"}
            row("life", "mid-life RST keeps primary", "PASS" if ok else "FAIL",
                f"{detail}; groups {groups}; save={'save:' in log}")

        # ---------------------------------------------------------- UDP / redirect / drop
        px = Proxy(adb, "udp", 18090, BASE + UDP_TEST_ARGS, binary="ciadpi_asan",
                   env=f"LD_LIBRARY_PATH={DEV_DIR} ASAN_OPTIONS=abort_on_error=0:halt_on_error=1 ")
        procs.append(px)
        txt = adb.sh(f"cd {DEV_DIR} && " + adb.as_shell("./udp_socks_test 18090 18096 18095"), timeout=180)
        for line in txt.splitlines():
            m = re.match(r"(PASS|FAIL) ([^:\s]+)(?:: (.*))?", line)
            if m:
                row("udp", m.group(2), m.group(1), m.group(3) or "")
        alive = px.alive()
        asan = "AddressSanitizer" in px.logtext()
        row("udp", "ciadpi_asan alive, no ASan report", "PASS" if alive and not asan else "FAIL",
            "ASan report found" if asan else "")
        px.stop()

        # ---------------------------------------------------------- JNI (istege bagli)
        if a.apk:
            jni_smoke(adb, Path(a.apk), out, row)

        # ---------------------------------------------------------- restart_test
        iters = "10" if a.quick else "50"
        for binary, env in (("restart_test", ""), ("restart_test_asan", f"LD_LIBRARY_PATH={DEV_DIR} ")):
            txt = adb.sh(f"cd {DEV_DIR} && {env}" + adb.as_shell(f"./{binary} {iters} 18099 18097 18098 2>&1"),
                         timeout=600)
            m = re.search(r"RESULT restart_test: (\w+) \((\d+) passed, (\d+) failed\)", txt)
            base = re.search(r"baseline: (.*)", txt)
            last = re.search(r"after races: (.*)", txt)
            fails = [l for l in txt.splitlines() if l.startswith("FAIL")]
            asan = "AddressSanitizer" in txt
            st = m.group(1) if m else "FAIL"
            if binary.endswith("asan"):
                # ASan'in karantinasi/golge bellegi RSS/mmap olcumunu anlamsiz kilar; fd ve hata raporu esas
                fails = [l for l in fails if "rss grew" not in l and "mmap leak" not in l]
                st = "PASS" if m and not fails and not asan else "FAIL"
            row("restart", binary, st,
                (f"{m.group(2)} checks" if m else "no result") + f"; baseline {base.group(1) if base else '?'}"
                + f"; end {last.group(1) if last else '?'}" + ("; " + fails[0] if fails else "")
                + ("; ASan report!" if asan else ""))
    finally:
        hosts.close()
        for px in procs:
            try:
                px.stop()
            except Exception:
                pass
        if iptables_used:
            for ipt in ("iptables", "ip6tables"):
                adb.sh(f"{ipt} -w -D OUTPUT -m owner --uid-owner 2000 -p tcp --dport 443 -j gdpi_smoke 2>/dev/null; "
                       f"{ipt} -w -F gdpi_smoke 2>/dev/null; {ipt} -w -X gdpi_smoke 2>/dev/null")
        adb.sh(f"rm -f {DEV_DIR}/*.pcap {DEV_DIR}/*.pid {DEV_DIR}/run_*.sh {DEV_DIR}/cap_*.sh")

    print()
    print(f"{'group':7} {'check':36} {'result':9} detail")
    print("-" * 110)
    for g, n, s, d in rows:
        print(f"{g:7} {n:36} {s:9} {d}")
    bad = [r for r in rows if r[2] == "FAIL"]
    print("-" * 110)
    print(f"{len(rows)} checks, {len(bad)} FAIL, "
          f"{sum(r[2] == 'EXPECTED' for r in rows)} EXPECTED (fake on emulator)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
