/*
 * GoodbyeDPI Android - upstream main.c'yi kutuphane olarak derler.
 *
 * ndk-build'de tek bir dosyaya ozel -D vermek zahmetli; bu sarmalayici main'i
 * byedpi_main olarak yeniden adlandirip upstream dosyayi degistirmeden dahil
 * eder (bkz. PATCHES.md). Kutuphane davranisi -DBYEDPI_LIB ile gelir.
 */
#define main byedpi_main
#include "../byedpi/main.c"
