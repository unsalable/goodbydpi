/*
 * GoodbyeDPI Android - byedpi --redirect / --drop-udp / UDP ASSOCIATE testi (cihazda).
 *
 * SPEC 7 host'ta Python SOCKS5 UDP istemcisi istiyordu; ancak adb forward yalnizca TCP
 * tasir, proxy'nin UDP rolesi (127.0.0.1:rastgele port) host'tan erisilemez. Bu yuzden
 * istemci cihazda calisan C programi; smoke.py onu adb ile calistirip sonucu okur.
 *
 * Beklenen proxy argumanlari (smoke.py ayni satiri kullanir):
 *   ciadpi -i 127.0.0.1 -p <port>
 *     --redirect 198.18.0.53:53=77.88.8.8:1253
 *     --redirect [fd00:6764:7069::53]:53=127.0.0.1:<echo>
 *     --drop-udp 443 --drop-udp <drop_echo>
 *     --proto=udp --pf=17900-18200 --udp-fake 2 --auto=none --proto=tls --split 1
 *
 * Kullanim: udp_socks_test <proxy_port> [echo=18096] [drop_echo=18095]
 * Cikis kodu 0 = hepsi gecti.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static int g_proxy = 18090, g_echo = 18096, g_drop = 18095;
static int g_pass = 0, g_fail = 0;

static void result(int ok, const char *name, const char *fmt, ...)
{
    char msg[256] = "";
    if (fmt) {
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(msg, sizeof(msg), fmt, ap);
        va_end(ap);
    }
    printf("%s %s%s%s\n", ok ? "PASS" : "FAIL", name, *msg ? ": " : "", msg);
    fflush(stdout);
    if (ok) g_pass++; else g_fail++;
}

/* ------------------------------------------------------------ yanki sunuculari */

/* Yanki sunucusunun gordugu datagramlar: sahte UDP paketlerinin sayisi ve TTL'i */
#define LOG_MAX 64
static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;
static struct { int ttl; int len; } g_log[LOG_MAX];
static int g_log_n = 0;
static volatile int g_drop_hits = 0;

static int udp_bind_local(int port)
{
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a))) {
        fprintf(stderr, "bind udp %d: %s\n", port, strerror(errno));
        exit(2);
    }
    return s;
}

static void *udp_echo_thread(void *arg)
{
    int drop = (int)(intptr_t)arg;
    int s = udp_bind_local(drop ? g_drop : g_echo);
    int one = 1;
    setsockopt(s, IPPROTO_IP, IP_RECVTTL, &one, sizeof(one));
    for (;;) {
        char buf[2048], cbuf[256];
        struct sockaddr_storage from;
        struct iovec iov = { buf, sizeof(buf) };
        struct msghdr mh = {
            .msg_name = &from, .msg_namelen = sizeof(from),
            .msg_iov = &iov, .msg_iovlen = 1,
            .msg_control = cbuf, .msg_controllen = sizeof(cbuf)
        };
        ssize_t n = recvmsg(s, &mh, 0);
        if (n < 0) continue;
        if (drop) {
            g_drop_hits++;
        }
        else {
            int ttl = -1;
            for (struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c; c = CMSG_NXTHDR(&mh, c)) {
                if (c->cmsg_level == IPPROTO_IP && c->cmsg_type == IP_TTL) {
                    memcpy(&ttl, CMSG_DATA(c), sizeof(ttl));
                }
            }
            pthread_mutex_lock(&g_log_lock);
            if (g_log_n < LOG_MAX) {
                g_log[g_log_n].ttl = ttl;
                g_log[g_log_n].len = (int)n;
                g_log_n++;
            }
            pthread_mutex_unlock(&g_log_lock);
        }
        sendto(s, buf, n, 0, (struct sockaddr *)&from, mh.msg_namelen);
    }
    return 0;
}

