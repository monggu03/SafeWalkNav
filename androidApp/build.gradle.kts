import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val tmapAppKey: String = run {
    val props = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    props.getProperty("TMAP_APP_KEY", "")
}

// Release APK 서명 설정 — keystore.properties (gitignored) 에서 로드.
// 파일 없으면 release 빌드 시 서명 생략 (unsigned APK 산출).
// keystore.properties 양식:
//   storeFile=safewalknav-release.keystore
//   storePassword=...
//   keyAlias=safewalknav
//   keyPassword=...
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // viewBinding 제거 (2026-06-15 정리) — *Binding 클래스 사용처 0건, 전부 findViewById.
        buildConfig = true
        mlModelBinding = true
    }
}

dependencies {
    // KMM 공통 모듈 (Kotlin Multiplatform shared)
    implementation(project(":shared"))

    // Firebase 제거 (2026-07) — 카메라-only 전환 후 미사용. 텔레메트리는 향후 자체 백엔드로.

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

    // TMap SDK (지도 widget) — PR-UI(시각장애인 풀스크린) 마이그레이션으로 완전 제거됨.
    // 시각장애인 사용자 시점에서 지도 화면이 필요 없으므로 SDK aar 의존 + libs/*.aar 파일 모두 삭제.
    // 길찾기/POI 검색에 쓰는 TMap REST API 는 shared 모듈의 Ktor 기반 TMapApiClient 가 담당 (HTTP appKey 만으로 충분).
    // 향후 디버그용 지도 시각화가 필요하면 SKT 개발자 포털에서 aar 재다운로드 후 다시 등록.

    // ===== ML / 카메라 / 영상 처리 (PR-1: 인프라 셋업) =====

    // CameraX — 신호등 추론 / 점자블록 추적용 카메라 스트림
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // TensorFlow Lite + GPU delegate — 신호등(YOLOv8n) 추론
    // Galaxy S25 (Adreno 830) 는 GPU delegate 풀 지원
    // gpu-delegate-plugin 은 옛 API 사용해 lite-gpu:2.14 와 호환 안 됨 → 제외
    // 추론 경로는 NNAPI 사용 (TrafficLightDetector 참조).
    // tensorflow-lite-gpu 는 실사용처가 없어 제거 (2026-06-15 정리).
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // OpenCV 제거 (2026-06-15 정리) — 유일 사용처였던 MLCameraProbe(의존성 검증용 임시 파일) 삭제됨.
    // Room·proj4j 제거 (2026-07 정리) — 신호 공공데이터 API 폐기로 신호등 위치 로컬 캐시와
    // EPSG:5186→WGS84 좌표 변환(신호등 위치 전용)이 함께 사라짐.
}
