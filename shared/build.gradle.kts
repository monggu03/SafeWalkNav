plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

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
            // POC 단계: 의존성 없이 순수 Kotlin stdlib만
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