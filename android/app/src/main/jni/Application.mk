# GoodbyeDPI Android - ndk-build uygulama ayarlari (Android.mk ile ayni dizin).

APP_ABI := arm64-v8a armeabi-v7a x86_64 x86
# minSdk 24 ile ayni; hev ve byedpi API 24 uzerinde bir bionic cagrisina
# ihtiyac duymuyor (derleme bunu dogruluyor: eksik sembol linkte patlar).
APP_PLATFORM := android-24
APP_OPTIM := release
# Iki bilesen de saf C; C++ calisma zamani paketlemeye gerek yok.
APP_STL := none

# -O2: lwIP/hev icin -O3 kadar hizli, belirgin sekilde daha kucuk.
# PKGNAME/CLSNAME: hev-jni.c RegisterNatives'i bu sinifa yapar; Kotlin
# tarafindaki engine/TProxy.kt ile birebir ayni olmali (upstream README
# de APP_CFLAGS ile vermeyi onerir; byedpi bu isimleri kullanmaz).
APP_CFLAGS := -O2 \
    -DPKGNAME=io/github/unsalable/goodbyedpi/engine \
    -DCLSNAME=TProxy
# Kullanilmayan bolumler atilsin; NDK zaten -ffunction-sections ile derler.
APP_LDFLAGS := -Wl,--gc-sections

# Android 15+ 16 KB sayfa boyutu: NDK r28+ bunu zaten varsayilan yapar,
# yine de acikca istiyoruz ki NDK surumu degisirse sessizce 4 KB'ye donmesin.
# Dogrulama: llvm-readelf -l lib*.so -> tum LOAD segmentleri Align 0x4000.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Uygulama yalnizca iki paylasimli kutuphaneyi paketler. Upstream hev ayrica
# bir CLI (hev-socks5-tunnel-bin) tanimlar; her derlemede onu da 4 ABI icin
# derlememek icin modulleri sinirliyoruz. Duman testleri icin CLI araclari
# gerekiyorsa komut satirinda GDPI_NATIVE_TOOLS=1 verilir (tum moduller).
ifeq ($(GDPI_NATIVE_TOOLS),)
    APP_MODULES := hev-socks5-tunnel byedpi
endif
