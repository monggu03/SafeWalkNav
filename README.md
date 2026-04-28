# SafeWalkNav

시각장애인을 위한 보행 내비게이션 모바일 앱. TMap REST API 기반의 경로 안내, GPS·센서 퓨전 기반 방향 안내, TTS·진동·오디오 비콘 피드백, 횡단보도 직진 유지 보정을 제공합니다.

**Kotlin Multiplatform Mobile (KMM)** 으로 안드로이드/iOS 두 플랫폼을 동일한 비즈니스 로직으로 지원합니다.

> 동국대학교 컴퓨터공학과 CSC2004 공개SW프로젝트 — 1조 작품

## 핵심 기능

- **도보 내비게이션** — TMap 보행자 경로 탐색, Forward-Only Waypoint 추적, 4단계 도착 안내(FAR / APPROACHING / NEAR / ARRIVED)
- **방향 안내** — Circular Kalman Filter 기반 heading 평활화(GPS accuracy 동적 가중), 시계 방향 안내("3시 방향"), 정지 시 자동 보정
- **보행 쏠림 보정** — Cross-track error 감지 + 횡단보도 구간 임계값 강화(2m → 1m, bearing diff 15° → 10°)
- **음성 안내** — 한국어 STT(흔들기로 호출), TTS, 거리 기반 오디오 비콘, 입구 방향 스테레오 패닝
- **신호등 색상 인식** — YOLOv8n(ped_green/ped_red) — *통합 진행 중*

## 프로젝트 구조

KMM 멀티 모듈로 비즈니스 로직과 OS 의존 코드를 분리합니다.

```
SafeWalkNav/
├── shared/                                 # ⭐ KMM 공통 모듈 (Android + iOS 공통)
│   └── src/
│       ├── commonMain/.../navigation/      # OS 무관 비즈니스 로직 (13 파일)
│       │   ├── NavigationManager.kt        #   경로 추종 핵심 엔진
│       │   ├── KalmanHeading.kt            #   Circular Kalman 필터 (클래스)
│       │   ├── CrossTrack.kt               #   cross-track error 계산
│       │   ├── CrosswalkGuard.kt           #   횡단보도 구간 강화 헬퍼
│       │   ├── BearingMath.kt              #   bearing / angleDiff / distanceBetween
│       │   ├── ClockDirection.kt           #   시계 방향 안내
│       │   ├── TMapApiClient.kt            #   TMap REST 호출 (Ktor)
│       │   ├── TMapRoute.kt                #   데이터 모델 (TMapRoute, LatLng, Waypoint, ArrivalState enum)
│       │   ├── POIResult.kt                #   POI 검색 결과
│       │   ├── GpsLocation.kt              #   위치 추상화 (data class)
│       │   ├── HeadingLogger.kt            #   CSV 로깅 인터페이스 + NoopHeadingLogger
│       │   ├── Logger.kt                   #   expect object Logger
│       │   └── Time.kt                     #   expect fun currentTimeMillis()
│       ├── androidMain/.../navigation/     # Android 전용 actual 구현 (4 파일)
│       │   ├── AndroidHeadingLogger.kt     #   File + BufferedWriter 기반 CSV
│       │   ├── LocationConverter.kt        #   Location → GpsLocation 확장
│       │   ├── Logger.android.kt           #   actual (android.util.Log)
│       │   └── Time.android.kt             #   actual (System.currentTimeMillis)
│       └── iosMain/                        # iOS actual (jiminlyy 작업 예정)
│
├── androidApp/                             # ⭐ 안드로이드 앱
│   ├── libs/                               #   TMap SDK aar (gitignored, 직접 배치)
│   ├── google-services.json                #   Firebase 설정 (gitignored)
│   ├── build.gradle.kts                    #   Android application 모듈 빌드 스크립트
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/safewalknav/
│       │   ├── MainActivity.kt             #   UI/센서/오디오/TTS/STT 오케스트레이터
│       │   └── location/LocationTracker.kt #   FusedLocationProvider GPS
│       └── res/                            #   layout, values, raw 등 안드로이드 리소스
│
├── iosApp/                                 # ⭐ iOS 앱 (골격 — jiminlyy 작업 예정)
│   ├── README.md                           #   Xcode 셋업 + iOS actual 구현 가이드
│   └── iosApp/
│       ├── iOSApp.swift                    #   SwiftUI 앱 진입점 (placeholder)
│       └── ContentView.swift               #   메인 화면 (placeholder)
│
├── tools/
│   ├── heading_analysis.py                 # Kalman Before/After 시각화
│   └── generate_dummy_data.py
│
├── settings.gradle.kts                     # 모듈 등록 (:androidApp, :shared)
├── build.gradle.kts                        # 루트 — KMP/AGP 플러그인 선언
├── gradle.properties                       # KMM 옵션 + 메모리 설정
├── .gitattributes                          # LF 줄바꿈 통일 (Win/Mac 협업)
└── local.properties                        # TMAP_APP_KEY (gitignored)
```

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.2.0 |
| Gradle | 8.2 |
| Android SDK | minSdk 26, targetSdk 34, compileSdk 34 |
| JDK | 17 |
| Ktor | 2.3.7 |
| kotlinx-serialization | 1.6.2 |
| kotlinx-coroutines | 1.7.3 |

