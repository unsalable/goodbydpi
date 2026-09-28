pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modullerin kendi depo tanimlamasina izin verme: tum bagimliliklar ayni iki depodan gelsin.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GoodbyeDPI-Android"

include(":app")
// Yalnizca emulator testlerinde kullanilan yardimci uygulama; APK'ya girmez.
include(":probe")
