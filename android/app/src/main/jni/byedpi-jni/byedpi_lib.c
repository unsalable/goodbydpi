/*
 * GoodbyeDPI Android - byedpi baslat/durdur durum makinesi.
 *
 * byedpi global durumla (params, getopt, server_fd) calisir; ayni surecte tek
 * ornek olabilir. Durumlar:
 *   IDLE -> (start) RUNNING -> (stop) STOPPING -> (dongu biter) IDLE
 *   RUNNING -> (dongu kendiliginden biter) IDLE
 *
 * Durdurma, calisma basinda acilan bir eventfd'ye yazarak yapilir. Upstream
 * SIGINT isleyicisindeki shutdown(server_fd) yerine bu yol secildi: dongu
 * server_fd'yi kendi kapatir, baska thread'den gelen gec bir shutdown o numarayi
 * yeniden almis baska bir soketi (orn. hev'inkini) bozabilirdi. eventfd'yi
 * yalnizca biz, kilit altinda ve durum IDLE'a donerken kapatiyoruz.
 */
#include "byedpi_lib.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/eventfd.h>
#include <unistd.h>

/* ../byedpi/main.c, byedpi_main.c icinde main -> byedpi_main olarak derlenir */
int byedpi_main(int argc, char **argv);

/* ../byedpi/proxy.c bunlari cagirir (proxy.h, BYEDPI_LIB) */
int byedpi_lib_wake_fd(void);
int byedpi_lib_stop_pending(void);

enum { ST_IDLE, ST_RUNNING, ST_STOPPING };

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_state = ST_IDLE;
static int g_wake_fd = -1;


int byedpi_lib_wake_fd(void)
{
    /* Yalnizca calisan thread'den cagrilir; deger calisma boyunca sabit. */
    return g_wake_fd;
}


int byedpi_lib_stop_pending(void)
{
    pthread_mutex_lock(&g_lock);
    int pending = g_state == ST_STOPPING;
    pthread_mutex_unlock(&g_lock);
    return pending;
}


int byedpi_lib_start(int argc, char **argv)
{
    if (argc < 1 || !argv || argv[argc]) {
        return BYEDPI_ERR_ARGS;
    }
    pthread_mutex_lock(&g_lock);
    if (g_state != ST_IDLE) {
        pthread_mutex_unlock(&g_lock);
        return BYEDPI_ERR_BUSY;
    }
    /* Durdurma bu andan itibaren kabul edilir: arguman ayristirma sirasinda
     * gelse bile sayac kalir, dongu ilk turda gorur ya da run() portu hic acmaz. */
    int wfd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (wfd < 0) {
        pthread_mutex_unlock(&g_lock);
        return BYEDPI_ERR_INTERNAL;
    }
    g_wake_fd = wfd;
    g_state = ST_RUNNING;
    pthread_mutex_unlock(&g_lock);

    int ret = byedpi_main(argc, argv);

    pthread_mutex_lock(&g_lock);
    int stopped = g_state == ST_STOPPING;
    close(g_wake_fd);
    g_wake_fd = -1;
    g_state = ST_IDLE;
    pthread_mutex_unlock(&g_lock);

    /* main: parse hatasi -2, init/listen hatasi -1, aksi 0 (-h/-v dahil) */
    if (ret == -2) {
        return BYEDPI_ERR_ARGS;
    }
    if (ret < 0) {
        return BYEDPI_ERR_START;
    }
    return stopped ? BYEDPI_OK : BYEDPI_ERR_EXITED;
}


int byedpi_lib_stop(void)
{
    pthread_mutex_lock(&g_lock);
    if (g_state == ST_IDLE) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    if (g_state == ST_RUNNING) {
        uint64_t one = 1;
        ssize_t w;
        do {
            w = write(g_wake_fd, &one, sizeof(one));
        } while (w < 0 && errno == EINTR);
        g_state = ST_STOPPING;
    }
    pthread_mutex_unlock(&g_lock);
    return 0;
}
