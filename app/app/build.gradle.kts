plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.zunvein.pocketglm"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.zunvein.pocketglm"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.3.5-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Shrink the debug APK too (it is the one that gets side-loaded
            // over mobile data): R8 + resource shrinking. Rules live in
            // proguard-rules.pro (JavascriptInterface methods are kept).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Stable CI signing: same keystore every build (uploaded as GHA
            // secrets) so Android sees every CI build as the same app.
            // Falls back to default debug signing locally on a dev box.
            // CI decodes the keystore to a file in the workflow step
            // (POCKETGLM_KEYSTORE_FILE) — no base64 handling inside Gradle.
            val ksFile = File(rootProject.projectDir, "keystore/pocketglm.keystore")
            val ksPass = System.getenv("POCKETGLM_KEYSTORE_PASSWORD")
            if (ksFile.exists() && !ksPass.isNullOrEmpty()) {
                signingConfig = signingConfigs.create("pocketglm") {
                    storeFile = ksFile
                    storePassword = ksPass
                    keyAlias = "pocketglm"
                    keyPassword = ksPass
                    storeType = "PKCS12"
                }
            }
        }
    }

    packaging {
        // The "jni library" is actually the Go bridge binary. The service
        // copies it out of nativeLibraryDir and exec()s it, so it must exist
        // as a REAL file on disk. AGP 8 defaults (useLegacyPackaging=false)
        // leave .so files mmap'ed inside the APK and nativeLibraryDir ends up
        // EMPTY on the device; opting into legacy packaging makes the
        // installer extract libzai.so with the exec bit set.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libzai.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The Go bridge binary ships as jniLibs/arm64-v8a/libzai.so (native
    // library packaging keeps it uncompressed + executable) and is copied
    // from applicationInfo.nativeLibraryDir at first launch.
    sourceSets {
        getByName("main") {
            // default dirs only; no custom assets needed
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
