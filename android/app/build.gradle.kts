import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.impl.VariantOutputImpl
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = "1.0.2"
val appVersionCode = 3

// Dagitilan tek APK tum ABI'leri tasir. Gelistirirken tek ABI derlemek (ornek:
// -Pgdpi.abi=x86_64) yerel kodun derleme suresini dortte birine indiriyor.
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val devAbi = providers.gradleProperty("gdpi.abi").orNull?.trim()?.takeIf { it.isNotEmpty() }
if (devAbi != null && devAbi !in allAbis) {
    throw GradleException("gdpi.abi=$devAbi gecersiz; su degerlerden biri olmali: $allAbis")
}
val abis = devAbi?.let { listOf(it) } ?: allAbis

// Ayni emulatorde birden fazla gelistirme derlemesi yan yana kurulabilsin diye
// (ornek: -Pgdpi.appIdSuffix=.devu). Namespace ve sinif adlari degismez.
val devAppIdSuffix = providers.gradleProperty("gdpi.appIdSuffix").orNull?.trim()?.takeIf { it.isNotEmpty() }

// ndk-build nesne yollari kaynak agacinin derinligini tekrarlar (lwip gibi); depo derin bir
// klasordeyse Windows'un 260 karakter sinirini asiyor. Ara dosyalar bu yuzden kisa bir yerde
// tutulur; gerekirse -Pgdpi.cxxDir=C:\t\cxx ile daha da kisaltilabilir.
val cxxDir = providers.gradleProperty("gdpi.cxxDir").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { file(it) } ?: rootProject.file(".cxx")

// Surum imzasi git'e girmeyen keystore.properties'ten okunur. Dosya yoksa surum derlemesi
// hata vermek yerine hata ayiklama anahtariyla imzalanir: assembleRelease her makinede calissin.
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.unsalable.goodbyedpi"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.unsalable.goodbyedpi"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_REPO", "\"unsalable/goodbydpi\"")

        ndk {
            abiFilters.clear()
            abiFilters += abis
        }

        externalNativeBuild {
            ndkBuild {
                // APP_ABI ve APP_PLATFORM (minSdk'ten) AGP tarafindan komut satirindan verilir;
                // jni/Application.mk'yi de Android.mk'nin yanindaysa kendisi NDK_APPLICATION_MK
                // olarak gecirir. Burada yalnizca her durumda gecerli olmasi gerekenler var.
                arguments += listOf(
                    // Android 15+ 16 KB sayfa boyutlu cihazlar icin .so hizalamasi.
                    "APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true",
                    "-j${(Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)}",
                )
                abiFilters.clear()
                abiFilters += abis
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
            buildStagingDirectory = cxxDir
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                // storeFile android/ klasorune gore cozulur.
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (devAppIdSuffix != null) applicationIdSuffix = devAppIdSuffix
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Arayuz yalnizca Turkce; kutuphanelerin onlarca dil cevirisi APK'yi bosuna buyutuyordu.
        localeFilters += listOf("tr")
    }

    packaging {
        // .so dosyalari sikistirilmadan ve hizali durur: sistem onlari APK'dan dogrudan
        // eslestirir, kurulumda ayrica acilip diske yazilmaz.
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Yan yana calisan ajanlarin yarim kalan isleri yuzunden surum derlemesi dusmesin;
        // lint ayrica calistirilip raporu okunur.
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Surum APK'sini android/build/dist altina hem surumlu hem sabit adla kopyalar ve SHA-256'sini
 * yazar. Sabit ad (GoodbyeDPI-Android.apk) guncelleme denetleyicisinin GitHub'da aradigi dosyadir.
 */
abstract class DistApkTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val artifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** -Pgdpi.abi ile tek ABI'ye daraltilmis derleme; dagitima cikmamali. */
    @get:Input
    @get:Optional
    abstract val devAbi: Property<String>

    @TaskAction
    fun copy() {
        if (devAbi.isPresent) {
            throw GradleException(
                "dist tek ABI'li (gdpi.abi=${devAbi.get()}) APK uretmez; -Pgdpi.abi olmadan calistirin.",
            )
        }
        val built = artifactsLoader.get().load(apkFolder.get())
            ?: throw GradleException("Surum APK'si bulunamadi: ${apkFolder.get().asFile}")
        val apk = File(built.elements.single().outputFile)
        val out = outputDir.get().asFile.apply { mkdirs() }

        val versioned = File(out, "GoodbyeDPI-Android-${versionName.get()}.apk")
        val fixed = File(out, "GoodbyeDPI-Android.apk")
        apk.copyTo(versioned, overwrite = true)
        apk.copyTo(fixed, overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        // Surum notuna yapistirilacak satir; guncelleyici bu 64 haneli ozeti APK adinin yaninda arar.
        File(out, "SHA256SUMS.txt").writeText("$sha  ${versioned.name}\n$sha  ${fixed.name}\n")

        logger.lifecycle("APK: ${versioned.absolutePath} (${apk.length()} bayt)")
        logger.lifecycle("APK: ${fixed.absolutePath}")
        logger.lifecycle("SHA-256: $sha")
    }
}

androidComponents {
    onVariants { variant ->
        val fileName = if (variant.buildType == "release") {
            "GoodbyeDPI-Android-$appVersionName.apk"
        } else {
            "GoodbyeDPI-Android-$appVersionName-${variant.name}.apk"
        }
        variant.outputs.forEach { output ->
            // Ciktinin adini degistirmenin AGP 8'de hala calisan tek yolu bu ic API.
            (output as? VariantOutputImpl)?.outputFileName?.set(fileName)
        }

        if (variant.name == "release") {
            val singleAbi = abis.singleOrNull()?.takeIf { abis != allAbis }
            tasks.register<DistApkTask>("dist") {
                group = "distribution"
                description = "Surum APK'sini build/dist altina kopyalar ve SHA-256'sini yazar."
                dependsOn("assembleRelease")
                apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
                artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                versionName.set(appVersionName)
                if (singleAbi != null) devAbi.set(singleAbi)
                outputDir.set(rootProject.layout.buildDirectory.dir("dist"))
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
