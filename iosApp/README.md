# SafeWalk iOS App

iOS 앱 모듈 — SwiftUI + KMM shared 모듈.

> ⚠️ 작업 시작 전 단계: Xcode 프로젝트 골격은 아직 없고 폴더만 생성되어 있습니다.

## 작업 시작 가이드 (jiminlyy 용)

### 1. 환경

- macOS 13+ (Ventura 이상)
- Xcode 15+
- Kotlin 1.9.22 / KMP 호환 환경 (Mac에 JDK 17 필요)

### 2. shared 모듈 iOS 타겟 활성화

`shared/build.gradle.kts` 의 다음 블록 주석 해제:

```kotlin
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
```

### 3. iOS 측 actual 구현 추가

다음 두 파일을 생성:

`shared/src/iosMain/kotlin/com/example/safewalknav/navigation/Logger.ios.kt`
```kotlin
package com.example.safewalknav.navigation

import platform.Foundation.NSLog

actual object Logger {
    actual fun d(tag: String, message: String) {
        NSLog("[$tag] D: $message")
    }
    actual fun w(tag: String, message: String) {
        NSLog("[$tag] W: $message")
    }
}
```

`shared/src/iosMain/kotlin/com/example/safewalknav/navigation/Time.ios.kt`
```kotlin
package com.example.safewalknav.navigation

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
```

추가로 `Location → GpsLocation` 변환을 위한 CoreLocation 어댑터:

`shared/src/iosMain/kotlin/com/example/safewalknav/navigation/CLLocationConverter.kt`
```kotlin
package com.example.safewalknav.navigation

import platform.CoreLocation.CLLocation

fun CLLocation.toGpsLocation(): GpsLocation = GpsLocation(
    latitude = coordinate.useContents { latitude },
    longitude = coordinate.useContents { longitude },
    speed = speed.toFloat().coerceAtLeast(0f),
    bearing = course.toFloat().coerceAtLeast(0f),
    accuracy = horizontalAccuracy.toFloat(),
    hasAccuracy = horizontalAccuracy >= 0,
)
```

### 4. iosApp Xcode 프로젝트 생성

이 디렉토리(`iosApp/`)에 Xcode 새 프로젝트 생성:

- File → New → Project → iOS → App
- Product Name: `iosApp`
- Interface: SwiftUI
- Language: Swift
- 저장 위치: `iosApp/` (이 디렉토리)

### 5. shared framework 의존성 추가

Xcode 프로젝트의 Build Phases에 다음 Run Script 추가:

```bash
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

또는 더 단순하게 — `cocoapods` 플러그인 사용 (KMP 표준).

### 6. 작업 영역

iOS 측에서 작성할 것:
- `iosApp/ContentView.swift` — 메인 SwiftUI 화면
- `iosApp/ViewModels/NavigationViewModel.swift` — shared.NavigationManager 래퍼
- `iosApp/Sensors/HeadingProvider.swift` — CMMotionManager 센서 퓨전
- `iosApp/Location/LocationTracker.swift` — CoreLocation
- `iosApp/Audio/TtsManager.swift` — AVSpeechSynthesizer
- `iosApp/Audio/SttManager.swift` — SFSpeechRecognizer
- `iosApp/ML/TrafficLightDetector.swift` — CoreML (best.mlpackage)
- `iosApp/Map/MapView.swift` — Apple MapKit (또는 TMap iOS SDK)

### 7. shared 모듈 호출 예시 (Swift)

```swift
import shared

let apiClient = TMapApiClient(appKey: tmapKey)
let logger = NoopHeadingLogger.shared  // 임시
let navigationManager = NavigationManager(
    tMapApiClient: apiClient,
    headingLogger: logger
)

// GPS 업데이트 시
let gpsLoc = clLocation.toGpsLocation()
Task {
    try await navigationManager.updateLocation(location: gpsLoc)
}
```

## 참고 자료

- KMP iOS 통합 공식 가이드: https://kotlinlang.org/docs/multiplatform-mobile-getting-started.html
- shared 모듈의 NavigationManager 시그니처는 `shared/src/commonMain/kotlin/com/example/safewalknav/navigation/NavigationManager.kt` 참조
