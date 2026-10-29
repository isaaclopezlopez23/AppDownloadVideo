import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma propia opcional (para poder actualizar la app sin desinstalar entre compilaciones de CI).
// Si no se definen estas variables de entorno, la release se firma con la clave debug local.
val ksFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "app.descargador.videos"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.descargador.videos"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("propia") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("propia") ?: signingConfigs.getByName("debug")
        }
    }

    // Python + yt-dlp + ffmpeg van como binarios nativos: un APK por arquitectura para que pese menos.
    // Casi todos los móviles actuales usan arm64-v8a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // youtubedl-android ejecuta python/ffmpeg/quickjs desde nativeLibraryDir: hay que extraerlos.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // yt-dlp + Python + QuickJS (runtime JS para YouTube) + ffmpeg empaquetados para Android
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
