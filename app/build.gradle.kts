plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.poolguide.tool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poolguide.tool"
        minSdk = 29
        // targetSdk 34 is a deliberate choice for v1 (see README, "Why targetSdk 34"):
        // it avoids the API 35 install-time foreground-service declaration
        // friction, while the code still handles Android 15 runtime behaviour
        // (partial screen sharing, overlay enablement) correctly.
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // x86_64 is included on purpose: the CI emulator tests run on x86_64.
        // A future release can use APK splits to drop it and shrink the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // Release signing via env vars (CI decodes the release keystore
            // from the RELEASE_KEYSTORE_BASE64 secret). When the env vars
            // are absent (local builds) this config is unused — the release
            // build type falls back to the debug signing config below.
            val keystorePath = System.getenv("RELEASE_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Signed with the persistent release keystore in CI (env vars
            // present); locally, without the keystore, falls back to debug
            // signing so the build still works. NOTE: v1.0.0 shipped
            // debug-signed, so v1.0.1 (first release-signed build) requires
            // uninstall + reinstall — the signature changes once, then
            // stays stable for all future releases.
            signingConfig = if (System.getenv("RELEASE_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
        // Explicit: MainActivity reads BuildConfig.VERSION_NAME/CODE
        // for the diagnostics report header (v1.0.6).
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // OpenCV — Maven AAR route (ChatGPT's recommended route, verified:
    // org.opencv:opencv exists on Maven Central, latest 5.1.0; we pin 4.10.0
    // because it is the stable 4.x API the kaigiii-era pipeline was written
    // against, and 4.9+ supports Android 15's 16 KB page size).
    // Fallback if this artifact ever goes missing for an ABI:
    //   implementation("com.github.opencv:opencv:4.9.0")  // JitPack
    implementation("org.opencv:opencv:4.10.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
