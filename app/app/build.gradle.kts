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
            // secrets) so Android does not treat each CI build as a different
            // app. Falls back to default debug signing locally on a dev box.
            val ksB64 = System.getenv("POCKETGLM_KEYSTORE_B64")
            if (ksB64 != null) {
                signingConfig = signingConfigs.create("pocketglm") {
                    val ksFile = File(rootProject.projectDir, "keystore/pocketglm.keystore")
                    java.util.Base64.getDecoder().decode(ksB64).let { ksFile.parentFile?.mkdirs(); ksFile.writeBytes(it) }
                    storeFile = ksFile
                    storePassword = System.getenv("POCKETGLM_KEYSTORE_PASSWORD")
                    keyAlias = "pocketglm"
                    keyPassword = System.getenv("POCKETGLM_KEYSTORE_PASSWORD")
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

    // The Go bridge binary is downloaded by CI into src/main/assets/bridge/
    // (zai-api-android-arm64) before the APK is assembled, so it ships inside
    // the APK and is extracted to filesDir on first launch.
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
