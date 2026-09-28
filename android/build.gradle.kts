// Surumler gradle/libs.versions.toml icinde; burada yalnizca eklentiler tek yerden cozulsun diye tanimlanir.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Gelistirme dugmesi: -Pgdpi.buildDir=C:\t\b tum derleme ciktilarini kisa bir klasore tasir.
// Iki ise yarar: ndk-build nesne yollari Windows'un 260 karakter sinirindan uzaklasir ve ayni
// kaynak agacinda ayni anda calisan derlemeler birbirinin ciktisini ezmez.
providers.gradleProperty("gdpi.buildDir").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
    val base = file(dir)
    allprojects {
        layout.buildDirectory.set(base.resolve(if (this == rootProject) "root" else name))
    }
}
