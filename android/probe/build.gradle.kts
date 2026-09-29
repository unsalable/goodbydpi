// Emulator testlerinde VPN'in ICINDEN istek atan yardimci uygulama. Ayri paket oldugu icin
// trafigi tun'a girer (ana uygulama VPN disinda tutuluyor). Dagitilmaz.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.unsalable.goodbyedpi.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.unsalable.goodbyedpi.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Yalnizca test araci; imzasiz surum APK'si kurulamaz, hata ayiklama anahtari yeter.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
