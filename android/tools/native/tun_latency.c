/*
 * GoodbyeDPI Android - tun uzerinden oturum kurulum gecikmesi olcer (APK'ya GIRMEZ).
 *
 * hev her yeni TCP oturumu ve her UDP akisi icin byedpi'ye yeni bir SOCKS5 el sikismasi
 * yapar; bu aracin olctugu sure o el sikismanin maliyetini de icerir (HEV_NOTES 9,
 * "tun latency"). Her deneme yeni bir soket (yeni kaynak portu) acar: DNS'te her sorgu
 * yeni bir UDP iliskisi, TCP'de yeni bir baglanti demek.
 *
 *   tun_latency udp <ip> <port> <n>   n adet A sorgusu (example.com), her biri ayri soket
 *   tun_latency tcp <ip> <port> <n>   n adet baglan + "HEAD / HTTP/1.0" + ilk bayt
 *   tun_latency serve <port>          127.0.0.1'de UDP yankilayici + TCP cevaplayici
 *
 * Calistirici: tun_latency.sh (root, hev CLI + ciadpi).
 *
 * Cikti: her deneme icin ms, sonunda "median=<ms> ok=<k>/<n>".
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static int cmp(const void *a, const void *b)
{
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static void set_to(int fd, int ms)
{
    struct timeval tv = { .tv_sec = ms / 1000, .tv_usec = (ms % 1000) * 1000 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

/* example.com A sorgusu; kimlik her denemede farkli */
static int dns_query(unsigned char *q, int id)
{
    static const unsigned char tail[] = {
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
        0x00, 0x01, 0x00, 0x01,
    };
    q[0] = id >> 8;
    q[1] = id;
    memcpy(q + 2, tail, sizeof(tail));
    return 2 + sizeof(tail);
}

static double one_udp(const struct sockaddr_in *dst, int i)
{
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    set_to(fd, 3000);
    unsigned char q[64], r[512];
    int ql = dns_query(q, 0x4000 + i);
    double t0 = now_ms();
    double res = -1;
    if (connect(fd, (const struct sockaddr *)dst, sizeof(*dst)) == 0
            && send(fd, q, ql, 0) == ql) {
        ssize_t n = recv(fd, r, sizeof(r), 0);
        if (n > 12 && r[0] == q[0] && r[1] == q[1]) res = now_ms() - t0;
    }
    close(fd);
    return res;
}

static double one_tcp(const struct sockaddr_in *dst, double *conn_ms)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    set_to(fd, 5000);
    static const char req[] = "HEAD / HTTP/1.0\r\nHost: example.com\r\n\r\n";
    char r[64];
    double t0 = now_ms(), res = -1;
    if (connect(fd, (const struct sockaddr *)dst, sizeof(*dst)) == 0) {
        *conn_ms = now_ms() - t0;
        if (send(fd, req, sizeof(req) - 1, 0) == (ssize_t)sizeof(req) - 1
                && recv(fd, r, sizeof(r), 0) > 0) {
            res = now_ms() - t0;
        }
    }
    close(fd);
    return res;
}

/* 127.0.0.1:<port> uzerinde UDP yankilayici + TCP cevaplayici. Emulatorun slirp
 * NAT'i her disari gidiste yuzlerce ms oynayabiliyor; hedef cihazin kendisi olunca
 * (byedpi --redirect ile) olcum yalnizca hev + byedpi maliyetini gosterir. */
static int serve(int port)
{
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port) };
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int u = socket(AF_INET, SOCK_DGRAM, 0), t = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    setsockopt(t, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (u < 0 || t < 0 || bind(u, (struct sockaddr *)&a, sizeof(a))
            || bind(t, (struct sockaddr *)&a, sizeof(a)) || listen(t, 64)) {
        perror("serve");
        return 1;
    }
    if (fork() == 0) {
        char b[2048];
        struct sockaddr_in from;
        for (;;) {
            socklen_t fl = sizeof(from);
            ssize_t n = recvfrom(u, b, sizeof(b), 0, (struct sockaddr *)&from, &fl);
            if (n > 0) sendto(u, b, n, 0, (struct sockaddr *)&from, fl);
        }
    }
    for (;;) {
        int c = accept(t, 0, 0);
        if (c < 0) continue;
        char b[256];
        static const char rsp[] = "HTTP/1.0 200 OK\r\n\r\n";
        if (recv(c, b, sizeof(b), 0) > 0) send(c, rsp, sizeof(rsp) - 1, 0);
        close(c);
    }
}

int main(int argc, char **argv)
{
    if (argc == 3 && !strcmp(argv[1], "serve")) {
        return serve(atoi(argv[2]));
    }
    if (argc != 5) {
        fprintf(stderr, "usage: %s udp|tcp <ip> <port> <n>\n", argv[0]);
        return 2;
    }
    struct sockaddr_in dst = { .sin_family = AF_INET, .sin_port = htons(atoi(argv[3])) };
    if (inet_pton(AF_INET, argv[2], &dst.sin_addr) != 1) return 2;
    int n = atoi(argv[4]);
    if (n <= 0 || n > 200) return 2;
    int udp = !strcmp(argv[1], "udp");
    double v[200];
    int ok = 0;
    for (int i = 0; i < n; i++) {
        double c = 0, t0 = now_ms(), t = udp ? one_udp(&dst, i) : one_tcp(&dst, &c);
        if (t >= 0) {
            v[ok++] = t;
            if (udp) printf("%d: %.1f ms\n", i, t);
            else printf("%d: connect %.1f ms, first byte %.1f ms\n", i, c, t);
        } else {
            int e = errno;
            printf("%d: fail after %.1f ms (%s)\n", i, now_ms() - t0, strerror(e));
        }
        usleep(50000);
    }
    qsort(v, ok, sizeof(double), cmp);
    printf("RESULT %s median=%.1f ok=%d/%d\n", argv[1], ok ? v[ok / 2] : -1.0, ok, n);
    return ok ? 0 : 1;
}
