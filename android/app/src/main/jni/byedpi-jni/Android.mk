# GoodbyeDPI Android - libbyedpi.so (byedpi kutuphane modu + JNI).
#
# Ust duzey jni/Android.mk bu dosyayi include eder; tek basina da
# APP_BUILD_SCRIPT olarak verilebilir (bkz. android/docs/BYEDPI_NOTES.md).
# Test amacli calistirilabilirler (ciadpi, restart_test, udp_socks_test)
# burada degil android/tools/native/Android.mk'de: APK'ya asla girmesinler.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := byedpi

# Upstream'in Linux kaynaklari; win_service.c bilincli olarak yok.
# main.c dogrudan degil, main'i byedpi_main yapan sarmalayici ile derlenir.
LOCAL_SRC_FILES := \
    byedpi_jni.c \
    byedpi_lib.c \
    byedpi_main.c \
    ../byedpi/conev.c \
    ../byedpi/desync.c \
    ../byedpi/extend.c \
    ../byedpi/mpool.c \
    ../byedpi/packets.c \
    ../byedpi/proxy.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/../byedpi

# Upstream Makefile ile ayni dil/uyari ayarlari (-std=c99 -D_DEFAULT_SOURCE
# -Wall -Wextra -Wno-unused ...). Ortuk bildirim her zaman hata: bionic'te
# eksik bir prototip sessizce int donduren cagriya donusmesin. Kapatilan iki
# uyari upstream'e ait ve zararsiz: gcc'ye ozel nonstring niteligi, desync.c'de
# socklen_t/offsetof isaret karsilastirmasi.
# NDEBUG: upstream'deki assert()'ler debug derlemede abort() ile tum uygulama
# surecini oldururdu; release ile ayni davranis istiyoruz.
BYEDPI_WARN_CFLAGS := \
    -Wall -Wextra -Wno-unused -Wno-unused-parameter \
    -Wno-unknown-attributes -Wno-sign-compare \
    -Werror=implicit-function-declaration -Werror=int-conversion \
    -Werror=incompatible-pointer-types -Werror=return-type -Werror=format

LOCAL_CFLAGS := \
    -std=c99 -D_DEFAULT_SOURCE -DBYEDPI_LIB -DNDEBUG \
    -O2 -fvisibility=hidden \
    $(BYEDPI_WARN_CFLAGS)

LOCAL_LDLIBS := -llog

# Android 15+ 16 KB sayfa: NDK r28+ varsayilan olarak yapar; NDK degisirse
# sessizce 4 KB'ye donmesin diye acikca istiyoruz (llvm-readelf -l ile dogrulandi).
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--gc-sections

include $(BUILD_SHARED_LIBRARY)
