/*
 * GoodbyeDPI Android - NativeBridge JNI duman testi (cihazda, app_process ile).
 *
 * Uygulamanin kendi APK'sindeki (release ise R8'den gecmis) NativeBridge sinifini ve
 * libbyedpi.so'yu yukler; RegisterNatives'in sinif/metot adlariyla eslestigini,
 * baslat -> istek -> durdur dongusunu ve hata kodlarini gercek JNI uzerinden dogrular.
 * NativeBridge "internal" oldugu icin yansima ile cagrilir (bytecode'da public).
 *
 * smoke.py --apk <apk> bunu derler (javac + d8) ve calistirir:
 *   CLASSPATH=app.apk:jnismoke.dex app_process -Djava.library.path=<lib dizini> / gdpitest.JniSmoke <port>
 */
package gdpitest;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class JniSmoke {
    private static int pass = 0, fail = 0;

    private static void check(boolean ok, String name, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : ": " + detail));
        if (ok) pass++; else fail++;
    }

    private static Method start, stop;

    private static int callStart(String[] args) throws Exception {
        return (Integer) start.invoke(null, (Object) args);
    }

    private static int callStop() throws Exception {
        return (Integer) stop.invoke(null);
    }

    private static final class Runner extends Thread {
        final String[] args;
        volatile int ret = Integer.MIN_VALUE;

        Runner(String[] args) {
            this.args = args;
        }

        @Override
        public void run() {
            try {
                ret = callStart(args);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean waitPort(int port, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Exception e) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
        }
        return false;
    }

    /** SOCKS5 CONNECT example.com:80 (alan adi byedpi'de cozulur) + HTTP GET; durum satiri. */
    private static String httpViaSocks(int port) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(10000);
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();
            o.write(new byte[] {5, 1, 0});
            byte[] r = new byte[2];
            readFully(in, r);
            byte[] host = "example.com".getBytes("US-ASCII");
            byte[] req = new byte[7 + host.length];
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3; req[4] = (byte) host.length;
            System.arraycopy(host, 0, req, 5, host.length);
            req[5 + host.length] = 0; req[6 + host.length] = 80;
            o.write(req);
            byte[] rep = new byte[10];
            readFully(in, rep);
            if (rep[1] != 0) return "socks reply " + rep[1];
            o.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
            byte[] line = new byte[12];
            readFully(in, line);
            return new String(line, "US-ASCII");
        }
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new Exception("eof");
            got += n;
        }
    }

    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 18098;
        Class<?> c = Class.forName("io.github.unsalable.goodbyedpi.engine.NativeBridge");
        start = c.getMethod("byedpiStart", String[].class);
        stop = c.getMethod("byedpiStop");
        check(Modifier.isStatic(start.getModifiers()) && Modifier.isNative(start.getModifiers())
                && Modifier.isStatic(stop.getModifiers()) && Modifier.isNative(stop.getModifiers()),
            "static_native_methods", start + " / " + stop);

        // Sinif yuklenince JNI_OnLoad RegisterNatives yapti; bos durdurma -1 olmali.
        check(callStop() == -1, "stop_when_idle", "");

        String[] good = {"-i", "127.0.0.1", "-p", String.valueOf(port), "--proto=tls,http",
            "--split", "2", "--split", "0+hm", "--redirect", "198.18.0.53:53=77.88.8.8:1253",
            "--drop-udp", "443"};
        for (int i = 0; i < 5; i++) {
            Runner r = new Runner(good);
            r.start();
            boolean up = waitPort(port, 3000);
            String status = up ? httpViaSocks(port) : "not listening";
            int s = callStop();
            r.join(3000);
            check(up && status.startsWith("HTTP/1.1") && s == 0 && r.ret == 0 && !r.isAlive(),
                "cycle_" + i, "status=" + status.trim() + " stop=" + s + " start=" + r.ret);
        }

        Runner busy = new Runner(good);
        busy.start();
        waitPort(port, 3000);
        int second = callStart(good);
        check(second == -3, "busy_second_start", "ret=" + second);
        callStop();
        busy.join(3000);
        check(busy.ret == 0, "busy_first_clean", "ret=" + busy.ret);

        int bad = callStart(new String[] {"--no-such-option"});
        check(bad == -2, "bad_args", "ret=" + bad);
        int nul = callStart(new String[] {"-p", null});
        check(nul == -2, "null_element", "ret=" + nul);
        check(callStop() == -1, "stop_after_natural_exit", "");

        // Baslatma surerken durdurma: iptal dongusu (stop; join(50)) hemen bitirmeli
        long t0 = System.currentTimeMillis();
        Runner early = new Runner(good);
        early.start();
        while (early.isAlive() && System.currentTimeMillis() - t0 < 3000) {
            callStop();
            early.join(50);
        }
        check(!early.isAlive() && early.ret == 0, "stop_during_start",
            "ret=" + early.ret + " in " + (System.currentTimeMillis() - t0) + " ms");

        System.out.println("RESULT jni_smoke: " + (fail == 0 ? "PASS" : "FAIL")
            + " (" + pass + " passed, " + fail + " failed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
