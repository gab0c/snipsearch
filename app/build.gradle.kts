plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.snipsearch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.snipsearch"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    // ONNX models must stay uncompressed for zero-copy AssetFileDescriptor IPC to the isolated OCR process.
    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    // The Play-encrypted dependency block breaks F-Droid / Obtainium / apksigcopier reproducibility.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Uncompressed, page-aligned .so packaging is required for 16KB page sizes.
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        error += setOf(
            "ExportedService",
            "UnsafeImplicitIntentLaunch",
            "TrustAllX509TrustManager",
            "BadHostnameVerifier",
            "SetJavaScriptEnabled",
            "HardcodedDebugMode",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    // Robolectric's SharedMemory shadow uses the JDK file-descriptor accessor.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    implementation(libs.zxing.core)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.android.all.instrumented)
}
