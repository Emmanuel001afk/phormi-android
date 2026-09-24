plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uong.phormi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uong.phormi"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("PHORMI_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("PHORMI_VERSION_NAME").orNull ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("PHORMI_KEYSTORE_PATH").orNull
            val storePassword = providers.environmentVariable("PHORMI_KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.environmentVariable("PHORMI_KEY_ALIAS").orNull
            val keyPassword = providers.environmentVariable("PHORMI_KEY_PASSWORD").orNull

            if (keystorePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else if (System.getenv("CI") == "true") {
                throw GradleException("Persistent Phormi release signing is not configured.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { aidl = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
}
