# Yerel kod (byedpi-jni, hev-jni) sinif ve metotlari adlariyla RegisterNatives/FindClass ile
# buluyor; R8 bunlari yeniden adlandirir ya da silerse kutuphane yuklenirken cokeriz.
-keep class io.github.unsalable.goodbyedpi.engine.NativeBridge { *; }
-keep class io.github.unsalable.goodbyedpi.engine.TProxy { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# kotlinx.serialization: kutuphanenin gomulu kurallari eklenti tarafindan uretilen serializer'lari
# zaten korur; ayar modellerimiz icin ek guvence (Companion.serializer() yansimayla da aranabiliyor).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.unsalable.goodbyedpi.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class io.github.unsalable.goodbyedpi.**
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hata raporlarinda satir numaralari okunabilsin; kaynak dosya adi gizli kalsin.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
