# GoodbyeDPI Android - ust duzey ndk-build betigi.
#
# Iki bagimsiz yerel bilesen tek ndk-build calismasinda derlenir:
#   * hev-socks5-tunnel (libhev-socks5-tunnel.so): tun -> SOCKS5 (lwIP)
#   * byedpi-jni (libbyedpi.so): SOCKS5 proxy + desync
# Gradle (externalNativeBuild.ndkBuild) bu dosyayi, yanindaki Application.mk
# ile birlikte kullanir. Ayrintilar: android/docs/HEV_NOTES.md.

# Alt betikler LOCAL_PATH / TOP_PATH / SRCDIR gibi degiskenleri ezdigi icin
# kendi dizinimizi ayri bir degiskende tutuyoruz; her include sonrasi
# buradan geri yukluyoruz.
GDPI_JNI_DIR := $(call my-dir)

# hev'in build.mk'si surum kimligini "git -C src rev-parse" ile okumaya
# calisir; bizim depoda bu bizim commit'imizi verir (ya da git PATH'te yoksa
# hata basar). Vendorlanan upstream commit'ini sabitliyoruz (PATCHES.hev.md).
REV_ID := d9dca26

# Upstream'in kendi ndk-build betigi: yaml, lwip, hev-task-system statik
# kutuphaneleri + hev-socks5-tunnel paylasimli kutuphanesi (+ -bin CLI).
# JNI sinif adi (PKGNAME/CLSNAME) upstream'in onerdigi gibi Application.mk
# icindeki APP_CFLAGS ile verilir.
include $(GDPI_JNI_DIR)/hev-socks5-tunnel/Android.mk
LOCAL_PATH := $(GDPI_JNI_DIR)

# byedpi (libbyedpi.so). Kosulsuz dahil: AGP ndk-build yapilandirmasini .cxx altinda
# onbellege aliyor ve wildcard ile eklenen bir dosyayi fark etmiyordu (byedpi sonradan
# eklenince APK'ya girmemisti).
include $(GDPI_JNI_DIR)/byedpi-jni/Android.mk
LOCAL_PATH := $(GDPI_JNI_DIR)
