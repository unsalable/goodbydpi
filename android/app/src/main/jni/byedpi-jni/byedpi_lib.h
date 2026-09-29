/*
 * GoodbyeDPI Android - byedpi kutuphane modu cekirdegi (JVM'siz).
 *
 * JNI katmani (byedpi_jni.c) ve cihaz ustu restart_test ayni kodu kullanir;
 * boylece baslat/durdur yarislari JVM olmadan da test edilebiliyor.
 */
#ifndef GDPI_BYEDPI_LIB_H
#define GDPI_BYEDPI_LIB_H

/* byedpiStart donus kodlari; Kotlin tarafi (NativeBridge) ayni sayilari kullanir. */
#define BYEDPI_OK             0  /* durdurma istegiyle temiz cikis */
#define BYEDPI_ERR_START     -1  /* socket/bind/listen/epoll hatasi (orn. port dolu) */
#define BYEDPI_ERR_ARGS      -2  /* gecersiz ya da bilinmeyen arguman */
#define BYEDPI_ERR_BUSY      -3  /* bu surecte zaten bir byedpi calisiyor */
#define BYEDPI_ERR_EXITED    -4  /* durdurma istenmeden dongu bitti (orn. accept hatasi) */
#define BYEDPI_ERR_INTERNAL  -5  /* bellek / eventfd / JNI donusum hatasi */

/*
 * Proxy'yi calistirir ve durana kadar bloklar. argv[0] program adidir,
 * argv[argc] NULL olmalidir. Dizgeler cagri bitene kadar yasamali (byedpi
 * bazi optarg isaretcilerini calisma boyunca saklar).
 */
int byedpi_lib_start(int argc, char **argv);

/*
 * Herhangi bir thread'den cagrilabilir. Calisan (ya da baslamakta olan)
 * proxy'ye durma istegi gonderir ve hemen doner: 0. Hicbir sey calismiyorsa
 * -1. Durma zaten istendiyse yine 0 (tekrar yazmaz).
 */
int byedpi_lib_stop(void);

#endif
