plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tiredfone.soundcloudrpc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tiredfone.soundcloudrpc"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.06"
    }

    val keystoreFile = file("signing/release.jks")
    val signingReady = keystoreFile.exists() ||
        System.getenv("SIGNING_KEYSTORE_BASE64") != null

    if (signingReady) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "soundcloudrpc123"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "soundcloudrpc"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "soundcloudrpc123"
            }
        }
    }

    buildTypes {
        debug {
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            if (signingReady) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coil)
}
