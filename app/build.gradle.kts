plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStorePath = System.getenv("PKGPOCKET_KEYSTORE_PATH")
val signingStorePassword = System.getenv("PKGPOCKET_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("PKGPOCKET_KEY_ALIAS")
val signingKeyPassword = System.getenv("PKGPOCKET_KEY_PASSWORD")
val betaApiUrl = System.getenv("PKGPOCKET_BETA_API_URL") ?: ""
val proApiUrl = System.getenv("PKGPOCKET_PRO_API_URL") ?: "https://pkg-pocket-api.wbjoaovictor.workers.dev"
val libraryApiUrl = System.getenv("PKGPOCKET_LIBRARY_API_URL") ?: "https://pkg-pocket-library.wbjoaovictor.workers.dev"

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
        versionCode = 64
        versionName = "1.0.0-rc.35"
        buildConfigField("int", "BETA_MAX_GAMES", "5")
        buildConfigField("int", "BETA_MAX_DLCS", "5")
        buildConfigField("int", "BETA_MAX_UPDATES", "5")
        buildConfigField("String", "BETA_API_URL", "\"${betaApiUrl}\"")
        buildConfigField("String", "PRO_API_URL", "\"${proApiUrl}\"")
        buildConfigField("String", "LIBRARY_API_URL", "\"${libraryApiUrl}\"")
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_DEMO", "true")
            buildConfigField("boolean", "PUBLIC_BETA", "false")
            // DEBUG usa a assinatura padrão de desenvolvimento.
            // A chave estável fica exclusiva do RELEASE.
        }

        getByName("release") {
            buildConfigField("boolean", "ENABLE_DEMO", "false")
            buildConfigField("boolean", "PUBLIC_BETA", "false")
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
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
