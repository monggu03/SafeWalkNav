import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val tmapAppKey: String = run {
    val props = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    props.getProperty("TMAP_APP_KEY", "")
}

android {
    namespace = "com.example.safewalknav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.safewalknav"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // TMap API Key - local.properties에서 로드
        buildConfigField("String", "TMAP_APP_KEY", "\"$tmapAppKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // KMM 공통 모듈 (Kotlin Multiplatform shared)
    implementation(project(":shared"))

    // Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-analytics")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Google Play Services Location (GPS)
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // OkHttp / Gson 의존성은 KMM 마이그레이션으로 제거됨.
    // TMap REST API 호출은 shared 모듈의 Ktor 기반 TMapApiClient 가 담당.
    // Ktor Android engine 이 내부적으로 OkHttp 를 사용하므로 결과적으로 같은 transport.

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // TMap SDK (지도)
    implementation(files("libs/vsm-tmap-sdk-v2-android-2.0.0.aar"))
    implementation(files("libs/tmap-sdk-3.5.aar"))
    implementation("com.google.flatbuffers:flatbuffers-java:24.3.25")
}
