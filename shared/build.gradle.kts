plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

// 2026-07: 내비게이션(TMap API·직렬화·비동기) 전면 제거로 Ktor/serialization/coroutines 불필요.
// shared 모듈은 이제 순수 로직(SignalDecisionEngine)만 담아 외부 의존성이 없다.

val isRunningOnMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    // iOS 타겟은 macOS에서만 빌드 가능 — Windows/Linux에서는 등록 자체를 건너뜀
    if (isRunningOnMac) {
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
    }

    sourceSets {
        commonMain.dependencies {
            // 외부 의존성 없음 — SignalDecisionEngine 은 순수 Kotlin stdlib 만 사용.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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