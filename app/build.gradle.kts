import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.moss.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.moss.live"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    // CI 环境（有 KEYSTORE_FILE env + 密钥文件存在）才注册 release 签名；
    // 本地或未配置 secrets 时回退 debug 签名，保证任何环境可构建
    signingConfigs {
        val ksFile = System.getenv("KEYSTORE_FILE")
        val ksPass = System.getenv("KEYSTORE_PASSWORD")
        val ksAlias = System.getenv("KEY_ALIAS")
        val ksKeyPass = System.getenv("KEY_PASSWORD")
        if (ksFile != null && ksPass != null && File(ksFile).exists()) {
            create("ci") {
                storeFile = File(ksFile)
                storePassword = ksPass
                keyAlias = ksAlias ?: "moss-live"
                keyPassword = ksKeyPass ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (signingConfigs.names.contains("ci")) signingConfigs.getByName("ci")
                else signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
