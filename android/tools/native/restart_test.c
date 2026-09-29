/*
 * GoodbyeDPI Android - byedpi kutuphane modu baslat/durdur testi (cihazda calisir).
 *
 * JNI'siz: byedpi-jni/byedpi_lib.c'deki ayni durum makinesini dogrudan cagirir.
 * Uygulama sureci proxy'yi defalarca baslatip durduracagi icin kontrol edilenler:
 *   - her calisma gercekten istek tasiyor (TCP CONNECT, UDP ASSOCIATE, --redirect
 *     etiketleme, fake/disorder yolu) ve durdurunca temiz (0) donuyor;
 *   - onceki calismanin argumanlari sonrakine sizmiyor (params geri yukleme);
 *   - fd sayisi, mmap sayisi ve RSS dongu boyunca duz kaliyor;
 *   - yarislar: baslatma surerken durdurma, cift durdurma, eszamanli durdurma,
 *     mesgulken ikinci baslatma, hatali arguman, port dolu, dogal cikis sonrasi durdurma.
 *
 * Kullanim: restart_test [iterasyon=50] [proxy_port=18099] [tcp_echo=18097] [udp_echo=18098]
 * Cikis kodu 0 = hepsi gecti. Yalnizca 18080-18099 portlarini kullanir.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include "byedpi_lib.h"

static int g_port = 18099, g_tcp_echo = 18097, g_udp_echo = 18098;
static int g_fail = 0, g_pass = 0;

#define CHECK(cond, ...) do { \
    if (cond) { g_pass++; } \
    else { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } \
} while (0)

static long now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

/* ------------------------------------------------------------ olcumler */

static int count_fds(void)
{
    DIR *d = opendir("/proc/self/fd");
    if (!d) return -1;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] != '.') n++;
    }
    closedir(d);
    return n - 1; /* opendir'in kendi fd'si */
}

static int count_maps(void)
{
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return -1;
    int n = 0, c;
    while ((c = fgetc(f)) != EOF) {
        if (c == '\n') n++;
    }
    fclose(f);
    return n;
}

static long rss_kb(void)
{
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "VmRSS:", 6)) {
            kb = strtol(line + 6, 0, 10);
            break;
        }
    }
    fclose(f);
    return kb;
}

/* ------------------------------------------------------------ yankı sunuculari */

static void *tcp_echo_thread(void *arg)
{
    (void)arg;
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(g_tcp_echo) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) || listen(s, 64)) {
        perror("tcp echo bind");
        exit(2);
    }
    for (;;) {
        int c = accept4(s, 0, 0, SOCK_CLOEXEC);
        if (c < 0) continue;
        struct timeval tv = { .tv_sec = 3 };
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        char buf[4096];
        ssize_t n;
        while ((n = recv(c, buf, sizeof(buf), 0)) > 0) {
            if (send(c, buf, n, MSG_NOSIGNAL) < 0) break;
        }
        close(c);
    }
    return 0;
}

static void *udp_echo_thread(void *arg)
{
    (void)arg;
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(g_udp_echo) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a))) {
        perror("udp echo bind");
        exit(2);
    }
    for (;;) {
        char buf[2048];
        struct sockaddr_storage from;
        socklen_t fl = sizeof(from);
        ssize_t n = recvfrom(s, buf, sizeof(buf), 0, (struct sockaddr *)&from, &fl);
        if (n >= 0) sendto(s, buf, n, 0, (struct sockaddr *)&from, fl);
    }
    return 0;
}

/* ------------------------------------------------------------ SOCKS5 istemci */

static int tcp_connect_local(int port, int timeout_ms)
{
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        close(s);
        return -1;
    }
    struct timeval tv = { .tv_sec = timeout_ms / 1000, .tv_usec = (timeout_ms % 1000) * 1000 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    return s;
}