**Android 빌드**: Windows / macOS / Linux 어디서든 가능.
**iOS 빌드**: macOS + Xcode 필수 (Kotlin/Native 컴파일러가 ARM64 framework 생성).

## 설치 및 빌드 (Android)

### 0. 프로젝트 위치 — OneDrive 외부 권장

OneDrive 안에 두면 Gradle build/ 폴더가 동기화되면서 빌드 충돌이 자주 발생합니다. **`C:\Dev\SafeWalkNav` 같은 외부 경로**에 clone 권장:

```bash
mkdir -p /c/Dev
cd /c/Dev
git clone https://github.com/monggu03/SafeWalkNav.git
cd SafeWalkNav
```

### 1. TMap SDK 다운로드

라이선스 정책상 SDK `.aar` 파일은 저장소에 포함되어 있지 않습니다. [TMap 개발자센터](https://tmapapi.tmapmobility.com/)에서 직접 받으세요.

`androidApp/libs/` 디렉토리에 다음 두 파일을 배치:

```
androidApp/libs/
├── vsm-tmap-sdk-v2-android-2.0.0.aar
└── tmap-sdk-3.5.aar
```

### 2. TMap API 키 등록

[TMap 개발자센터](https://tmapapi.tmapmobility.com/)에서 앱 키 발급 후, 프로젝트 루트의 `local.properties`에 추가:

```properties
TMAP_APP_KEY=발급받은_앱_키
```

`local.properties`는 `.gitignore`에 포함되어 커밋되지 않습니다.

### 3. Firebase 설정

[Firebase Console](https://console.firebase.google.com/)에서 프로젝트의 `google-services.json`을 받아 **`androidApp/` 폴더에 직접 배치**합니다. 이 파일도 `.gitignore`로 처리되어 커밋되지 않습니다.

> 팀원 간 공유는 카톡/Slack 등으로 직접 전달.

### 4. 빌드

Android Studio에서 프로젝트를 열고 Sync 후 실행. CLI 빌드:

```bash
./gradlew :androidApp:assembleDebug
```

APK 출력: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`

## 설치 및 빌드 (iOS) — 진행 중

`iosApp/` 디렉토리에 골격이 잡혀 있고, 본격 작업 가이드는 `iosApp/README.md` 에 있습니다.

빌드 흐름:

1. macOS + Xcode 15+
2. `shared/build.gradle.kts` 의 iOS 타겟 활성화 (현재 주석 처리됨)
3. iOS 측 actual 구현(`Logger.ios.kt`, `Time.ios.kt`, `CLLocationConverter.kt`) 작성
4. `iosApp/iosApp.xcodeproj` 생성 후 SwiftUI 화면 작성

상세는 `iosApp/README.md` 참조.

## 주요 권한 (Android)

- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS 위치
- `INTERNET`, `ACCESS_NETWORK_STATE` — TMap REST API 호출
- `VIBRATE` — 진동 피드백
- `FOREGROUND_SERVICE` — 백그라운드 TTS
- `RECORD_AUDIO` — 음성 인식(STT)
- `CAMERA` — 신호등 인식 (통합 시점에 추가 예정)

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android + 공통), Swift (iOS), Python (분석 도구) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile (KMM, expect/actual 패턴) |
| HTTP | Ktor Client (Android: OkHttp engine) |
| JSON | kotlinx-serialization (트리 탐색) |
| 비동기 | Kotlin Coroutines + Flow |
| 지도 | TMap SDK (Android), Apple MapKit 또는 TMap iOS (예정) |
| GPS | FusedLocationProvider (Android), CoreLocation (iOS 예정) |
| TTS/STT | Android `TextToSpeech` / `RecognizerIntent`, AVSpeechSynthesizer / SFSpeechRecognizer 예정 |
| ML | YOLOv8n + TFLite (Android, 통합 진행 중), CoreML (iOS) |
| 배포 | Firebase App Distribution |
| 분석 | Python(matplotlib, pandas) — Kalman Before/After 시각화 |

## 알고리즘 핵심

- **Circular Kalman Filter** — bearing(원형각)을 sin/cos 두 직교 성분으로 분해 후 각 성분에 1D Kalman 적용. 350°/10° 같은 경계 문제 회피. GPS accuracy를 measurement noise로 동적 사용.
- **Forward-Only Waypoint Selection** — 한 번 지나간 waypoint는 다시 잡지 않음. GPS 튀김으로 인한 안내 혼선 방지.
- **4단계 도착 판정** — FAR(15m+) / APPROACHING(15m) / NEAR(5m) / ARRIVED(2m). 히스테리시스(NEAR→7m, APPROACHING→18m)로 GPS 흔들림 흡수.
- **Cross-track Error + 횡단보도 강화** — 경로 선분 대비 수직 이탈 거리(부호 있음)로 측면 드리프트 감지. 횡단보도 구간(다음 wp 30m 이내 ~ 직전 wp 20m 이내)에서는 임계값 강화.

## 팀

- **이도윤(@monggu03)** — Android, 알고리즘, KMM 마이그레이션
- **김지민(@jiminlyy)** — AI(YOLOv8n 학습), iOS

## 라이선스

신호등 모델(YOLOv8n) 통합 시점에 AGPL-3.0 라이선스가 적용됩니다 (Ultralytics YOLO 라이선스 의존).