static void *tcp_echo_thread(void *arg)
{
    (void)arg;
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(g_echo) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) || listen(s, 16)) {
        fprintf(stderr, "bind tcp %d: %s\n", g_echo, strerror(errno));
        exit(2);
    }
    for (;;) {
        int c = accept4(s, 0, 0, SOCK_CLOEXEC);
        if (c < 0) continue;
        struct timeval tv = { .tv_sec = 3 };
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        char buf[2048];
        ssize_t n;
        while ((n = recv(c, buf, sizeof(buf), 0)) > 0) {
            if (send(c, buf, n, MSG_NOSIGNAL) < 0) break;
        }
        close(c);
    }
    return 0;
}

/* ------------------------------------------------------------ SOCKS5 */

static int tcp_to_proxy(void)
{
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(g_proxy) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        close(s);
        return -1;
    }
    struct timeval tv = { .tv_sec = 5 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
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

/* SOCKS5 adresi: atyp 1 (4 bayt) ya da 4 (16 bayt) + port */
struct s5addr {
    uint8_t atyp;
    uint8_t ip[16];
    uint16_t port; /* ag sirasi */
};

static struct s5addr a4(const char *ip, int port)
{
    struct s5addr a = { .atyp = 1, .port = htons(port) };
    inet_pton(AF_INET, ip, a.ip);
    return a;
}

static struct s5addr a6(const char *ip, int port)
{
    struct s5addr a = { .atyp = 4, .port = htons(port) };
    inet_pton(AF_INET6, ip, a.ip);
    return a;
}

static size_t put_addr(uint8_t *out, const struct s5addr *a)
{
    size_t l = a->atyp == 1 ? 4 : 16;
    out[0] = a->atyp;
    memcpy(out + 1, a->ip, l);
    memcpy(out + 1 + l, &a->port, 2);
    return 3 + l;
}

static const char *addr_str(const struct s5addr *a, char *buf, size_t bl)
{
    char ip[64];
    inet_ntop(a->atyp == 1 ? AF_INET : AF_INET6, a->ip, ip, sizeof(ip));
    snprintf(buf, bl, a->atyp == 1 ? "%s:%d" : "[%s]:%d", ip, ntohs(a->port));
    return buf;
}

/* Selamlasma + istek; cevap kodu (0 basarili), -1 protokol hatasi. bnd'ye bagli adres. */
static int socks5(int s, uint8_t cmd, const struct s5addr *dst, struct sockaddr_in *bnd)
{
    uint8_t hello[3] = { 5, 1, 0 }, hr[2];
    if (send(s, hello, 3, MSG_NOSIGNAL) != 3 || recv_all(s, hr, 2) || hr[1] != 0) {
        return -1;
    }
    uint8_t req[32] = { 5, cmd, 0 };
    size_t l = 3 + put_addr(req + 3, dst);
    if (send(s, req, l, MSG_NOSIGNAL) != (ssize_t)l) return -1;
    uint8_t rep[32];
    if (recv_all(s, rep, 4)) return -1;
    size_t rest = rep[3] == 1 ? 6 : (rep[3] == 4 ? 18 : 0);
    if (!rest || recv_all(s, rep + 4, rest)) return -1;
    if (bnd && rep[3] == 1) {
        memset(bnd, 0, sizeof(*bnd));
        bnd->sin_family = AF_INET;
        memcpy(&bnd->sin_addr, rep + 4, 4);
        memcpy(&bnd->sin_port, rep + 8, 2);
    }
    return rep[1];
}

struct assoc {
    int ctl, udp;
    struct sockaddr_in relay;
};

static int assoc_open(struct assoc *as)
{
    as->ctl = tcp_to_proxy();
    if (as->ctl < 0) return -1;
    struct s5addr any = { .atyp = 1 };
    int code = socks5(as->ctl, 3, &any, &as->relay);
    if (code != 0) {
        close(as->ctl);
        return -1;
    }
    as->udp = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in me = { .sin_family = AF_INET };
    me.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(as->udp, (struct sockaddr *)&me, sizeof(me));
    return 0;
}

static void assoc_close(struct assoc *as)
{
    close(as->udp);
    close(as->ctl);
}

static int assoc_send(struct assoc *as, const struct s5addr *dst, const void *data, size_t n)
{
    uint8_t pkt[2048] = { 0, 0, 0 };
    size_t h = 3 + put_addr(pkt + 3, dst);
    memcpy(pkt + h, data, n);
    return sendto(as->udp, pkt, h + n, 0, (struct sockaddr *)&as->relay, sizeof(as->relay)) < 0 ? -1 : 0;
}

/* Bir cevap bekle; baslik adresini ve yuku ayirir. Donus: yuk uzunlugu, -1 zaman asimi. */
static int assoc_recv(struct assoc *as, struct s5addr *src, uint8_t *out, size_t outl, int timeout_ms)
{
    struct pollfd p = { as->udp, POLLIN, 0 };
    if (poll(&p, 1, timeout_ms) <= 0) return -1;
    uint8_t buf[2048];
    ssize_t n = recv(as->udp, buf, sizeof(buf), 0);
    if (n < 4) return -2;
    size_t l = buf[3] == 1 ? 4 : (buf[3] == 4 ? 16 : 0);
    if (!l || (size_t)n < 4 + l + 2) return -2;
    memset(src, 0, sizeof(*src));
    src->atyp = buf[3];
    memcpy(src->ip, buf + 4, l);
    memcpy(&src->port, buf + 4 + l, 2);
    size_t pl = n - (4 + l + 2);
    if (pl > outl) pl = outl;
    memcpy(out, buf + 4 + l + 2, pl);
    return (int)pl;
}

static int addr_eq(const struct s5addr *a, const struct s5addr *b)
{
    size_t l = a->atyp == 1 ? 4 : 16;
    return a->atyp == b->atyp && a->port == b->port && !memcmp(a->ip, b->ip, l);
}

/* ------------------------------------------------------------ DNS */

static size_t dns_query(uint8_t *q, uint16_t id)
{
    static const uint8_t body[] = {
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
        0x00, 0x01, 0x00, 0x01
    };
    q[0] = id >> 8;
    q[1] = id & 0xff;
    memcpy(q + 2, body, sizeof(body));
    return 2 + sizeof(body);
}

/* 0: gecerli cevap (ayni id, QR, rcode 0, en az 1 cevap) */
static int dns_check(const uint8_t *r, int n, uint16_t id, char *why, size_t wl)
{
    if (n < 12) { snprintf(why, wl, "short answer %d", n); return -1; }
    if (((r[0] << 8) | r[1]) != id) { snprintf(why, wl, "id mismatch"); return -1; }
    if (!(r[2] & 0x80)) { snprintf(why, wl, "not a response"); return -1; }
    if (r[3] & 0x0f) { snprintf(why, wl, "rcode %d", r[3] & 0x0f); return -1; }
    int an = (r[6] << 8) | r[7];
    if (an < 1) { snprintf(why, wl, "no answers"); return -1; }
    return 0;
}

/* Iliski uzerinden DNS sor; cevap basliginin want olmasini da dogrular. */
static int udp_dns(struct assoc *as, const struct s5addr *dst, const struct s5addr *want,
        char *why, size_t wl)
{
    uint8_t q[64], r[1500];
    uint16_t id = rand() & 0xffff;
    size_t ql = dns_query(q, id);
    for (int attempt = 0; attempt < 3; attempt++) {
        if (assoc_send(as, dst, q, ql)) { snprintf(why, wl, "send: %s", strerror(errno)); return -1; }
        struct s5addr src;
        int n = assoc_recv(as, &src, r, sizeof(r), 2500);
        if (n == -1) continue; /* UDP kaybi: tekrar dene */
        if (n < 0) { snprintf(why, wl, "bad udp header"); return -1; }
        if (!addr_eq(&src, want)) {
            char a[80], b[80];
            snprintf(why, wl, "reply labelled %s, want %s",
                addr_str(&src, a, sizeof(a)), addr_str(want, b, sizeof(b)));
            return -1;
        }
        return dns_check(r, n, id, why, wl);
    }
    snprintf(why, wl, "no reply (3 tries)");
    return -1;
}

/* ------------------------------------------------------------ testler */

static int proxy_alive(void)
{
    int s = tcp_to_proxy();
    if (s < 0) return 0;
    struct s5addr d = a4("127.0.0.1", g_echo);
    int code = socks5(s, 1, &d, 0);
    int ok = code == 0 && send(s, "ok", 2, MSG_NOSIGNAL) == 2;
    char b[2];
    ok = ok && recv_all(s, b, 2) == 0 && !memcmp(b, "ok", 2);
    close(s);
    return ok;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <proxy_port> [echo=18096] [drop_echo=18095]\n", argv[0]);
        return 2;
    }
    g_proxy = atoi(argv[1]);
    if (argc > 2) g_echo = atoi(argv[2]);
    if (argc > 3) g_drop = atoi(argv[3]);
    setvbuf(stdout, 0, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);
    srand(time(0) ^ getpid());

    pthread_t t;
    pthread_create(&t, 0, udp_echo_thread, (void *)0);
    pthread_create(&t, 0, udp_echo_thread, (void *)1);
    pthread_create(&t, 0, tcp_echo_thread, 0);
    usleep(100000);

    char why[256];
    struct s5addr vdns = a4("198.18.0.53", 53);
    struct s5addr vdns6 = a6("fd00:6764:7069::53", 53);
    struct s5addr echo = a4("127.0.0.1", g_echo);
    struct s5addr dropped = a4("127.0.0.1", g_drop);
    struct assoc A, B, C, D;

    /* T1: DNS 198.18.0.53:53 -> 77.88.8.8:1253, cevap 198.18.0.53:53 etiketli */
    if (assoc_open(&A)) {
        result(0, "udp_associate", "cannot open association");
        return 1;
    }
    result(udp_dns(&A, &vdns, &vdns, why, sizeof(why)) == 0, "udp_dns_redirect_v4", why);

    /* T7: ayni (DNS'e bagli) iliskide dusurulen porta datagram */
    assoc_send(&A, &dropped, "drop-me", 7);

    /* T2: hedef v4-mapped IPv6 yazilsa da kural eslesir, etiket duz IPv4 */
    struct s5addr mapped = a6("::ffff:198.18.0.53", 53);
    assoc_open(&B);
    result(udp_dns(&B, &mapped, &vdns, why, sizeof(why)) == 0, "udp_dns_redirect_v4mapped", why);
    assoc_close(&B);

    /* T3: IPv6 FROM -> 127.0.0.1 yanki (aileler arasi), etiket IPv6 FROM */
    {
        assoc_open(&B);
        static const char pl[] = "v6-redirect-probe";
        assoc_send(&B, &vdns6, pl, sizeof(pl));
        int ok = 0;
        why[0] = 0;
        for (int i = 0; i < 6; i++) {
            struct s5addr src;
            uint8_t r[256];
            int n = assoc_recv(&B, &src, r, sizeof(r), 2000);
            if (n < 0) { snprintf(why, sizeof(why), "no reply"); break; }
            if (!addr_eq(&src, &vdns6)) { snprintf(why, sizeof(why), "wrong label atyp=%d", src.atyp); break; }
            if (n == sizeof(pl) && !memcmp(r, pl, n)) { ok = 1; break; }
        }
        result(ok, "udp_redirect_v6_to_v4", why);
        assoc_close(&B);
    }

    /* T4: --udp-fake 2 + --pf=17900-18200 (18096 araliga dusmeli: byte-order duzeltmesi),
     * sahteler varsayilan TTL 8 ile, gercek datagram sistem TTL'i ile */
    {
        pthread_mutex_lock(&g_log_lock);
        g_log_n = 0;
        pthread_mutex_unlock(&g_log_lock);
        assoc_open(&B);
        static const char pl[] = "fake-ttl-probe";
        assoc_send(&B, &echo, pl, sizeof(pl));
        int got = 0;
        for (int i = 0; i < 6 && !got; i++) {
            struct s5addr src;
            uint8_t r[256];
            int n = assoc_recv(&B, &src, r, sizeof(r), 2000);
            if (n < 0) break;
            if (!addr_eq(&src, &echo)) break;
            if (n == sizeof(pl) && !memcmp(r, pl, n)) got = 1;
        }
        usleep(200000);
        pthread_mutex_lock(&g_log_lock);
        int fakes = 0, fake_ttl_ok = 1, real_ttl = -1;
        char seen[160] = "";
        for (int i = 0; i < g_log_n; i++) {
            char e[24];
            snprintf(e, sizeof(e), "%s%d/ttl%d", i ? "," : "", g_log[i].len, g_log[i].ttl);
            strncat(seen, e, sizeof(seen) - strlen(seen) - 1);
            if (g_log[i].len == (int)sizeof(pl)) real_ttl = g_log[i].ttl;
            else { fakes++; if (g_log[i].ttl != 8) fake_ttl_ok = 0; }
        }
        pthread_mutex_unlock(&g_log_lock);
        result(got && fakes == 2, "udp_fake_pf_range", "echo=%d fakes=%d seen=[%s]", got, fakes, seen);
        result(fakes == 2 && fake_ttl_ok && real_ttl > 8, "udp_fake_default_ttl8",
            "real ttl=%d seen=[%s]", real_ttl, seen);
        assoc_close(&B);
    }

    /* T5: ilk datagram dusurulen porta -> cevap yok, ayni iliski DNS icin calisir */
    assoc_open(&C);
    assoc_send(&C, &dropped, "first-dropped", 13);
    {
        struct s5addr src;
        uint8_t r[64];
        int n = assoc_recv(&C, &src, r, sizeof(r), 1500);
        result(n == -1, "udp_drop_first_datagram", n == -1 ? 0 : "got a reply");
    }
    result(udp_dns(&C, &vdns, &vdns, why, sizeof(why)) == 0, "udp_drop_then_dns_same_assoc", why);
    assoc_close(&C);

    /* T6: QUIC gibi 1.1.1.1:443 dusmeli; dusmeseydi iliski 1.1.1.1:443'e baglanir ve
     * sonraki DNS cevapsiz kalirdi. DNS cevabi = 443 datagrami iliskiyi baglamadi. */
    assoc_open(&D);
    {
        struct s5addr quic = a4("1.1.1.1", 443);
        uint8_t junk[1200];
        memset(junk, 0xc3, sizeof(junk));
        assoc_send(&D, &quic, junk, sizeof(junk));
        struct s5addr src;
        uint8_t r[64];
        int n = assoc_recv(&D, &src, r, sizeof(r), 1000);
        result(n == -1, "udp_drop_443_silent", n == -1 ? 0 : "got a reply");
        result(udp_dns(&D, &vdns, &vdns, why, sizeof(why)) == 0, "udp_drop_443_then_dns", why);
    }
    assoc_close(&D);

    /* T7 sonucu: dusurulen porta hic datagram ulasmamali */
    usleep(300000);
    result(g_drop_hits == 0, "udp_drop_port_never_reached", "drop echo got %d datagrams", g_drop_hits);
    assoc_close(&A);

    /* T11: yonlendirmesiz hedefte etiket gercek kaynak */
    assoc_open(&B);
    {
        static const char pl[] = "plain";
        assoc_send(&B, &echo, pl, sizeof(pl));
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            struct s5addr src;
            uint8_t r[256];
            int n = assoc_recv(&B, &src, r, sizeof(r), 2000);
            if (n < 0 || !addr_eq(&src, &echo)) break;
            if (n == sizeof(pl) && !memcmp(r, pl, n)) { ok = 1; break; }
        }
        result(ok, "udp_plain_label", 0);
    }
    assoc_close(&B);

    /* T8: TCP DNS, CONNECT 198.18.0.53:53 -> 77.88.8.8:1253 */
    {
        int s = tcp_to_proxy();
        int code = s < 0 ? -1 : socks5(s, 1, &vdns, 0);
        int ok = 0;
        why[0] = 0;
        if (code == 0) {
            uint8_t q[80], r[1500];
            uint16_t id = rand() & 0xffff;
            size_t ql = dns_query(q + 2, id);
            q[0] = ql >> 8;
            q[1] = ql & 0xff;
            uint8_t lb[2];
            if (send(s, q, ql + 2, MSG_NOSIGNAL) == (ssize_t)(ql + 2) && !recv_all(s, lb, 2)) {
                int rl = (lb[0] << 8) | lb[1];
                if (rl > 0 && rl <= (int)sizeof(r) && !recv_all(s, r, rl)) {
                    ok = dns_check(r, rl, id, why, sizeof(why)) == 0;
                }
                else snprintf(why, sizeof(why), "short tcp answer");
            }
            else snprintf(why, sizeof(why), "no tcp answer");
        }
        else snprintf(why, sizeof(why), "CONNECT reply %d", code);
        if (s >= 0) close(s);
        result(ok, "tcp_dns_redirect", why);
    }

    /* T9: TCP CONNECT IPv6 FROM -> 127.0.0.1 yanki */
    {
        int s = tcp_to_proxy();
        int code = s < 0 ? -1 : socks5(s, 1, &vdns6, 0);
        char b[8];
        int ok = code == 0 && send(s, "hello6", 6, MSG_NOSIGNAL) == 6
            && recv_all(s, b, 6) == 0 && !memcmp(b, "hello6", 6);
        if (s >= 0) close(s);
        result(ok, "tcp_redirect_v6_to_v4", "code=%d", code);
    }

    /* T12: --deny-net (sanal aglar) ve -N. Ozel DNS'in 853 yoklamasi gibi FROM olmayan
     * sanal hedefler hemen reddedilmeli (0x02), gercek aga cikmamali; FROM (T1/T8/T9)
     * yine calisir. Ad tipi (ATYP 3) istek -N ile 0x08 almali, getaddrinfo yok. */
    {
        struct s5addr dot = a4("198.18.0.53", 853);
        struct s5addr other6 = a6("fd00:6764:7069::99", 80);
        /* tun'un kuresel kapsamli adres blogu (HevConfig.TUN_IPV6): gercek hedef degil */
        struct s5addr tun6 = a6("2001:db8:6764:7069::1", 443);
        int codes[3];
        double ms[3];
        const struct s5addr *dsts[3] = { &dot, &other6, &tun6 };
        for (int i = 0; i < 3; i++) {
            struct timespec t0, t1;
            clock_gettime(CLOCK_MONOTONIC, &t0);
            int s = tcp_to_proxy();
            codes[i] = s < 0 ? -1 : socks5(s, 1, dsts[i], 0);
            clock_gettime(CLOCK_MONOTONIC, &t1);
            ms[i] = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
            if (s >= 0) close(s);
        }
        result(codes[0] == 2 && ms[0] < 500, "tcp_deny_virtual_v4",
            "198.18.0.53:853 reply=%d in %.1f ms", codes[0], ms[0]);
        result(codes[1] == 2 && ms[1] < 500, "tcp_deny_virtual_v6",
            "[fd00:6764:7069::99]:80 reply=%d in %.1f ms", codes[1], ms[1]);
        result(codes[2] == 2 && ms[2] < 500, "tcp_deny_tun_global_v6",
            "[2001:db8:6764:7069::1]:443 reply=%d in %.1f ms", codes[2], ms[2]);

        /* UDP: sanal hedefe datagram duser, iliski baglanmaz; ayni iliskide DNS calisir */
        struct assoc G;
        int go = assoc_open(&G) == 0;
        if (go) {
            assoc_send(&G, &dot, "dot-probe", 9);
            struct s5addr src;
            uint8_t r[64];
            int n = assoc_recv(&G, &src, r, sizeof(r), 1000);
            result(n == -1, "udp_deny_virtual_silent", n == -1 ? 0 : "got a reply");
            result(udp_dns(&G, &vdns, &vdns, why, sizeof(why)) == 0, "udp_deny_then_dns", why);
            assoc_close(&G);
        }
        else result(0, "udp_deny_virtual_silent", "assoc");

        /* ATYP 3 (alan adi) */
        int s = tcp_to_proxy();
        uint8_t hello[3] = { 5, 1, 0 }, hr[2], rep[10] = { 0 };
        uint8_t req[32] = { 5, 1, 0, 3, 11 };
        memcpy(req + 5, "example.com", 11);
        req[16] = 0;
        req[17] = 80;
        int got = s >= 0 && send(s, hello, 3, MSG_NOSIGNAL) == 3 && recv_all(s, hr, 2) == 0
            && send(s, req, 18, MSG_NOSIGNAL) == 18 && recv_all(s, rep, 10) == 0;
        if (s >= 0) close(s);
        result(got && rep[1] == 8, "no_domain_atyp3_refused", "got=%d code=%d", got, rep[1]);
    }

    /* T10: bozuk girdiler proxy'yi dusurmemeli */
    {
        /* bilinmeyen ATYP -> 08 */
        int s = tcp_to_proxy();
        uint8_t hello[3] = { 5, 1, 0 }, hr[2], rep[10] = { 0 };
        send(s, hello, 3, MSG_NOSIGNAL);
        recv_all(s, hr, 2);
        uint8_t bad[12] = { 5, 1, 0, 9, 1, 2, 3, 4, 0, 80, 0, 0 };
        send(s, bad, 10, MSG_NOSIGNAL);
        int got = recv_all(s, rep, 10) == 0;
        close(s);
        result(got && rep[1] == 8, "malformed_atyp_reply", "got=%d code=%d", got, rep[1]);

        /* yanlis selamlasma, kisa istek, rastgele cop */
        s = tcp_to_proxy();
        uint8_t g1[5] = { 5, 7, 0, 1, 2 };
        send(s, g1, 5, MSG_NOSIGNAL);
        close(s);
        s = tcp_to_proxy();
        send(s, hello, 3, MSG_NOSIGNAL);
        recv_all(s, hr, 2);
        send(s, "\x05\x01\x00", 3, MSG_NOSIGNAL);
        close(s);
        s = tcp_to_proxy();
        uint8_t junk[3000];
        for (size_t i = 0; i < sizeof(junk); i++) junk[i] = rand();
        junk[0] = 5;
        send(s, junk, sizeof(junk), MSG_NOSIGNAL);
        close(s);

        /* UDP: 1 baytlik, parcali (frag=1), bilinmeyen ATYP datagramlari */
        struct assoc E;
        if (!assoc_open(&E)) {
            sendto(E.udp, "\x00", 1, 0, (struct sockaddr *)&E.relay, sizeof(E.relay));
            usleep(50000);
            assoc_close(&E);
        }
        if (!assoc_open(&E)) {
            uint8_t fr[20] = { 0, 0, 1, 1, 127, 0, 0, 1, 0x46, 0xb0, 'x' };
            sendto(E.udp, fr, 11, 0, (struct sockaddr *)&E.relay, sizeof(E.relay));
            uint8_t ba[20] = { 0, 0, 0, 9, 127, 0, 0, 1, 0x46, 0xb0, 'x' };
            sendto(E.udp, ba, 11, 0, (struct sockaddr *)&E.relay, sizeof(E.relay));
            usleep(50000);
            assoc_close(&E);
        }
        usleep(100000);
        result(proxy_alive(), "malformed_input_proxy_alive", 0);
        struct assoc F;
        int fo = assoc_open(&F) == 0;
        result(fo && udp_dns(&F, &vdns, &vdns, why, sizeof(why)) == 0, "udp_after_malformed", fo ? why : "assoc");
        if (fo) assoc_close(&F);
    }

    printf("RESULT udp_socks_test: %s (%d passed, %d failed)\n",
        g_fail ? "FAIL" : "PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
