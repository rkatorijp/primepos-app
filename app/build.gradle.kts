plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// version from the CI run number, so every build installs over the previous one
val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "jp.co.primehub.pos"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.co.primehub.pos"
        minSdk = 24
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
    }

    // release signing comes from GitHub secrets (see README); without them the build is signed with the debug key
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
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
