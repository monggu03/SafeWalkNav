plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

val ktorVersion = "2.3.7"           // Kotlin 1.9.22 호환
val kotlinxSerializationVersion = "1.6.2"
val coroutinesVersion = "1.7.3"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    // iOS 타겟은 macOS + Xcode 환경에서만 안정적으로 빌드됨.
    // POC 단계(Windows)에서는 일시 제외.
    // jiminlyy의 Mac에서 본 마이그레이션 작업 시 아래 블록 활성화.
    /*
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }
    */

    sourceSets {
        commonMain.dependencies {
            // 비동기
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

            // HTTP — TMap REST API 호출용
            implementation("io.ktor:ktor-client-core:$ktorVersion")

            // JSON — 응답 텍스트를 JsonElement 트리로 파싱
            //         (기존 Gson JsonParser 와 동등한 사용감)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            // Android engine — 내부적으로 OkHttp 사용. 기존 OkHttp 사용 경험 그대로 재사용 가능.
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
        }
    }
}

android {
    namespace = "com.example.safewalknav.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
