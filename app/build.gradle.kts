plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.poweran2020.rclone.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.poweran2020.rclone.manager"
        minSdk = 29
        targetSdk = 35
        versionCode = 120
        versionName = "1.2.0"
    }

    val keystorePath = System.getenv("KEYSTORE_FILE")
    val resolvedKeystore = if (!keystorePath.isNullOrEmpty()) {
        val f = rootProject.file(keystorePath)
        if (f.exists()) f else file(keystorePath).let { if (it.exists()) it else null }
    } else null

    signingConfigs {
        if (resolvedKeystore != null) {
            create("release") {
                storeFile = resolvedKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (resolvedKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures { compose = true }
}


kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.google.zxing:core:3.5.3")
}