static int recv_all(int s, void *buf, size_t n)
{
    size_t got = 0;
    while (got < n) {
        ssize_t r = recv(s, (char *)buf + got, n - got, 0);
        if (r <= 0) return -1;
        got += r;
    }
    return 0;
}

/* Selamlasma + istek; cevap kodunu dondurur (0 = basarili), -1 = protokol/baglanti hatasi.
 * bnd: istege gore cevaptaki bagli adres (UDP ASSOCIATE icin roleyi verir). */
static int socks5_request(int s, uint8_t cmd, uint32_t ip_be, uint16_t port, struct sockaddr_in *bnd)
{
    uint8_t hello[3] = { 5, 1, 0 }, hr[2];
    if (send(s, hello, 3, MSG_NOSIGNAL) != 3 || recv_all(s, hr, 2) || hr[0] != 5 || hr[1] != 0) {
        return -1;
    }
    uint8_t req[10] = { 5, cmd, 0, 1 };
    memcpy(req + 4, &ip_be, 4);
    memcpy(req + 8, &port, 2);
    if (send(s, req, 10, MSG_NOSIGNAL) != 10) return -1;
    uint8_t rep[10];
    if (recv_all(s, rep, 4) || rep[0] != 5) return -1;
    if (rep[3] == 1) {
        if (recv_all(s, rep + 4, 6)) return -1;
        if (bnd) {
            memset(bnd, 0, sizeof(*bnd));
            bnd->sin_family = AF_INET;
            memcpy(&bnd->sin_addr, rep + 4, 4);
            memcpy(&bnd->sin_port, rep + 8, 2);
        }
    }
    else if (rep[3] == 4) {
        uint8_t skip[18];
        if (recv_all(s, skip, 18)) return -1;
    }
    return rep[1];
}

static uint32_t ip4(const char *s)
{
    struct in_addr a;
    inet_pton(AF_INET, s, &a);
    return a.s_addr;
}

static int wait_ready(int timeout_ms)
{
    long end = now_ms() + timeout_ms;
    while (now_ms() < end) {
        int s = tcp_connect_local(g_port, 500);
        if (s >= 0) {
            close(s);
            return 0;
        }
        usleep(5000);
    }
    return -1;
}

static int port_is_closed(void)
{
    int s = tcp_connect_local(g_port, 500);
    if (s >= 0) {
        close(s);
        return 0;
    }
    return errno == ECONNREFUSED;
}

/* CONNECT dst -> yanki; len bayt gonder, len bayt geri bekle. */
static int tcp_roundtrip(uint32_t ip_be, uint16_t port_host, const void *data, size_t len)
{
    int s = tcp_connect_local(g_port, 3000);
    if (s < 0) return -100;
    int code = socks5_request(s, 1, ip_be, htons(port_host), 0);
    if (code != 0) {
        close(s);
        return code < 0 ? -101 : code;
    }
    if (send(s, data, len, MSG_NOSIGNAL) != (ssize_t)len) {
        close(s);
        return -102;
    }
    char buf[4096];
    int ok = recv_all(s, buf, len) == 0 ? 0 : -103;
    close(s);
    return ok;
}

/* UDP ASSOCIATE + dst'ye bir datagram; beklenen yankiyi ve cevap basligindaki
 * kaynak adresi dogrular. Donus: 0 basarili, >0 SOCKS hata kodu, <0 diger. */
