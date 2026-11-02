import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Clé TMDB injectée à la compilation (secret GitHub), jamais écrite dans le code.
val tmdbKey: String = (System.getenv("TMDB_KEY") ?: "").replace("\"", "")

// Clé de signature fournie par les secrets GitHub (jamais dans le dépôt).
// Sans elle, l'APK est signé avec une clé temporaire : il s'installe, mais les
// mises à jour intégrées ne pourront pas le remplacer.
val keystoreFile: String? = System.getenv("BIBITV_KEYSTORE_FILE")?.takeIf { File(it).exists() }
val keystorePassword: String = System.getenv("BIBITV_KEYSTORE_PASSWORD") ?: ""

android {
    namespace = "com.bibitv.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bibitv.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "1.3.1"
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
        ndk {
            // Box et TV Android : ARM 32 et 64 bits
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = "bibitv"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            // R8 : code optimisé (interface plus fluide sur les TV)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Moteur vidéo VLC : décodage matériel 4K HEVC, MKV, AC3, sous-titres…
    implementation("org.videolan.android:libvlc-all:3.6.5")
}
