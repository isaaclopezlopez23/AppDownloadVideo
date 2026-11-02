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
    namespace = "app.conversor.video"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.conversor.video"
        minSdk = 26
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

    // ffmpeg va como binario nativo: un APK por arquitectura para que pese menos.
    // Casi todos los móviles actuales usan arm64-v8a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // ffmpeg y ffprobe se ejecutan desde nativeLibraryDir: hay que extraerlos al instalar.
            useLegacyPackaging = true
            // De youtubedl-android solo hacen falta ffmpeg y las bibliotecas del zip de Python;
            // el intérprete y QuickJS sobran.
            excludes += setOf("**/libpython.so", "**/libqjs.so")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ffmpeg 7.1 + ffprobe compilados para Android (paquetes de Termux) dentro de youtubedl-android.
    // "library" solo aporta las bibliotecas compartidas que ffmpeg necesita (libc++, libcrypto…).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // Para descomprimir respetando los enlaces simbólicos (misma versión que usa youtubedl-android).
    implementation("org.apache.commons:commons-compress:1.12")
}