static int udp_roundtrip(uint32_t dst_ip, uint16_t dst_port_host,
        uint32_t want_ip, uint16_t want_port_host)
{
    int c = tcp_connect_local(g_port, 3000);
    if (c < 0) return -200;
    struct sockaddr_in relay;
    int code = socks5_request(c, 3, 0, 0, &relay);
    if (code != 0) {
        close(c);
        return code < 0 ? -201 : code;
    }
    int u = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in me = { .sin_family = AF_INET };
    me.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(u, (struct sockaddr *)&me, sizeof(me));
    struct timeval tv = { .tv_sec = 2 };
    setsockopt(u, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    static const char payload[] = "gdpi-udp-probe";
    uint8_t pkt[64] = { 0, 0, 0, 1 };
    memcpy(pkt + 4, &dst_ip, 4);
    uint16_t p = htons(dst_port_host);
    memcpy(pkt + 8, &p, 2);
    memcpy(pkt + 10, payload, sizeof(payload));
    int ret = -202;
    if (sendto(u, pkt, 10 + sizeof(payload), 0, (struct sockaddr *)&relay, sizeof(relay)) < 0) {
        goto out;
    }
    /* --udp-fake varsa once sahte paketin yankisi gelir; gercek yukunu bekle */
    for (int i = 0; i < 8; i++) {
        uint8_t r[2048];
        ssize_t n = recv(u, r, sizeof(r), 0);
        if (n < 0) {
            ret = -203;
            break;
        }
        if (n < 10 || r[3] != 1) {
            ret = -204;
            break;
        }
        uint32_t sip;
        uint16_t sport;
        memcpy(&sip, r + 4, 4);
        memcpy(&sport, r + 8, 2);
        if (sip != want_ip || ntohs(sport) != want_port_host) {
            ret = -205;
            break;
        }
        if (n == 10 + (ssize_t)sizeof(payload) && !memcmp(r + 10, payload, sizeof(payload))) {
            ret = 0;
            break;
        }
    }
out:
    close(u);
    close(c);
    return ret;
}

/* ------------------------------------------------------------ calistirma */

struct run {
    pthread_t th;
    int argc;
    char *argv[96];
    int ret;
    volatile int done;
};

static void *run_thread(void *arg)
{
    struct run *r = arg;
    r->ret = byedpi_lib_start(r->argc, r->argv);
    __atomic_store_n(&r->done, 1, __ATOMIC_SEQ_CST);
    return 0;
}

static void run_init(struct run *r, ...)
{
    memset(r, 0, sizeof(*r));
    r->argv[r->argc++] = "ciadpi";
    va_list ap;
    va_start(ap, r);
    const char *a;
    while ((a = va_arg(ap, const char *))) {
        r->argv[r->argc++] = (char *)a;
    }
    va_end(ap);
    r->argv[r->argc] = 0;
}

static void run_add(struct run *r, const char *a)
{
    r->argv[r->argc++] = (char *)a;
    r->argv[r->argc] = 0;
}

static void run_start(struct run *r)
{
    pthread_create(&r->th, 0, run_thread, r);
}

static int run_join(struct run *r, int timeout_ms)
{
    long end = now_ms() + timeout_ms;
    while (!__atomic_load_n(&r->done, __ATOMIC_SEQ_CST)) {
        if (now_ms() > end) return -1;
        usleep(1000);
    }
    pthread_join(r->th, 0);
    return 0;
}

static char port_s[8], udp_redir_s[64], tcp_redir_s[64];

/* Zengin arguman seti: gruplar, sahte veri, hosts/ipset, redirect, drop, timeout, cache. */
static void args_full(struct run *r)
{
    run_init(r, "-i", "127.0.0.1", "-p", port_s, "-c", "256", "-b", "16384",
        "--redirect", udp_redir_s, "--redirect", tcp_redir_s,
        "--deny-net", "198.18.0.0/15", "--deny-net", "fd00:6764:7069::/48", "-N",
        "--drop-udp", "443", "--drop-udp", "50000-50010",
        "--proto=udp", "--pf=17900-18200", "--udp-fake", "1", "--ttl", "5",
        "--auto=none",
        "--proto=tls,http", "--disorder", "1", "--fake", "-1", "--ttl", "5",
        "--fake-data", ":\\x00\\x00\\x00\\x00", "--fake-sni", "www.w3.org",
        "--ipset", ":127.0.0.0/8 fd00::/8", "--cache-ttl", "60",
        "--auto=torst,ssl_err", "--proto=tls", "--split", "2", "--split", "0+sm", "--cache-ttl", "60",
        "--hosts", ":example.com example.org",
        "--auto=torst,ssl_err", "--proto=tls", "--tlsrec", "3+s", "--cache-ttl", "60",
        "--timeout", "3", (char *)0);
}

/* Minimal set: UDP kapali, redirect yok. Onceki setten sizinti olursa yakalanir. */
static void args_min(struct run *r)
{
    run_init(r, "-i", "127.0.0.1", "-p", port_s, "-U", (char *)0);
}

/* TLS ClientHello gibi gorunen yuk: grup 1'in disorder+fake yolunu calistirir. */
static size_t fake_chello(char *out)
{
    static const unsigned char hdr[] = { 0x16, 0x03, 0x01, 0x00, 0x60, 0x01, 0x00, 0x00, 0x5c, 0x03, 0x03 };
    memset(out, 'x', 101);
    memcpy(out, hdr, sizeof(hdr));
    return 101;
}

static int one_iteration(int i, int full)
{
    struct run r;
    if (full) args_full(&r); else args_min(&r);
    run_start(&r);
    int ready = wait_ready(3000);
    CHECK(ready == 0, "iter %d: proxy did not come up", i);

    int rc;
    /* Duz CONNECT yankiya */
    static const char ping[] = "ping-pong-through-byedpi";
    rc = tcp_roundtrip(ip4("127.0.0.1"), g_tcp_echo, ping, sizeof(ping));
    CHECK(rc == 0, "iter %d: tcp echo rc=%d", i, rc);

    /* TCP --redirect: 127.0.0.1:18096 kapali port, TO yankiya gider */
    rc = tcp_roundtrip(ip4("127.0.0.1"), 18096, ping, sizeof(ping));
    if (full) CHECK(rc == 0, "iter %d: tcp redirect rc=%d", i, rc);
    else CHECK(rc == 5 || rc == 1, "iter %d: redirect leaked into minimal run rc=%d", i, rc);

    if (full) {
        /* disorder + fake (vmsplice/mmap) yolu */
        char ch[128];
        size_t n = fake_chello(ch);
        rc = tcp_roundtrip(ip4("127.0.0.1"), g_tcp_echo, ch, n);
        CHECK(rc == 0, "iter %d: tls-like echo rc=%d", i, rc);

        /* --deny-net: sanal agdaki FROM olmayan hedef hemen 02 (izin yok) */
        rc = tcp_roundtrip(ip4("198.18.1.1"), 80, ping, sizeof(ping));
        CHECK(rc == 2, "iter %d: deny-net rc=%d", i, rc);

        /* UDP --redirect + etiket: 10.255.0.1:5353 -> yanki; baslik FROM olmali */
        rc = udp_roundtrip(ip4("10.255.0.1"), 5353, ip4("10.255.0.1"), 5353);
        CHECK(rc == 0, "iter %d: udp redirect rc=%d", i, rc);
        /* yonlendirmesiz UDP: baslik gercek kaynak */
        rc = udp_roundtrip(ip4("127.0.0.1"), g_udp_echo, ip4("127.0.0.1"), g_udp_echo);
        CHECK(rc == 0, "iter %d: udp direct rc=%d", i, rc);
    }
    else {
        /* -U: UDP ASSOCIATE reddedilmeli (07 komut desteklenmiyor) */
        rc = udp_roundtrip(ip4("127.0.0.1"), g_udp_echo, 0, 0);
        CHECK(rc == 7, "iter %d: -U not honoured rc=%d", i, rc);
    }
    int s = byedpi_lib_stop();
    CHECK(s == 0, "iter %d: stop=%d", i, s);
    CHECK(run_join(&r, 3000) == 0, "iter %d: start did not return after stop", i);
    CHECK(r.ret == BYEDPI_OK, "iter %d: start returned %d", i, r.ret);
    CHECK(port_is_closed(), "iter %d: port still open after stop", i);
    return 0;
}

/* ------------------------------------------------------------ yarislar */

static void *stopper(void *arg)
{
    int *res = arg;
    *res = byedpi_lib_stop();
    return 0;
}

static void races(void)
{
    struct run r;

    printf("race R1\n");
    /* R1: baslatma surerken durdurma (rastgele gecikme), iptal dongusu tarifi */
    int r1_bad = 0;
    for (int i = 0; i < 200; i++) {
        args_full(&r);
        run_start(&r);
        usleep(rand() % 3000);
        long end = now_ms() + 2000;
        while (!__atomic_load_n(&r.done, __ATOMIC_SEQ_CST) && now_ms() < end) {
            byedpi_lib_stop();
            usleep(1000);
        }
        if (run_join(&r, 1000) || r.ret != BYEDPI_OK || !port_is_closed()) {
            r1_bad++;
            printf("R1 iter %d: ret=%d done=%d\n", i, r.ret, r.done);
        }
    }
    CHECK(r1_bad == 0, "R1 stop-during-start: %d bad", r1_bad);

    printf("race R2\n");
    /* R2: cift durdurma; bitince durdurma -1 */
    args_full(&r);
    run_start(&r);
    CHECK(wait_ready(3000) == 0, "R2 not ready");
    int a = byedpi_lib_stop(), b = byedpi_lib_stop();
    CHECK(a == 0 && b == 0, "R2 double stop %d %d", a, b);
    CHECK(run_join(&r, 3000) == 0 && r.ret == BYEDPI_OK, "R2 ret %d", r.ret);
    CHECK(byedpi_lib_stop() == -1, "R2 stop after exit should be -1");

    printf("race R3\n");
    /* R3: 8 is parcacigindan ayni anda durdurma */
    args_full(&r);
    run_start(&r);
    CHECK(wait_ready(3000) == 0, "R3 not ready");
    pthread_t th[8];
    int res[8];
    for (int i = 0; i < 8; i++) pthread_create(&th[i], 0, stopper, &res[i]);
    for (int i = 0; i < 8; i++) pthread_join(th[i], 0);
    /* En az biri 0 almali; dongu bittikten sonra gelenler -1 (calismiyor) alir */
    int r3_zero = 0, r3_other = 0;
    for (int i = 0; i < 8; i++) {
        if (res[i] == 0) r3_zero++;
        else if (res[i] != -1) r3_other++;
    }
    CHECK(r3_zero >= 1 && r3_other == 0, "R3 concurrent stop: zero=%d other=%d", r3_zero, r3_other);
    CHECK(run_join(&r, 3000) == 0 && r.ret == BYEDPI_OK, "R3 ret %d", r.ret);

    printf("race R4\n");
    /* R4: calisirken ikinci baslatma mesgul */
    args_full(&r);
    run_start(&r);
    CHECK(wait_ready(3000) == 0, "R4 not ready");
    struct run r2;
    args_min(&r2);
    run_start(&r2);
    CHECK(run_join(&r2, 1000) == 0 && r2.ret == BYEDPI_ERR_BUSY, "R4 busy ret %d", r2.ret);
    CHECK(tcp_roundtrip(ip4("127.0.0.1"), g_tcp_echo, "x", 1) == 0, "R4 first instance broken");
    byedpi_lib_stop();
    CHECK(run_join(&r, 3000) == 0 && r.ret == BYEDPI_OK, "R4 ret %d", r.ret);

    printf("race R5\n");
    /* R5: hatali argumanlar hemen -2; ardindan durdurma -1 (dogal cikis sonrasi) */
    const char *bad[][4] = {
        { "--bogus", 0 },
        { "--ttl", "0", 0 },
        { "--redirect", "1.2.3.4=5.6.7.8:53", 0 },
        { "--redirect", "1.2.3.4:53", 0 },
        { "--redirect", "[fd00::1]:53=bad:1", 0 },
        { "--drop-udp", "70000", 0 },
        { "--drop-udp", "500-100", 0 },
        { "--pf", "0", 0 },
        { "--auto", "q", 0 },
        { "-p", 0 },
        { "--deny-net", "198.18.0.0", 0 },
        { "--deny-net", "198.18.0.0/33", 0 },
        { "--deny-net", "fd00::/129", 0 },
        { "--deny-net", "example.com/8", 0 },
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(*bad); i++) {
        run_init(&r, "-i", "127.0.0.1", "-p", port_s, (char *)0);
        for (int j = 0; bad[i][j]; j++) run_add(&r, bad[i][j]);
        long t0 = now_ms();
        run_start(&r);
        CHECK(run_join(&r, 2000) == 0 && r.ret == BYEDPI_ERR_ARGS,
            "R5 bad args #%zu ret %d", i, r.ret);
        CHECK(now_ms() - t0 < 1000, "R5 bad args #%zu slow", i);
        CHECK(byedpi_lib_stop() == -1, "R5 stop after natural exit");
        CHECK(port_is_closed(), "R5 port open after bad args");
    }

    printf("race R6\n");
    /* R6: port dolu -> -1, sizinti yok */
    int blocker = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    /* byedpi'nin kapattigi baglantilar portta TIME_WAIT birakir; LISTEN cakismasi yine olur */
    int one = 1;
    setsockopt(blocker, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in ba = { .sin_family = AF_INET, .sin_port = htons(g_port) };
    ba.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int bok = bind(blocker, (struct sockaddr *)&ba, sizeof(ba)) == 0 && listen(blocker, 1) == 0;
    CHECK(bok, "R6 could not occupy port");
    args_min(&r);
    run_start(&r);
    int j6 = run_join(&r, 2000);
    CHECK(j6 == 0 && r.ret == BYEDPI_ERR_START, "R6 bind failure ret %d", r.ret);
    CHECK(byedpi_lib_stop() == -1, "R6 stop after failed start");
    if (j6) {
        byedpi_lib_stop();
        run_join(&r, 3000);
    }
    close(blocker);

    printf("race R7\n");
    /* R7: hemen baslat-durdur, hazir olmayi beklemeden (500 kez) */
    int r7_bad = 0;
    for (int i = 0; i < 500; i++) {
        args_min(&r);
        run_start(&r);
        while (byedpi_lib_stop() != 0 && !__atomic_load_n(&r.done, __ATOMIC_SEQ_CST)) {
            sched_yield();
        }
        if (run_join(&r, 2000) || r.ret != BYEDPI_OK) {
            r7_bad++;
        }
    }
    CHECK(r7_bad == 0, "R7 rapid start/stop: %d bad", r7_bad);
    CHECK(port_is_closed(), "R7 port open at end");

    printf("race R8\n");
    /* R8: fd tablosu dolunca (EMFILE) accept hatasi proxy'yi kapatmamali. Upstream burada
     * pool->brk kuruyordu: uygulamada tum telefonun baglantisi kopar, motor bastan kurulurdu.
     * Istemci ve byedpi ayni surecte: dusuk bir RLIMIT_NOFILE ile baglanti acmaya devam et;
     * son baglanti kuyrukta kalir ve byedpi'nin accept'i EMFILE alir. */
    {
        args_min(&r);
        run_start(&r);
        CHECK(wait_ready(3000) == 0, "R8 not ready");
        struct rlimit old, lim;
        getrlimit(RLIMIT_NOFILE, &old);
        lim = old;
        lim.rlim_cur = count_fds() + 40;
        setrlimit(RLIMIT_NOFILE, &lim);
        int cs[64], cn = 0;
        struct sockaddr_in pa = { .sin_family = AF_INET, .sin_port = htons(g_port) };
        pa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        while (cn < 64) {
            int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (s < 0) break;
            if (connect(s, (struct sockaddr *)&pa, sizeof(pa)) < 0) {
                close(s);
                break;
            }
            cs[cn++] = s;
            usleep(2000);
        }
        /* byedpi kuyruktakini kabul etmeye calisip EMFILE alsin (100 ms'de bir yeniden) */
        usleep(400000);
        int alive = !__atomic_load_n(&r.done, __ATOMIC_SEQ_CST);
        for (int i = 0; i < cn; i++) close(cs[i]);
        setrlimit(RLIMIT_NOFILE, &old);
        CHECK(cn > 0 && cn < 64, "R8 fd table never filled (cn=%d)", cn);
        CHECK(alive, "R8 proxy exited on EMFILE (ret=%d)", r.ret);
        /* Dinleyici en gec ACCEPT_RETRY_MS sonra geri gelir */
        usleep(300000);
        int rc8 = tcp_roundtrip(ip4("127.0.0.1"), g_tcp_echo, "after-emfile", 12);
        CHECK(rc8 == 0, "R8 no service after fds were freed rc=%d", rc8);
        byedpi_lib_stop();
        CHECK(run_join(&r, 3000) == 0 && r.ret == BYEDPI_OK, "R8 ret %d", r.ret);
    }

    /* Son: normal calisma hala mumkun */
    one_iteration(-1, 1);
}

int main(int argc, char **argv)
{
    int iters = argc > 1 ? atoi(argv[1]) : 50;
    if (argc > 2) g_port = atoi(argv[2]);
    if (argc > 3) g_tcp_echo = atoi(argv[3]);
    if (argc > 4) g_udp_echo = atoi(argv[4]);
    setvbuf(stdout, 0, _IOLBF, 0);
    srand(time(0));
    signal(SIGPIPE, SIG_IGN);

    snprintf(port_s, sizeof(port_s), "%d", g_port);
    snprintf(udp_redir_s, sizeof(udp_redir_s), "10.255.0.1:5353=127.0.0.1:%d", g_udp_echo);
    snprintf(tcp_redir_s, sizeof(tcp_redir_s), "127.0.0.1:18096=127.0.0.1:%d", g_tcp_echo);

    pthread_t t1, t2;
    pthread_create(&t1, 0, tcp_echo_thread, 0);
    pthread_create(&t2, 0, udp_echo_thread, 0);
    usleep(100000);

    /* Isinma: ilk calisma kalici ayirmalari yapar (log, libc onbellekleri) */
    one_iteration(0, 1);
    one_iteration(0, 0);
    int fd0 = count_fds(), maps0 = count_maps();
    long rss0 = rss_kb();
    printf("baseline: fds=%d maps=%d rss=%ldkB\n", fd0, maps0, rss0);

    long t0 = now_ms();
    for (int i = 1; i <= iters; i++) {
        one_iteration(i, i % 2);
        if (i % 10 == 0) {
            printf("iter %3d: fds=%d maps=%d rss=%ldkB\n", i, count_fds(), count_maps(), rss_kb());
        }
    }
    printf("loop: %d start/request/stop cycles in %ld ms\n", iters, now_ms() - t0);
    int fd1 = count_fds(), maps1 = count_maps();
    long rss1 = rss_kb();
    CHECK(fd1 == fd0, "fd leak: %d -> %d", fd0, fd1);
    CHECK(maps1 <= maps0 + 2, "mmap leak: %d -> %d", maps0, maps1);
    CHECK(rss1 - rss0 < 1024, "rss grew %ld kB", rss1 - rss0);

    races();
    int fd2 = count_fds(), maps2 = count_maps();
    long rss2 = rss_kb();
    printf("after races: fds=%d maps=%d rss=%ldkB\n", fd2, maps2, rss2);
    CHECK(fd2 == fd0, "fd leak after races: %d -> %d", fd0, fd2);
    CHECK(maps2 <= maps0 + 2, "mmap leak after races: %d -> %d", maps0, maps2);
    CHECK(rss2 - rss0 < 2048, "rss grew %ld kB after races", rss2 - rss0);

    printf("RESULT restart_test: %s (%d passed, %d failed)\n",
        g_fail ? "FAIL" : "PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
