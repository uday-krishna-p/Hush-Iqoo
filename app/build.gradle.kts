import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hush"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hush"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep the model file uncompressed inside the APK so it can be memory-mapped straight from assets.
    androidResources {
        noCompress += listOf("tflite")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)      // AppCompatActivity + runtime permission helpers
    implementation(libs.androidx.core.ktx)       // small Kotlin helpers (ContextCompat etc.)
    implementation(libs.play.services.nearby)    // Nearby Connections (offline phone-to-phone link)
    implementation(libs.tensorflow.lite)         // runs yamnet.tflite on the CPU
}
