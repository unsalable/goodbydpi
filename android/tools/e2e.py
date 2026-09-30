#!/usr/bin/env python3
"""
GoodbyeDPI Android uctan uca test (SPEC 7 "E2E on emulator").

Ana makineden calisir, adb ile tek bir cihazi/emulatoru surer. Her adim PASS/FAIL/SKIP ve
kanit satirlari yazar; herhangi bir FAIL varsa cikis kodu 1'dir.

Windows'ta `py -3` ile calistirin: Microsoft Store'daki `python` takma adi AppData\\Local'i
sanallastiriyor ve SDK'daki adb.exe'yi goremiyor.

Ornek:
  py -3 android/tools/e2e.py --serial emulator-5554 \
      --apk C:/t/b/app/outputs/apk/debug/GoodbyeDPI-Android-1.0.0-debug.apk \
      --probe-apk C:/t/b/probe/outputs/apk/debug/probe-debug.apk

  py -3 android/tools/e2e.py ... --only install,permissions,probe_baseline
  py -3 android/tools/e2e.py ... --list

Cihazin/emulatorun genel durumunu degistiren adimlar (Wi-Fi/ucak modu, doze, yeniden baslatma,
her zaman acik VPN, gece modu) yalnizca --allow-disruptive ile calisir: ayni emulatoru baska
isler de kullaniyor olabilir. Bitince eski ayarlar geri yuklenir.

--ipv4-only-underlying (adb root ister): adimlardan once alttaki aglarda (wlan0, eth0) IPv6'yi
sysctl disable_ipv6=1 ile kapatir, en sonda eski degerleri geri yazar. Turk mobil verisinin cogu
boyle (IPv6 yok); 1.0.1'de tun yine de IPv6 sunuyordu ve Chromium (Chrome, Google, YouTube)
Google/YouTube'a baglanamiyordu. chromium_web adimi bu durumda da gecmeli. reboot / always_on
adimlari sysctl'i sifirlar (sonrasi yine IPv6'li olur).

Varsayimlar (ui/runtime katmaniyla ortak sabitler asagida, tek yerde):
  * Guc dugmesinin content-description'i POWER_DESC_RE ile eslesir.
  * Hata ayiklama derlemesi (run-as calisir): ayar dosyasi files/settings.json.
  * QUIC dusurme testi icin ana makinede 127.0.0.1:443 ve :4443'te UDP yankilayici acilir;
    emulator bunlara 10.0.2.2 uzerinden ulasir (bu yuzden excludeLan=false yazilir).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

# ---------------------------------------------------------------- ortak sabitler (tek yer)

#: Ana ekrandaki guc dugmesi. UI katmani content-description'i "Bağlan" / "Bağlantıyı kes"
#: (ya da "Güç ...") olarak veriyor; biri degisirse yalnizca burasi guncellenir.
POWER_DESC_RE = re.compile(r"Güç|Bağlantıyı kes|Bağlan")
#: Yalnizca "baglan" durumundaki dugme (ensure_running acik baglantiyi kapatmasin).
CONNECT_DESC_RE = re.compile(r"^Bağlan$")
#: Ayarlar ekranina giden ust cubuk simgesi.
SETTINGS_DESC_RE = re.compile(r"Ayarlar")
#: Servis bileseni ve eylemleri (SPEC 3: START / STOP / RESTART). Runtime katmani farkli
#: adlandirirsa --service-action-prefix ile degistirilebilir.
SERVICE_CLASS = "io.github.unsalable.goodbyedpi.service.DpiVpnService"
TILE_CLASS = "io.github.unsalable.goodbyedpi.service.QuickTileService"
MAIN_ACTIVITY = "io.github.unsalable.goodbyedpi.MainActivity"
DEFAULT_ACTION_PREFIX = "io.github.unsalable.goodbyedpi.action."
PROBE_PKG = "io.github.unsalable.goodbyedpi.probe"
PROBE_ACTIVITY = PROBE_PKG + "/.ProbeActivity"

VIRTUAL_DNS = "198.18.0.53:53"
# Duz HTTP hedefi: neverssl.com bu agdan VPN'siz de ulasilamiyor (ana makinede curl zaman asimi),
# o yuzden Firefox'un portal denetim adresi kullaniliyor.
PROBE_URLS = ["https://example.com", "https://discord.com", "https://www.cloudflare.com",
              "http://detectportal.firefox.com/success.txt"]
PROBE_DNS = ["example.com", "discord.com", "roblox.com"]
#: chromium_web: WebView (Chromium ag yigini, Chrome/Cronet ile ayni adres secimi) ile tun
#: uzerinden yuklenen sayfalar. Ucu de cift yiginli (AAAA var): tun IPv6 sunar ama ag tasimazsa
#: Chromium IPv6'yi secip ERR_CONNECTION_RESET aliyordu (1.0.1).
WEB_URLS = ["https://www.google.com/search?q=test", "https://m.youtube.com", "https://www.wikipedia.org"]
#: --ipv4-only-underlying ile IPv6'si kapatilan arayuzler (emulatorde Wi-Fi ve hucresel).
V4ONLY_IFACES = ("wlan0", "eth0")
HOST_ECHO_PORTS = (443, 4443)  # 443: dusurulmeli, 4443: gecmeli (UDP yolunun calistigi kontrolu)

VPN_UP_TIMEOUT = 15.0
RESTART_DEADLINE = 10.0  # SPEC: kill -9 sonrasi <= 10 sn


# ---------------------------------------------------------------- adb yardimcilari

def default_adb() -> str:
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(env)
        if root:
            cand = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if cand.exists():
                return str(cand)
    local = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    if local.exists():
        return str(local)
    return shutil.which("adb") or "adb"


class Adb:
    def __init__(self, adb: str, serial: str, verbose: bool = False):
        self.adb = adb
        self.serial = serial
        self.verbose = verbose

    def run(self, *args: str, timeout: float = 60, check: bool = False, stdin: bytes | None = None,
            binary: bool = False):
        cmd = [self.adb, "-s", self.serial, *args]
        if self.verbose:
            print("    $ " + " ".join(cmd), flush=True)
        p = subprocess.run(cmd, input=stdin, capture_output=True, timeout=timeout)
        out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} -> {p.returncode}: {p.stderr.decode('utf-8', 'replace')[:400]}")
        return out if binary else out.replace("\r\n", "\n")

    def sh(self, command: str, timeout: float = 60, check: bool = False, stdin: bytes | None = None) -> str:
        return self.run("shell", command, timeout=timeout, check=check, stdin=stdin)

    def pidof(self, pkg: str) -> int | None:
        out = self.sh(f"pidof {pkg}").strip()
        try:
            return int(out.split()[0]) if out else None
        except ValueError:
            return None

    def wait_boot(self, timeout: float = 180) -> bool:
        self.run("wait-for-device", timeout=timeout)
        end = time.time() + timeout
        while time.time() < end:
            if self.sh("getprop sys.boot_completed").strip() == "1":
                return True
            time.sleep(2)
        return False


# ---------------------------------------------------------------- sonuc kaydi

@dataclass
class StepResult:
    name: str
    status: str = "PASS"  # PASS / FAIL / SKIP
    evidence: list[str] = field(default_factory=list)

    def ev(self, line: str) -> None:
        self.evidence.append(line)
        print(f"    {line}", flush=True)

    def fail(self, why: str) -> None:
        self.status = "FAIL"
        self.ev("FAIL: " + why)

    def skip(self, why: str) -> None:
        if self.status != "FAIL":
            self.status = "SKIP"
        self.ev("SKIP: " + why)

    def expect(self, cond: bool, what: str) -> bool:
        if cond:
            self.ev("ok: " + what)
        else:
            self.fail(what)
        return cond


class UdpEcho:
    """Ana makinede UDP yankilayici; QUIC/UDP 443 dusurme testinin karsi tarafi."""

    def __init__(self, ports=HOST_ECHO_PORTS):
        self.ports = ports
        self.socks: list[socket.socket] = []
        self.hits: dict[int, int] = {p: 0 for p in ports}
        self.ok = True

    def __enter__(self):
        for p in self.ports:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.bind(("127.0.0.1", p))
            except OSError:
                self.ok = False
                s.close()
                continue
            s.settimeout(0.5)
            self.socks.append(s)
            threading.Thread(target=self._serve, args=(s, p), daemon=True).start()
        return self

    def _serve(self, s: socket.socket, port: int):
        while True:
            try:
                d, a = s.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            self.hits[port] += 1
            try:
                s.sendto(d[:64], a)
            except OSError:
                pass

    def __exit__(self, *exc):
        for s in self.socks:
            s.close()


# ---------------------------------------------------------------- test govdesi

class E2E:
    def __init__(self, args):
        self.args = args
        self.adb = Adb(args.adb, args.serial, args.verbose)
        self.apk = args.apk
        self.pkg = args.package or self._package_of(args.apk)
        self.out = Path(args.out)
        self.out.mkdir(parents=True, exist_ok=True)
        self.action_prefix = args.service_action_prefix
        self.results: list[StepResult] = []

    # ---------- yardimcilar

    def _package_of(self, apk: str) -> str:
        sdk = Path(self.args.adb).resolve().parent.parent
        tools = sorted((sdk / "build-tools").glob("*/aapt*")) if (sdk / "build-tools").exists() else []
        for t in reversed(tools):
            if t.stem == "aapt":
                out = subprocess.run([str(t), "dump", "badging", apk], capture_output=True, text=True).stdout
                m = re.search(r"package: name='([^']+)'", out)
                if m:
                    return m.group(1)
        raise SystemExit("Paket adi APK'dan okunamadi; --package verin")

    def vpn_state(self) -> tuple[bool, str]:
        """tun0 var mi ve connectivity'de VPN agi bizim paketimize mi ait."""
        # Arayuz adi sabit degil: canli ayar degisimi tun'u eski acikken yeniden kuruyor (VPN agi
        # dusmesin diye), yeni arayuz tun1 olabiliyor. Adrese gore aranir.
        tun = self.adb.sh("ip -o addr show 2>/dev/null | grep 198.18.0.1")
        conn = self.adb.sh("dumpsys connectivity")
        # NetworkAgentInfo satiri: "... ni{VPN CONNECTED extra: VPN:<paket>} ..." (Wi-Fi satirlarinda
        # NOT_VPN yetenegi gecer, o yuzden duz "VPN" aramasi yetmez). Sahip bilgisi surumden surume
        # farkli yerde yaziliyor; satirda yoksa dokumun genelinde paket adi aranir.
        vpn_lines = [l.strip() for l in conn.splitlines() if "ni{VPN CONNECTED" in l]
        owner = any(self.pkg in l for l in vpn_lines) or (bool(vpn_lines) and self.pkg in conn)
        up = "198.18.0.1" in tun and bool(vpn_lines)
        m = re.search(r"ni\{VPN CONNECTED[^}]*\}", vpn_lines[0]) if vpn_lines else None
        return up and owner, (tun.strip().splitlines() or ["tun0 yok"])[0][:120] + " | " + (m.group(0) if m else "VPN agi yok")

    def tun_index(self) -> str | None:
        """198.18.0.1 adresli arayuzun sira numarasi (yeniden kurulan tun yeni numara alir)."""
        out = self.adb.sh("ip -o addr show 2>/dev/null | grep 198.18.0.1").strip()
        return out.split(":")[0].strip() if out else None

    def wait_vpn(self, want_up: bool, timeout: float = VPN_UP_TIMEOUT) -> tuple[bool, float, str]:
        t0 = time.time()
        detail = ""
        while time.time() - t0 < timeout:
            up, detail = self.vpn_state()
            if up == want_up:
                return True, time.time() - t0, detail
            time.sleep(0.5)
        return False, time.time() - t0, detail

    _priv_mode: str | None = None

    def priv_mode(self) -> str:
        """Uygulama verisine erisim yolu: 'run-as' (debug derleme), 'root' (adb root; release
        derleme de test edilebilsin) ya da 'none'."""
        if self._priv_mode is None:
            if "uid=" in self.adb.sh(f"run-as {self.pkg} id"):
                self._priv_mode = "run-as"
            elif self.is_root():
                self._priv_mode = "root"
            else:
                self._priv_mode = "none"
        return self._priv_mode

    def app_sh(self, command: str, stdin: bytes | None = None, check: bool = False) -> str:
        """Uygulamanin veri klasorunde (cwd = /data/data/<pkg>) komut calistirir."""
        mode = self.priv_mode()
        q = command.replace("'", "'\\''")
        if mode == "run-as":
            return self.adb.run("shell", f"run-as {self.pkg} sh -c '{q}'", stdin=stdin, check=check)
        if mode == "root":
            return self.adb.run("shell", f"sh -c 'cd /data/data/{self.pkg} && {q}'", stdin=stdin, check=check)
        return ""

    def read_settings(self) -> dict:
        txt = self.app_sh("cat files/settings.json")
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return {}

    def write_settings(self, changes: dict) -> None:
        """Uygulamayi durdurup settings.json'u birlestirerek yazar (debug: run-as, release: root)."""
        cur = self.read_settings()
        cur.update(changes)
        self.adb.sh(f"am force-stop {self.pkg}")
        data = json.dumps(cur, ensure_ascii=False, indent=2).encode("utf-8")
        if self.priv_mode() == "root":
            # root olarak yazilan dosya uygulamaya ait olmali (sahip + SELinux baglami).
            self.app_sh("mkdir -p files && cat > files/settings.json && o=$(stat -c %u:%g .) && "
                        "chown $o files files/settings.json && chmod 771 files && chmod 600 files/settings.json && "
                        "restorecon -R files", stdin=data, check=True)
        else:
            self.app_sh("mkdir -p files && cat > files/settings.json", stdin=data, check=True)

    def ui_dump(self) -> ET.Element | None:
        self.adb.sh("uiautomator dump /sdcard/gdpi_ui.xml", timeout=30)
        xml = self.adb.sh("cat /sdcard/gdpi_ui.xml")
        self.adb.sh("rm -f /sdcard/gdpi_ui.xml")
        try:
            return ET.fromstring(xml[xml.find("<"):])
        except ET.ParseError:
            return None

    def tap_desc(self, pattern: re.Pattern, r: StepResult) -> bool:
        root = self.ui_dump()
        if root is None:
            r.fail("uiautomator dokumu okunamadi")
            return False
        for node in root.iter("node"):
            desc = node.get("content-desc", "")
            if node.get("package") == self.pkg and pattern.search(desc):
                m = re.findall(r"\d+", node.get("bounds", ""))
                if len(m) == 4:
                    x = (int(m[0]) + int(m[2])) // 2
                    y = (int(m[1]) + int(m[3])) // 2
                    r.ev(f"dokunma: '{desc}' @ {x},{y}")
                    self.adb.sh(f"input tap {x} {y}")
                    return True
        r.fail(f"content-desc /{pattern.pattern}/ bulunamadi")
        return False

    def launch_main(self) -> None:
        self.adb.sh(f"am start -W -n {self.pkg}/{MAIN_ACTIVITY}", timeout=30)
        time.sleep(1.0)

    def service_intent(self, action: str) -> str:
        return self.adb.sh(f"am start-foreground-service -n {self.pkg}/{SERVICE_CLASS} -a {self.action_prefix}{action}")

    def is_root(self) -> bool:
        return self.adb.sh("id -u").strip() == "0"

    def run_probe(self, r: StepResult, tag: str, urls=None, dns=None, udp=None, quic=None,
                  parallel: int = 4, repeat: int = 1, timeout_ms: int = 10000, wait: float = 120,
                  tcp=None, web=None) -> dict | None:
        extras = ["--es", "tag", tag, "--ei", "parallel", str(parallel), "--ei", "repeat", str(repeat),
                  "--ei", "timeout", str(timeout_ms)]
        for key, val in (("urls", urls), ("dns", dns), ("udp", udp), ("quic", quic), ("tcp", tcp), ("web", web)):
            if val:
                # Tek tirnak: adb shell komutu cihazdaki sh'ta calisir, URL'deki '?' / '&' yorumlanmasin.
                extras += ["--es", key, "'" + ",".join(val) + "'"]
        self.adb.run("logcat", "-c")
        self.adb.run("shell", "am", "start", "-n", PROBE_ACTIVITY, *extras)
        end = time.time() + wait
        done = None
        lines: list[dict] = []
        while time.time() < end:
            log = self.adb.run("logcat", "-d", "-s", "GDPI_PROBE:*")
            done = None
            lines = []
            for l in log.splitlines():
                i = l.find("GDPI_PROBE: ")
                if i < 0:
                    continue
                body = l[i + len("GDPI_PROBE: "):]
                if body.startswith("DONE "):
                    try:
                        d = json.loads(body[5:])
                        if d.get("tag") == tag:
                            done = d
                    except json.JSONDecodeError:
                        pass
                elif body.startswith("{"):
                    try:
                        j = json.loads(body)
                        if j.get("tag") == tag:
                            lines.append(j)
                    except json.JSONDecodeError:
                        pass
            if done:
                break
            time.sleep(1)
        if not done:
            r.fail(f"probe '{tag}' {wait:.0f} sn icinde bitmedi")
            return None
        r.ev(f"probe {tag}: {json.dumps(done, ensure_ascii=False)}")
        (self.out / f"probe-{tag}.json").write_text(json.dumps({"summary": done, "results": lines}, indent=2, ensure_ascii=False), encoding="utf-8")
        done["results"] = lines
        return done

    def proc_sample(self) -> dict:
        pid = self.adb.pidof(self.pkg)
        if not pid:
            return {}
        status = self.app_sh(f"cat /proc/{pid}/status")
        rss = re.search(r"VmRSS:\s+(\d+)", status)
        threads = re.search(r"Threads:\s+(\d+)", status)
        fds = self.app_sh(f"ls /proc/{pid}/fd").split()
        top = self.adb.sh(f"top -b -n 1 -p {pid}")
        cpu = None
        for l in top.splitlines():
            parts = l.split()
            if parts and parts[0] == str(pid):
                # PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS
                try:
                    cpu = float(parts[8])
                except (IndexError, ValueError):
                    pass
        return {"pid": pid, "rss_kb": int(rss.group(1)) if rss else None, "fds": len(fds),
                "threads": int(threads.group(1)) if threads else None, "cpu": cpu}

    def cpu_percent(self, pid: int | None, secs: float = 10) -> float | None:
        """Surecin [secs] boyunca kullandigi CPU (tek cekirdek %), /proc/<pid>/stat utime+stime."""
        if not pid:
            return None

        def ticks() -> int | None:
            st = self.app_sh(f"cat /proc/{pid}/stat")
            parts = st[st.rfind(")") + 2:].split()
            try:
                return int(parts[11]) + int(parts[12])
            except (IndexError, ValueError):
                return None

        a = ticks()
        t0 = time.time()
        time.sleep(secs)
        b = ticks()
        if a is None or b is None:
            return None
        return round((b - a) / 100.0 / (time.time() - t0) * 100.0, 1)  # USER_HZ=100

    def ensure_running(self, r: StepResult) -> bool:
        up, _ = self.vpn_state()
        if up:
            return True
        self.launch_main()
        # Arayuz acilinca ServiceController.recoverIfNeeded (wantRunning=true) baglantiyi kendisi
        # geri getirebiliyor; o zaman dugme "Baglantiyi kes" olur ve koru dokunmak VPN'i kapatir.
        ok, secs, detail = self.wait_vpn(True, 4)
        if ok:
            r.ev(f"arayuz acilinca baglanti kendiliginden geldi ({secs:.1f} sn)")
            return True
        self.tap_desc(CONNECT_DESC_RE, r)
        ok, secs, detail = self.wait_vpn(True)
        return r.expect(ok, f"VPN acik ({secs:.1f} sn): {detail}")

    def disruptive(self, r: StepResult) -> bool:
        if not self.args.allow_disruptive:
            r.skip("cihaz genelini etkiler; --allow-disruptive ile calistirin")
            return False
        return True

    # ---------- adimlar

    def step_install(self, r: StepResult):
        out = self.adb.run("install", "-r", "-d", self.apk, timeout=180)
        r.expect("Success" in out, f"ana APK kuruldu ({self.pkg})")
        if self.args.probe_apk:
            out = self.adb.run("install", "-r", self.args.probe_apk, timeout=120)
            r.expect("Success" in out, "probe APK kuruldu")
        else:
            installed = PROBE_PKG in self.adb.sh(f"pm list packages {PROBE_PKG}")
            r.expect(installed, "probe zaten kurulu (--probe-apk verilmedi)")
        self._priv_mode = None
        r.expect(self.priv_mode() != "none", f"uygulama verisine erisim: {self.priv_mode()} (run-as ya da adb root)")

    def step_permissions(self, r: StepResult):
        self.adb.sh(f"appops set {self.pkg} ACTIVATE_VPN allow")
        self.adb.sh(f"pm grant {self.pkg} android.permission.POST_NOTIFICATIONS")
        self.adb.sh(f"appops set {self.pkg} REQUEST_INSTALL_PACKAGES allow")
        ops = self.adb.sh(f"appops get {self.pkg} ACTIVATE_VPN")
        r.expect("allow" in ops, f"ACTIVATE_VPN: {ops.strip()[:120]}")
        perm = self.adb.sh(f"dumpsys package {self.pkg} | grep POST_NOTIFICATIONS")
        r.expect("granted=true" in perm, "POST_NOTIFICATIONS verildi")

    def step_settings(self, r: StepResult):
        # Test icin: otomatik guncelleme kapali (GitHub'a gitmesin), yerel ag da tun'dan gecsin
        # (10.0.2.2'deki yankilayiciya giden UDP tun'a girmeli), DNS yonlendirmesi Yandex:1253.
        # Yontem: emulatorde sahte paket (TTL) yontemleri slirp yuzunden baglanti bozabilir
        # (SPEC "Emulator caveat"); tesisat testleri sahtesiz bir yontemle yapilir.
        self.write_settings({"autoUpdate": False, "excludeLan": False, "dns": "yandex",
                             "startOnBoot": True, "autoConnect": False, "method": self.args.method})
        s = self.read_settings()
        r.expect(s.get("excludeLan") is False and s.get("dns") == "yandex", "settings.json yazildi")

    def step_probe_baseline(self, r: StepResult):
        """VPN KAPALIYKEN kontrol olcumu: QUIC yankisi burada gelmeli (yoksa dusurme testi anlamsiz)."""
        up, detail = self.vpn_state()
        if up:
            r.skip("VPN acik; taban olcum VPN kapaliyken alinir")
            return
        with UdpEcho() as echo:
            if not echo.ok:
                r.skip("127.0.0.1:443/4443 UDP baglanamadi")
            d = self.run_probe(r, "baseline", urls=PROBE_URLS, dns=PROBE_DNS[:1],
                               udp=["77.88.8.8:1253"], quic=[f"10.0.2.2:{p}" for p in HOST_ECHO_PORTS],
                               timeout_ms=4000)
        if not d:
            return
        res = d["results"]
        r.expect(all(x["ok"] for x in res if x["kind"] == "http"), "HTTP istekleri basarili")
        r.expect(all(x["ok"] for x in res if x["kind"] == "dns"), "DNS cozumlemesi basarili")
        q = {x["target"]: x["ok"] for x in res if x["kind"] == "quic"}
        r.expect(all(q.values()) and q, f"VPN'siz UDP 443/4443 yankisi geliyor: {q}")

    def step_start_ui(self, r: StepResult):
        # wantRunning=true kalmissa arayuz acilinca kendisi baglanir (recoverIfNeeded) ve
        # dokunma baglantiyi keserdi; adim "kullanici dugmeye basti" yolunu sinamali.
        self.write_settings({"wantRunning": False})
        self.launch_main()
        if not self.tap_desc(CONNECT_DESC_RE, r):
            return
        ok, secs, detail = self.wait_vpn(True)
        r.expect(ok, f"arayuzden baglandi, VPN {secs:.1f} sn'de acik: {detail}")

    def step_vpn_up(self, r: StepResult):
        up, detail = self.vpn_state()
        r.expect(up, f"tun0 + VPN agi: {detail}")
        addr = self.adb.sh("ip -o addr show 2>/dev/null | grep 198.18.0.1")
        r.expect("198.18.0.1" in addr, "tun0 adresi 198.18.0.1")
        s = self.read_settings()
        r.expect(s.get("wantRunning") is True, "wantRunning=true kaydedildi")

    def step_probe(self, r: StepResult):
        if not self.ensure_running(r):
            return
        d = self.run_probe(r, "vpn", urls=PROBE_URLS, dns=PROBE_DNS, parallel=8)
        if not d:
            return
        res = d["results"]
        http = [x for x in res if x["kind"] == "http"]
        r.expect(all(x["ok"] for x in http), f"HTTP(S) tun uzerinden: {[(x['url'], x.get('code'), x['ms']) for x in http]}")
        dns = [x for x in res if x["kind"] == "dns"]
        r.expect(all(x["ok"] for x in dns), f"DNS tun uzerinden: {[(x['host'], x['ms']) for x in dns]}")

    def tun_v6_summary(self) -> str:
        """Tun arayuzundeki IPv6 adresleri (kapsamlariyla). 'yok' = tun IPv6 sunmuyor."""
        line = self.adb.sh("ip -o addr show 2>/dev/null | grep 198.18.0.1").strip()
        if not line:
            return "tun yok"
        name = line.split(":")[1].strip().split()[0]
        v6 = self.adb.sh(f"ip -o -6 addr show dev {name}").strip().splitlines()
        addrs = [" ".join(l.split()[2:6]) for l in v6]
        return f"{name}: " + ("; ".join(addrs) if addrs else "IPv6 yok")

    def underlying_v6_defaults(self) -> str:
        """Alttaki aglarin IPv6 varsayilan yollari (Android her aga ayri tablo kurar; tun ve
        'unreachable' satirlari haric)."""
        out = self.adb.sh("ip -6 route show table all 2>/dev/null | grep '^default'")
        lines = [" ".join(l.split()[:5]) for l in out.splitlines()
                 if l.strip() and "dev tun" not in l and "dev dummy" not in l]
        return " | ".join(lines) if lines else "yok"

    def step_chromium_web(self, r: StepResult):
        """WebView ile google arama, m.youtube.com ve wikipedia tun uzerinden: ana belgede ag hatasi
        (net::ERR_*) olmamali. HttpURLConnection'li probe IPv4'u one aldigi icin 1.0.1'deki IPv6
        hatasini goremedi; Chromium tun IPv6 sunuyorsa IPv6'yi secer. Google'in robot sayfasi (429)
        ag hatasi degildir, kanit satirinda httpStatus olarak gorunur."""
        if not self.ensure_running(r):
            return
        r.ev(f"tun IPv6: {self.tun_v6_summary()}")
        r.ev("alttaki IPv6 varsayilan yol(lar): " + self.underlying_v6_defaults())
        # Chromium'un baglanti havuzu ve DNS onbellegi onceki calismadan kalmasin.
        self.adb.sh(f"am force-stop {PROBE_PKG}")
        d = self.run_probe(r, "chromium", web=WEB_URLS, tcp=["www.google.com:443"], timeout_ms=30000, wait=200)
        if not d:
            return
        for x in d["results"]:
            if x.get("kind") == "tcp":
                fams = ["v6" if ":" in a else "v4" for a in x.get("order", [])]
                r.ev(f"bilgi: getAllByName(www.google.com) aileleri {fams} -> HttpURLConnection {x.get('family')}")
        web = [x for x in d["results"] if x.get("kind") == "web"]
        r.expect(len(web) == len(WEB_URLS), f"{len(web)}/{len(WEB_URLS)} sayfa sonucu geldi")
        for x in web:
            if x.get("ok"):
                extra = f" HTTP {x['httpStatus']}" if x.get("httpStatus") else ""
                r.expect(True, f"{x['url']}: yuklendi ({x.get('ms')} ms{extra}) '{x.get('title', '')[:50]}'")
            else:
                r.expect(False, f"{x['url']}: {x.get('error')} (kod {x.get('errorCode')}, {x.get('ms')} ms)")

    def step_dns_redirect(self, r: StepResult):
        if not self.ensure_running(r):
            return
        d = self.run_probe(r, "dnsredir", udp=[VIRTUAL_DNS], timeout_ms=5000)
        if not d:
            return
        u = [x for x in d["results"] if x["kind"] == "udp"]
        r.expect(bool(u) and u[0]["ok"] and u[0].get("answers", 0) > 0,
                 f"{VIRTUAL_DNS} (sanal DNS) yanit verdi -> byedpi --redirect calisiyor: {u}")

    def step_quic_drop(self, r: StepResult):
        if not self.ensure_running(r):
            return
        s = self.read_settings()
        if s.get("excludeLan", True):
            r.skip("excludeLan=true: 10.0.2.2 tun disinda kalir; once 'settings' adimini calistirin")
            return
        with UdpEcho() as echo:
            if not echo.ok:
                r.skip("127.0.0.1:443/4443 UDP baglanamadi")
                return
            d = self.run_probe(r, "quic", quic=[f"10.0.2.2:{p}" for p in HOST_ECHO_PORTS], timeout_ms=4000)
            hits = dict(echo.hits)
        if not d:
            return
        q = {x["target"]: x for x in d["results"] if x["kind"] == "quic"}
        r.expect(not q.get("10.0.2.2:443", {}).get("ok", True), f"UDP 443 dusuruldu (QUIC engeli): {q.get('10.0.2.2:443')}")
        r.expect(q.get("10.0.2.2:4443", {}).get("ok", False), f"UDP 4443 gecti (UDP yolu calisiyor): {q.get('10.0.2.2:4443')}")
        r.expect(hits.get(443, 0) == 0, f"ana makinedeki 443 yankilayicisina paket ulasmadi (hits={hits})")

    def step_stop_ui(self, r: StepResult):
        if not self.ensure_running(r):
            return
        self.launch_main()
        if not self.tap_desc(POWER_DESC_RE, r):
            return
        ok, secs, detail = self.wait_vpn(False)
        r.expect(ok, f"arayuzden durdu ({secs:.1f} sn): {detail}")

    def step_start_tile(self, r: StepResult):
        comp = f"{self.pkg}/{TILE_CLASS}"
        self.adb.sh(f"cmd statusbar add-tile {comp}")
        up, _ = self.vpn_state()
        self.adb.sh(f"cmd statusbar click-tile {comp}")
        ok, secs, detail = self.wait_vpn(not up)
        r.expect(ok, f"karo tiklamasi durumu cevirdi ({'acik->kapali' if up else 'kapali->acik'}, {secs:.1f} sn): {detail}")
        self.adb.sh(f"cmd statusbar click-tile {comp}")
        ok, secs, detail = self.wait_vpn(up)
        r.expect(ok, f"ikinci tiklama geri cevirdi ({secs:.1f} sn)")
        self.adb.sh("cmd statusbar collapse")

    def step_start_intent(self, r: StepResult):
        # Servis disa kapali (exported=false): shell ancak root ise dogrudan baslatabilir.
        if not self.is_root():
            r.skip("adb root degil; disa kapali servise shell'den Intent gonderilemez (karo adimi ayni yolu sinar)")
            return
        self.service_intent("STOP")
        self.wait_vpn(False)
        out = self.service_intent("START")
        ok, secs, detail = self.wait_vpn(True)
        r.expect(ok, f"START Intent'i ile acildi ({secs:.1f} sn): {out.strip()[:100]} | {detail}")

    def step_kill9(self, r: StepResult):
        if not self.ensure_running(r):
            return
        # Kurtarma arayuz acilmadan gelmeli: on plandaki aktivite surecle birlikte olurse AMS onu
        # yeniden baslatir ve onAppOpen kurtarirdi. Once ana ekrana donulur, QS paneli kapali.
        self.adb.sh("input keyevent KEYCODE_HOME")
        self.adb.sh("cmd statusbar collapse")
        time.sleep(3)
        # -9: SIGKILL; -11: yerel cokme (byedpi/hev segfault benzetimi, AMS 'Native crash').
        for sig in (9, 11):
            if not self.vpn_state()[0]:
                r.fail(f"kill -{sig} oncesi VPN kapali")
                return
            pid = self.adb.pidof(self.pkg)
            tun0 = self.tun_index()
            r.ev(f"surec {pid} olduruluyor (kill -{sig}), tun if{tun0}")
            self.app_sh(f"kill -{sig} {pid}")
            t0 = time.time()
            new_pid = None
            up = False
            while time.time() - t0 < RESTART_DEADLINE + 5:
                # SIGSEGV'de surec debuggerd dokumu bitene kadar (~0.5-1 sn) yasiyor ve eski tun
                # hala acik: "geri geldi" demek icin eski surec gitmis ve tun yenilenmis olmali.
                old_alive = self.adb.sh(f"ls -d /proc/{pid} 2>/dev/null").strip() != ""
                idx = self.tun_index()
                new_pid = self.adb.pidof(self.pkg)
                up, _ = self.vpn_state()
                if not old_alive and idx is not None and idx != tun0 and new_pid and new_pid != pid and up:
                    break
                up = False
                time.sleep(0.5)
            secs = time.time() - t0
            r.expect(bool(new_pid) and new_pid != pid and up and secs <= RESTART_DEADLINE,
                     f"kill -{sig}: yeni surec {new_pid}, VPN {secs:.1f} sn'de geri geldi (sinir {RESTART_DEADLINE:.0f} sn)")
            top = self.adb.sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
            r.expect(self.pkg + "/" not in top, f"kurtarma arayuz acilmadan oldu: {top.strip()[:160]}")
            if not up:
                # Bilgi: sistem geri getirmediyse arayuzu acmak (recoverIfNeeded) getiriyor mu?
                self.launch_main()
                ok, secs2, _ = self.wait_vpn(True, 8)
                r.ev(f"bilgi: arayuz acilinca kurtarma {'CALISTI' if ok else 'CALISMADI'} ({secs2:.1f} sn)")
                return
            time.sleep(3)

    def step_network_toggle(self, r: StepResult):
        if not self.disruptive(r) or not self.ensure_running(r):
            return
        try:
            self.adb.sh("svc wifi disable"); time.sleep(3)
            self.adb.sh("svc wifi enable"); time.sleep(6)
            self.adb.sh("cmd connectivity airplane-mode enable"); time.sleep(4)
            up_air, detail_air = self.vpn_state()
            r.ev(f"ucak modunda: {detail_air}")
        finally:
            self.adb.sh("cmd connectivity airplane-mode disable")
            self.adb.sh("svc wifi enable")
        time.sleep(8)
        ok, secs, detail = self.wait_vpn(True, 30)
        r.expect(ok, f"ag degisimlerinden sonra VPN acik: {detail}")
        pid = self.adb.pidof(self.pkg)
        r.expect(pid is not None, f"surec ayakta (pid {pid})")
        d = self.run_probe(r, "afternet", urls=PROBE_URLS[:2], dns=PROBE_DNS[:1])
        if d:
            r.expect(d["fail"] == 0, "ag geri gelince trafik calisiyor")

    def step_doze(self, r: StepResult):
        if not self.disruptive(r) or not self.ensure_running(r):
            return
        try:
            self.adb.sh("dumpsys battery unplug")
            out = self.adb.sh("dumpsys deviceidle force-idle")
            r.ev(out.strip()[:120])
            time.sleep(5)
            ok, _, detail = self.wait_vpn(True, 5)
            r.expect(ok, f"doze'da VPN acik: {detail}")
            d = self.run_probe(r, "doze", urls=PROBE_URLS[:2], dns=PROBE_DNS[:1])
            if d:
                r.expect(d["fail"] == 0, "doze'da trafik calisiyor")
        finally:
            self.adb.sh("dumpsys deviceidle unforce")
            self.adb.sh("dumpsys battery reset")

    def step_parallel200(self, r: StepResult):
        if not self.ensure_running(r):
            return
        pid = self.adb.pidof(self.pkg)
        d = self.run_probe(r, "p200", urls=PROBE_URLS[:3], parallel=200, repeat=67, timeout_ms=20000, wait=300)
        if not d:
            return
        total, ok = d["total"], d["ok"]
        r.expect(total >= 200, f"{total} istek, {ok} basarili")
        r.expect(ok >= total * 0.95, "en az %95 basari")
        r.expect(self.adb.pidof(self.pkg) == pid, f"motor sureci cokmedi (pid {pid})")
        up, detail = self.vpn_state()
        r.expect(up, f"VPN hala acik: {detail}")

    def step_soak(self, r: StepResult):
        if not self.ensure_running(r):
            return
        minutes = self.args.soak_minutes
        samples = []
        end = time.time() + minutes * 60
        i = 0
        while True:
            smp = self.proc_sample()
            samples.append(smp)
            r.ev(f"ornek {i}: {smp}")
            if time.time() >= end:
                break
            d = self.run_probe(r, f"soak{i}", urls=PROBE_URLS[:2], dns=PROBE_DNS[:1])
            if d and d["fail"]:
                r.fail(f"soak probe {i} basarisiz")
            i += 1
            time.sleep(max(0, min(30, end - time.time())))
        # Bosta CPU: uygulama arka planda (ana ekrandaki "bagli" halesi surekli cizim yapiyor,
        # o arayuz maliyeti; burada motorun bosta maliyeti olculur), probe yok, 10 sn'lik
        # /proc/<pid>/stat farki (top -n 1'in tek ornegi guvenilmez).
        self.adb.sh("input keyevent KEYCODE_HOME")
        time.sleep(10)
        idle = self.proc_sample()
        idle["cpu"] = self.cpu_percent(idle.get("pid"), 10)
        r.ev(f"bosta: {idle}")
        (self.out / "soak.json").write_text(json.dumps({"samples": samples, "idle": idle}, indent=2), encoding="utf-8")
        valid = [s for s in samples if s.get("rss_kb")]
        if len(valid) >= 2:
            first, last = valid[0], valid[-1]
            r.expect(last["pid"] == first["pid"], "surec soak boyunca ayni")
            growth = last["rss_kb"] - first["rss_kb"]
            r.expect(growth < max(15000, first["rss_kb"] * 0.2), f"RSS {first['rss_kb']} -> {last['rss_kb']} kB (+{growth})")
            r.expect(last["fds"] - first["fds"] < 20, f"FD {first['fds']} -> {last['fds']}")
        if idle.get("cpu") is not None:
            r.expect(idle["cpu"] < 3.0, f"bosta CPU %{idle['cpu']}")

    def step_reinstall(self, r: StepResult):
        if not self.ensure_running(r):
            return
        pid = self.adb.pidof(self.pkg)
        out = self.adb.run("install", "-r", "-d", self.apk, timeout=180)
        r.expect("Success" in out, "calisirken yeniden kuruldu")
        ok, secs, detail = self.wait_vpn(True, 30)
        new_pid = self.adb.pidof(self.pkg)
        r.expect(ok and new_pid and new_pid != pid,
                 f"MY_PACKAGE_REPLACED sonrasi VPN geri geldi ({secs:.1f} sn, pid {pid} -> {new_pid}): {detail}")

    def reboot_and_wait(self, r: StepResult) -> bool:
        """Yeniden baslatir; adbd onceden root idiyse yine root yapar (release derlemede ayar
        dosyasina erisim root'a bagli)."""
        was_root = self.is_root()
        self.adb.run("reboot", timeout=60)
        ok = self.adb.wait_boot(240)
        if ok and was_root and not self.is_root():
            self.adb.run("root", timeout=30)
            time.sleep(3)
            self.adb.run("wait-for-device", timeout=60)
        self._priv_mode = None
        return r.expect(ok, "cihaz acildi")

    def step_reboot(self, r: StepResult):
        if not self.disruptive(r) or not self.ensure_running(r):
            return
        s = self.read_settings()
        r.expect(s.get("startOnBoot") is True and s.get("wantRunning") is True, "startOnBoot ve wantRunning acik")
        if not self.reboot_and_wait(r):
            return
        ok, secs, detail = self.wait_vpn(True, 90)
        r.expect(ok, f"acilistan sonra VPN kendiliginden acildi ({secs:.1f} sn): {detail}")

    def step_always_on(self, r: StepResult):
        """Her zaman acik VPN. Vpn sinifi always_on_vpn_app ayarini yalnizca kullanici
        baslarken okuyor (calisirken 'settings put' etkisiz, kabukta bunun icin komut yok):
        ayar + yeniden baslatma. wantRunning=false yazilir ki acilista BootReceiver degil
        sistem baslatsin; logcat'te action=android.net.VpnService aranir. Sonra kilit (lockdown)
        modunda probe trafigi ve uygulamanin kendi uid'inin dogrudan erisimi denenir."""
        if not self.disruptive(r):
            return
        old_app = self.adb.sh("settings get secure always_on_vpn_app").strip()
        old_lock = self.adb.sh("settings get secure always_on_vpn_lockdown").strip()
        try:
            self.write_settings({"wantRunning": False})
            self.adb.sh("settings put secure always_on_vpn_lockdown 1")
            self.adb.sh(f"settings put secure always_on_vpn_app {self.pkg}")
            if not self.reboot_and_wait(r):
                return
            ok, secs, detail = self.wait_vpn(True, 60)
            r.expect(ok, f"her zaman acik VPN acilista servisi baslatti ({secs:.1f} sn): {detail}")
            log = self.adb.run("logcat", "-d", "-s", "GdpiVpnService:*")
            r.expect("action=android.net.VpnService" in log, "baslatan sistem (SERVICE_INTERFACE eylemi)")
            d = self.run_probe(r, "lockdown", urls=PROBE_URLS[:2], dns=PROBE_DNS[:1], udp=[VIRTUAL_DNS])
            if d:
                r.expect(d["fail"] == 0, "kilit modunda tun uzerinden trafik calisiyor")
            uid = self.adb.sh(f"stat -c %u /data/data/{self.pkg}").strip()
            if self.is_root() and uid.isdigit():
                out = self.adb.sh(f"su {uid} sh -c 'echo | nc -w 4 1.1.1.1 443 && echo OWN_OK'")
                r.expect("OWN_OK" in out, "kilit modunda uygulamanin kendi trafigi (byedpi, guncelleyici) muaf")
        finally:
            if old_app in ("", "null"):
                self.adb.sh("settings delete secure always_on_vpn_app")
            else:
                self.adb.sh(f"settings put secure always_on_vpn_app {old_app}")
            if old_lock in ("", "null"):
                self.adb.sh("settings delete secure always_on_vpn_lockdown")
            else:
                self.adb.sh(f"settings put secure always_on_vpn_lockdown {old_lock}")
            # Bellekteki her zaman acik durumu ancak yeniden baslatmayla temizlenir.
            self.reboot_and_wait(StepResult("always_on-restore"))

    def _screenshot(self, name: str) -> Path:
        png = self.adb.run("exec-out", "screencap", "-p", binary=True, timeout=30)
        p = self.out / f"{name}.png"
        p.write_bytes(png)
        return p

    def step_screenshots(self, r: StepResult):
        if not self.disruptive(r):
            return
        old = self.adb.sh("cmd uimode night").strip()
        try:
            for mode in ("no", "yes"):
                self.adb.sh(f"cmd uimode night {mode}")
                time.sleep(1.5)
                self.launch_main()
                label = "light" if mode == "no" else "dark"
                p = self._screenshot(f"main-{label}")
                r.expect(p.stat().st_size > 10000, f"{p.name} ({p.stat().st_size} bayt)")
                if self.tap_desc(SETTINGS_DESC_RE, r):
                    time.sleep(1.5)
                    p = self._screenshot(f"settings-{label}")
                    r.expect(p.stat().st_size > 10000, f"{p.name} ({p.stat().st_size} bayt)")
                    self.adb.sh("input keyevent KEYCODE_BACK")
        finally:
            self.adb.sh("cmd uimode night " + ("yes" if "yes" in old else "no"))

    def wait_settled(self, r: StepResult, max_load: float = 4.0, timeout: float = 240) -> None:
        """Acilistan hemen sonra (always_on/reboot adimlari) sistem yuku 15-20; o sirada olculen
        jank uygulamaya degil emulatore ait. 1 dk'lik yuk ortalamasi dusene kadar beklenir."""
        t0 = time.time()
        load = None
        while time.time() - t0 < timeout:
            try:
                load = float(self.adb.sh("cat /proc/loadavg").split()[0])
            except (IndexError, ValueError):
                break
            if load < max_load:
                break
            time.sleep(10)
        r.ev(f"sistem yuku {load} ({time.time() - t0:.0f} sn beklendi)")

    def step_gfxinfo(self, r: StepResult):
        self.wait_settled(r)
        self.launch_main()
        self.adb.sh(f"dumpsys gfxinfo {self.pkg} reset")
        size = self.adb.sh("wm size").strip().split()[-1]
        w, h = (int(v) for v in size.split("x"))
        # Senaryo: ayarlara gec, asagi-yukari kaydir, geri don.
        dummy = StepResult("gfx-nav")
        if self.tap_desc(SETTINGS_DESC_RE, dummy):
            time.sleep(1)
            for _ in range(3):
                self.adb.sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.25)} 250")
                time.sleep(0.4)
                self.adb.sh(f"input swipe {w // 2} {int(h * 0.25)} {w // 2} {int(h * 0.8)} 250")
                time.sleep(0.4)
            self.adb.sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
        info = self.adb.sh(f"dumpsys gfxinfo {self.pkg}")
        (self.out / "gfxinfo.txt").write_text(info, encoding="utf-8")
        total = re.search(r"Total frames rendered: (\d+)", info)
        janky = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", info)
        p90 = re.search(r"90th percentile: (\d+)ms", info)
        r.ev(f"kareler={total.group(1) if total else '?'} janky={janky.group(0) if janky else '?'} p90={p90.group(1) if p90 else '?'}ms")
        if not total or int(total.group(1)) < 10:
            r.skip("yeterli kare yok (ayarlar simgesi bulunamadi mi?)")
            return
        r.expect(janky is not None and float(janky.group(2)) < self.args.max_jank, f"janky oran < %{self.args.max_jank}")

    def step_stop(self, r: StepResult):
        up, _ = self.vpn_state()
        if not up:
            r.ev("zaten kapali")
            return
        self.launch_main()
        self.tap_desc(POWER_DESC_RE, r)
        ok, secs, detail = self.wait_vpn(False)
        r.expect(ok, f"kapatildi ({secs:.1f} sn)")

    # ---------- surucu

    STEPS: list[tuple[str, str]] = [
        ("install", "APK + probe kurulumu"),
        ("permissions", "ACTIVATE_VPN appop, POST_NOTIFICATIONS, bilinmeyen kaynak"),
        ("settings", "test ayarlarini yaz (run-as)"),
        ("probe_baseline", "VPN kapaliyken kontrol olcumu"),
        ("start_ui", "arayuzden guc dugmesiyle baglan"),
        ("vpn_up", "tun0 + dumpsys connectivity VPN"),
        ("probe", "TCP/HTTPS + DNS tun uzerinden"),
        ("chromium_web", "WebView (Chromium) ile google/youtube/wikipedia tun uzerinden"),
        ("dns_redirect", "UDP DNS -> 198.18.0.53 (byedpi --redirect)"),
        ("quic_drop", "UDP 443 dusurulur, 4443 gecer"),
        ("stop_ui", "arayuzden kapat"),
        ("start_tile", "hizli ayarlar karosuyla ac/kapat"),
        ("start_intent", "servis Intent'i (root gerekir)"),
        ("kill9", "kill -9 -> <= 10 sn'de geri"),
        ("network_toggle", "Wi-Fi / ucak modu (disruptive)"),
        ("doze", "deviceidle force-idle (disruptive)"),
        ("parallel200", "200 paralel istek"),
        ("soak", "N dakika soak: RSS/FD/CPU"),
        ("reinstall", "calisirken yeniden kur -> MY_PACKAGE_REPLACED"),
        ("reboot", "yeniden baslatma + startOnBoot (disruptive)"),
        ("always_on", "her zaman acik VPN (disruptive)"),
        ("screenshots", "acik/koyu ekran goruntuleri (disruptive: gece modu)"),
        ("gfxinfo", "dumpsys gfxinfo jank"),
        ("stop", "sonunda kapat"),
    ]

    def run(self, only: list[str] | None) -> int:
        names = [n for n, _ in self.STEPS]
        todo = only or names
        unknown = [n for n in todo if n not in names]
        if unknown:
            raise SystemExit(f"bilinmeyen adim(lar): {unknown}; --list ile bakin")
        print(f"Cihaz {self.args.serial}, paket {self.pkg}, cikti {self.out}", flush=True)
        saved_v6: dict[str, str] = {}
        if self.args.ipv4_only_underlying:
            r = StepResult("ipv4_only_underlying")
            print("\n== ipv4_only_underlying", flush=True)
            saved_v6 = self.disable_underlying_v6(r)
            self.results.append(r)
            if r.status == "FAIL":
                self.restore_underlying_v6(saved_v6)
                return self.summary()
        try:
            for name in [n for n in names if n in todo]:
                r = StepResult(name)
                print(f"\n== {name}", flush=True)
                t0 = time.time()
                try:
                    getattr(self, "step_" + name)(r)
                except Exception as e:  # adim dusse bile digerleri calissin
                    r.fail(f"istisna: {e!r}")
                print(f"[{r.status}] {name} ({time.time() - t0:.1f} sn)", flush=True)
                self.results.append(r)
        finally:
            # Ctrl+C ya da beklenmeyen hata olsa da emulator IPv6'siz kalmasin.
            if saved_v6:
                self.restore_underlying_v6(saved_v6)
        return self.summary()

    def disable_underlying_v6(self, r: StepResult) -> dict[str, str]:
        """IPv4-only alt ag benzetimi: V4ONLY_IFACES'te disable_ipv6=1. Eski degerleri dondurur."""
        if not self.is_root():
            r.fail("--ipv4-only-underlying icin adb root gerekli (adb root)")
            return {}
        saved: dict[str, str] = {}
        for iface in V4ONLY_IFACES:
            cur = self.adb.sh(f"cat /proc/sys/net/ipv6/conf/{iface}/disable_ipv6 2>/dev/null").strip()
            if cur not in ("0", "1"):
                r.ev(f"{iface} yok, atlandi")
                continue
            saved[iface] = cur
            self.adb.sh(f"sysctl -w net.ipv6.conf.{iface}.disable_ipv6=1")
        # LinkProperties guncellemesi (adreslerin ve varsayilan yolun dusmesi) birkac saniye surer.
        time.sleep(3)
        r.expect(bool(saved), f"IPv6 kapatildi: {sorted(saved)} (eski degerler {saved})")
        r.ev("kalan IPv6 varsayilan yol(lar): " + self.underlying_v6_defaults())
        return saved

    def restore_underlying_v6(self, saved: dict[str, str]) -> None:
        for iface, val in saved.items():
            self.adb.sh(f"sysctl -w net.ipv6.conf.{iface}.disable_ipv6={val}")
        print(f"\n== alttaki IPv6 geri yuklendi: {saved}", flush=True)

    def summary(self) -> int:
        print("\n==== OZET")
        for r in self.results:
            print(f"  {r.status:4}  {r.name}")
        (self.out / "e2e-summary.json").write_text(
            json.dumps([r.__dict__ for r in self.results], indent=2, ensure_ascii=False), encoding="utf-8")
        failed = [r.name for r in self.results if r.status == "FAIL"]
        print(f"\n{'FAIL: ' + ', '.join(failed) if failed else 'Tum adimlar PASS/SKIP'}")
        return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="GoodbyeDPI Android E2E (emulator)")
    ap.add_argument("--serial", required=False, default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--apk", help="ana uygulama APK'si (debug; run-as gerekir)")
    ap.add_argument("--probe-apk", help="probe APK'si (verilmezse kurulu olani kullanir)")
    ap.add_argument("--package", help="paket adi (varsayilan: APK'dan okunur)")
    ap.add_argument("--adb", default=default_adb())
    ap.add_argument("--out", default="e2e-out", help="ekran goruntusu / json klasoru")
    ap.add_argument("--only", help="virgulle adimlar, orn. install,probe")
    ap.add_argument("--list", action="store_true", help="adimlari listele")
    ap.add_argument("--allow-disruptive", action="store_true",
                    help="Wi-Fi/ucak modu, doze, reboot, always-on, gece modu adimlarina izin ver")
    ap.add_argument("--soak-minutes", type=float, default=10)
    ap.add_argument("--max-jank", type=float, default=10.0, help="izin verilen janky kare yuzdesi")
    ap.add_argument("--service-action-prefix", default=DEFAULT_ACTION_PREFIX)
    ap.add_argument("--ipv4-only-underlying", action="store_true",
                    help="adimlardan once wlan0/eth0'da IPv6'yi kapat (sysctl, adb root), sonunda geri yukle")
    ap.add_argument("--method", default="disorder",
                    help="settings adiminda yazilacak yontem (emulatorde sahtesiz: disorder/split2/tlsrec)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    if args.list:
        for n, d in E2E.STEPS:
            print(f"  {n:16} {d}")
        return 0
    if not args.apk:
        ap.error("--apk gerekli")
    only = [s.strip() for s in args.only.split(",") if s.strip()] if args.only else None
    return E2E(args).run(only)


if __name__ == "__main__":
    sys.exit(main())
