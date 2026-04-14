// Top-level build file
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// OneDrive가 build 폴더를 동기화하면서 Gradle 빌드 깨뜨리는 문제 방지
// build 출력을 OneDrive 밖(C:\SafeWalkBuild)으로 리다이렉트
allprojects {
    layout.buildDirectory = file("C:/SafeWalkBuild/${project.name}")
}
