plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStorePath = System.getenv("PKGPOCKET_KEYSTORE_PATH")
val signingStorePassword = System.getenv("PKGPOCKET_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("PKGPOCKET_KEY_ALIAS")
val signingKeyPassword = System.getenv("PKGPOCKET_KEY_PASSWORD")
val stableSigningReady =
    !signingStorePath.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank() &&
    file(signingStorePath).exists()

android {
    namespace = "com.pkgpocket.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pkgpocket.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.6.0"
    }

    signingConfigs {
        if (stableSigningReady) {
            create("stable") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_DEMO", "true")
            if (stableSigningReady) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }

        getByName("release") {
            buildConfigField("boolean", "ENABLE_DEMO", "false")
            isMinifyEnabled = false
            if (stableSigningReady) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
