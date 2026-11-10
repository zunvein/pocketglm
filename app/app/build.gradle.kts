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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
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
        // The "jni library" is actually the Go bridge binary; never strip or
        // compress it (executable must land byte-identical).
        jniLibs {
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
