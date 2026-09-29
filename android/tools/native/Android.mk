# GoodbyeDPI Android - byedpi duman testi araclari (APK'ya GIRMEZ).
#
# Gradle bu dosyayi hic gormez; smoke.py su sekilde derler (x86_64 emulator icin):
#   ndk-build NDK_PROJECT_PATH=null APP_BUILD_SCRIPT=android/tools/native/Android.mk
#             APP_ABI=x86_64 APP_PLATFORM=android-24 NDK_OUT=C:/t/... NDK_LIBS_OUT=C:/t/...
#
# Moduller:
#   ciadpi          upstream CLI + bizim yamalarimiz (kutuphane modu KAPALI) - curl testleri
#   ciadpi_asan     ayni CLI, AddressSanitizer ile (udp_socks_test'in bozuk girdileri icin)
#   restart_test    kutuphane modu (-DBYEDPI_LIB) + byedpi_lib.c; baslat/durdur/yaris/sizinti
#   restart_test_asan  ayni test, AddressSanitizer ile
#   udp_socks_test  cihaz ustu SOCKS5 UDP/redirect/drop/deny-net istemcisi
#   tun_latency     hev CLI + tun uzerinden oturum kurulum gecikmesi (tun_latency.sh, root)
#
# Test ikilileri assert()'leri ACIK tutar (NDEBUG yok): hata varsa burada patlasin.

LOCAL_PATH := $(call my-dir)

GDPI_BD := ../../app/src/main/jni/byedpi
GDPI_BJ := ../../app/src/main/jni/byedpi-jni

GDPI_CORE_SRC := \
    $(GDPI_BD)/conev.c \
    $(GDPI_BD)/desync.c \
    $(GDPI_BD)/extend.c \
    $(GDPI_BD)/mpool.c \
    $(GDPI_BD)/packets.c \
    $(GDPI_BD)/proxy.c

GDPI_CFLAGS := -std=c99 -D_DEFAULT_SOURCE -O2 -g \
    -Wall -Wextra -Wno-unused -Wno-unused-parameter \
    -Wno-unknown-attributes -Wno-sign-compare \
    -Werror=implicit-function-declaration -Werror=int-conversion \
    -Werror=incompatible-pointer-types -Werror=return-type -Werror=format

GDPI_ASAN_CFLAGS := -fsanitize=address -fno-omit-frame-pointer
GDPI_ASAN_LDFLAGS := -fsanitize=address

# ---------------------------------------------------------------- ciadpi
include $(CLEAR_VARS)
LOCAL_MODULE := ciadpi
LOCAL_SRC_FILES := $(GDPI_CORE_SRC) $(GDPI_BD)/main.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(GDPI_BD)
LOCAL_CFLAGS := $(GDPI_CFLAGS)
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := ciadpi_asan
LOCAL_SRC_FILES := $(GDPI_CORE_SRC) $(GDPI_BD)/main.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(GDPI_BD)
LOCAL_CFLAGS := $(GDPI_CFLAGS) $(GDPI_ASAN_CFLAGS)
LOCAL_LDFLAGS := $(GDPI_ASAN_LDFLAGS)
include $(BUILD_EXECUTABLE)

# ---------------------------------------------------------------- restart_test
GDPI_LIB_SRC := $(GDPI_CORE_SRC) $(GDPI_BJ)/byedpi_main.c $(GDPI_BJ)/byedpi_lib.c restart_test.c

include $(CLEAR_VARS)
LOCAL_MODULE := restart_test
LOCAL_SRC_FILES := $(GDPI_LIB_SRC)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(GDPI_BD) $(LOCAL_PATH)/$(GDPI_BJ)
LOCAL_CFLAGS := $(GDPI_CFLAGS) -DBYEDPI_LIB
LOCAL_LDLIBS := -llog
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := restart_test_asan
LOCAL_SRC_FILES := $(GDPI_LIB_SRC)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(GDPI_BD) $(LOCAL_PATH)/$(GDPI_BJ)
LOCAL_CFLAGS := $(GDPI_CFLAGS) -DBYEDPI_LIB $(GDPI_ASAN_CFLAGS)
LOCAL_LDFLAGS := $(GDPI_ASAN_LDFLAGS)
LOCAL_LDLIBS := -llog
include $(BUILD_EXECUTABLE)

# ---------------------------------------------------------------- udp_socks_test
include $(CLEAR_VARS)
LOCAL_MODULE := udp_socks_test
LOCAL_SRC_FILES := udp_socks_test.c
LOCAL_CFLAGS := -std=c99 -O2 -g -Wall -Wextra -Wno-unused-parameter \
    -Werror=implicit-function-declaration
include $(BUILD_EXECUTABLE)

# ---------------------------------------------------------------- tun_latency
# hev CLI + tun uzerinden oturum kurulum gecikmesi (tun_latency.sh ile, root).
include $(CLEAR_VARS)
LOCAL_MODULE := tun_latency
LOCAL_SRC_FILES := tun_latency.c
LOCAL_CFLAGS := -std=c99 -O2 -g -Wall -Wextra -Wno-unused-parameter \
    -Werror=implicit-function-declaration
include $(BUILD_EXECUTABLE)
