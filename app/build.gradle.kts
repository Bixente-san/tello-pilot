plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vincent.tellopilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vincent.tellopilot"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "3.2"
    }

    signingConfigs {
        // Clé de debug stable versionnée dans le dépôt : permet d'installer
        // les nouvelles versions par-dessus l'ancienne sans désinstaller.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // llama-server est livré comme "bibliothèque native" : il doit être extrait sur
    // le disque pour pouvoir être exécuté.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
