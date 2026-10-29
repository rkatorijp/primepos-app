plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Use the GitHub Actions run number for monotonically increasing build versions.
val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "jp.co.primehub.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "prime.pos"   // must match the app in Play Console
        minSdk = 24
        targetSdk = 36
        versionCode = run
        versionName = "1.0.$run"
    }

    // Release builds must be signed with a private upload key.
    // Do not fall back to the debug key for Play Store releases.
    val ks = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
