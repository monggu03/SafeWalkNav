# SafeWalkNav

시각장애인을 위한 보행 내비게이션 모바일 앱. TMap REST API 기반의 경로 안내, GPS·센서 퓨전 기반 방향 안내, TTS·진동·오디오 비콘 피드백, 횡단보도 직진 유지 보정을 제공합니다.

**Kotlin Multiplatform Mobile (KMM)** 으로 안드로이드/iOS 두 플랫폼을 동일한 비즈니스 로직으로 지원합니다.

> 동국대학교 컴퓨터공학과 CSC2004 공개SW프로젝트 — 1조 작품

## 핵심 기능

- **도보 내비게이션** — TMap 보행자 경로 탐색, Forward-Only Waypoint 추적, 4단계 도착 안내(FAR / APPROACHING / NEAR / ARRIVED)
- **TBFW (Trust-Based Forward Waypoint)** — GPS 정확도·heading 차·속도를 합쳐 Trust Score를 산출하고, 신뢰도 등급(HIGH/MEDIUM/LOW/CRITICAL)에 따라 waypoint 통과 거리와 안내 강도를 차등 적용
- **경로 사전 분석** — `RouteAnnotator`가 경로 전체를 곡선/회전/직진으로 사전 분류해 굽은 길을 미리 안내 (현재 iOS TBFW 데모 화면에 연결, 메인 안내 파이프라인 연동 예정)
- **방향 안내** — Circular Kalman Filter 기반 heading 평활화(GPS accuracy 동적 가중), 시계 방향 안내("3시 방향"), 정지 시 자동 보정
- **보행 쏠림 보정** — Cross-track error 감지 + 횡단보도 구간 임계값 강화(2m → 1m, bearing diff 15° → 10°)
- **횡단보도 신호** — 서울 T-data 신호제어기 API 연동, 잔여시간 쿨다운 캐싱(60초)
- **음성 안내** — 한국어 STT(흔들기로 호출), TTS, 거리 기반 오디오 비콘, 입구 방향 스테레오 패닝
- **신호등 색상 인식** — YOLOv8n(ped_green/ped_red) — *통합 진행 중*

## 프로젝트 구조

KMM 멀티 모듈로 비즈니스 로직과 OS 의존 코드를 분리합니다. `shared/commonMain/navigation/` 은 책임별로 7개 하위 패키지로 나뉘어 있습니다.

```
SafeWalkNav/
├── shared/                                 # ⭐ KMM 공통 모듈 (Android + iOS 공통)
│   └── src/
│       ├── commonMain/.../navigation/
│       │   ├── NavigationManager.kt        # 최상위 오케스트레이터 (1500+ LOC)
│       │   │
│       │   ├── platform/                   # 플랫폼 추상화 (expect/actual + 공통 모델)
│       │   │   ├── Logger.kt               #   expect object Logger
│       │   │   ├── Time.kt                 #   expect fun currentTimeMillis()
│       │   │   └── GpsLocation.kt          #   위치 추상화 (data class)
│       │   │
│       │   ├── geo/                        # 좌표·방위·필터 수학 (순수 함수)
│       │   │   ├── BearingMath.kt          #   bearing / angleDiff / distanceBetween
│       │   │   ├── CrossTrack.kt           #   cross-track error 계산
│       │   │   ├── KalmanHeading.kt        #   Circular Kalman 필터
│       │   │   └── ClockDirection.kt       #   "3시 방향" 시계 안내
│       │   │
│       │   ├── tmap/                       # TMap REST API
│       │   │   ├── TMapApiClient.kt        #   Ktor 기반 호출
│       │   │   ├── TMapRoute.kt            #   TMapRoute / Waypoint / RouteSegment / LatLng
│       │   │   └── POIResult.kt
│       │   │
│       │   ├── route/                      # 경로 위험도·안내 전략
│       │   │   ├── RiskScoreCalculator.kt
│       │   │   ├── SegmentAnalyzer.kt      #   SegmentRisk / DangerLevel
│       │   │   └── GuidanceStrategy.kt     #   위험도별 GuidanceConfig
│       │   │
│       │   ├── signal/                     # 서울 T-data 신호등 API
│       │   │   ├── Trafficapi.kt           #   TrafficApi interface
│       │   │   ├── SignalApiClient.kt
│       │   │   ├── SeoulTrafficSignalLocationApiClient.kt
│       │   │   ├── TrafficIntersectionParser.kt
│       │   │   ├── TrafficLightCountdownService.kt
│       │   │   ├── TrafficSignalLocation.kt
│       │   │   ├── TrafficSignalMatcher.kt
│       │   │   └── TrafficSignalRemainingTimeParser.kt
│       │   │
│       │   ├── walking/                    # 보행자 행동·로깅·상수
│       │   │   ├── WalkingConstants.kt     #   NavigationConstants (임계값 모음)
│       │   │   ├── WalkingDiagnostic.kt    #   쏠림 진단
│       │   │   ├── CrosswalkGuard.kt       #   횡단보도 구간 강화 헬퍼
│       │   │   └── HeadingLogger.kt        #   CSV 로깅 인터페이스
│       │   │
│       │   └── tbfw/                       # TBFW 알고리즘 (자체 완결)
│       │       ├── TrustBasedNavigator.kt  #   Facade
│       │       ├── TrustScoreCalculator.kt
│       │       ├── ForwardOnlyTracker.kt   #   waypoint 통과 판정
│       │       ├── RouteAnnotator.kt       #   경로 곡선/회전 사전 분류
│       │       ├── RouteAnnotationLogger.kt
│       │       ├── PathAnnotation.kt
│       │       ├── MessageBuilder.kt
│       │       ├── NavigatorConfig.kt      #   튜닝 가능한 threshold 묶음
│       │       ├── NavigationResult.kt
│       │       └── UserState.kt
│       │
│       ├── androidMain/.../navigation/     # Android 전용 actual 구현
│       │   ├── platform/Logger.kt          #   actual (android.util.Log)
│       │   ├── Time.android.kt             #   actual (System.currentTimeMillis)
│       │   ├── AndroidHeadingLogger.kt     #   File + BufferedWriter 기반 CSV
│       │   └── LocationConverter.kt        #   Location → GpsLocation 확장
│       │
│       ├── iosMain/.../navigation/         # iOS 전용 actual 구현
│       │   ├── platform/Logger.kt          #   actual (NSLog)
│       │   ├── Time.ios.kt                 #   actual (NSDate.timeIntervalSince1970)
│       │   ├── CLLocationConverter.kt      #   CLLocation → GpsLocation
│       │   └── tbfw/UserStateConverter.ios.kt
│       │
│       └── commonTest/                     # KMP 공통 테스트 (RouteAnnotator 등)
│
├── androidApp/                             # ⭐ 안드로이드 앱
│   ├── libs/                               #   TMap SDK aar (gitignored, 직접 배치)
│   ├── google-services.json                #   Firebase 설정 (gitignored)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/safewalknav/
│       │   ├── MainActivity.kt             #   UI/센서/오디오/TTS/STT 오케스트레이터
│       │   └── location/LocationTracker.kt #   FusedLocationProvider GPS
│       └── res/
│
├── iosApp/                                 # ⭐ iOS 앱 (SwiftUI)
│   └── iosApp/
│       ├── iosAppApp.swift                 #   앱 진입점
│       ├── ContentView.swift
│       ├── Navigation/                     #   메인 내비게이션 화면
│       ├── TBFW/                           #   TBFW 데모/검증 화면 (TBFWDemoView/ViewModel)
│       ├── Map/                            #   지도 표시
│       ├── Location/                       #   CoreLocation 래퍼
│       ├── Sensors/                        #   CoreMotion (heading)
│       ├── Audio/                          #   TTS / 비콘
│       ├── ML/                             #   CoreML (신호등 인식 예정)
│       ├── Traffic/
│       ├── ViewModels/
│       └── *.gpx                           #   시뮬레이터용 더미 경로
│
├── tools/
│   ├── heading_analysis.py                 #   Kalman Before/After 시각화
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
| kotlinx-datetime | 0.5.0 |

**Android 빌드**: Windows / macOS / Linux 어디서든 가능.
**iOS 빌드**: macOS + Xcode 15+ 필수 (Kotlin/Native 컴파일러가 ARM64 framework 생성).

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

### 2. API 키 등록

[TMap 개발자센터](https://tmapapi.tmapmobility.com/)와 [서울 열린데이터광장](https://data.seoul.go.kr/)에서 키 발급 후, 프로젝트 루트의 `local.properties`에 추가:

```properties
TMAP_APP_KEY=발급받은_TMap_앱_키
T_DATA_API_KEY=발급받은_서울_T-data_키
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

## 설치 및 빌드 (iOS)

shared 모듈의 iOS 타겟(`iosX64`, `iosArm64`, `iosSimulatorArm64`)이 활성화되어 있고, iOS 측 actual 구현(`Logger`, `Time`, `CLLocationConverter`, `UserStateConverter`)도 작성되어 있습니다. SwiftUI 앱은 `iosApp/iosApp/` 아래 모듈별로 구성됩니다.

빌드 흐름:

1. macOS + Xcode 15+
2. `./gradlew :shared:assembleSharedDebugXCFramework` 로 KMP framework 생성
3. `iosApp/iosApp.xcodeproj` 열고 실기기/시뮬레이터에서 Run
4. 시뮬레이터에서는 `iosApp/iosApp/*.gpx` (예: `safewalk_demo_v2.gpx`) 를 Debug → Simulate Location 으로 주입해 실시간 경로 검증

상세는 `iosApp/README.md` 참조.

## 테스트

```bash
./gradlew :shared:allTests       # KMP 공통 테스트
./gradlew :shared:testDebugUnitTest
```

`commonTest/.../tbfw/` 아래 `RouteAnnotatorTest`, `TrustBasedNavigatorTest` 등이 알고리즘 핵심을 커버합니다.

## 주요 권한 (Android)

- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS 위치
- `INTERNET`, `ACCESS_NETWORK_STATE` — TMap / 서울 T-data REST API 호출
- `VIBRATE` — 진동 피드백
- `FOREGROUND_SERVICE` — 백그라운드 TTS
- `RECORD_AUDIO` — 음성 인식(STT)
- `CAMERA` — 신호등 인식 (통합 시점에 추가 예정)

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android + 공통), Swift (iOS), Python (분석 도구) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile (KMM, expect/actual 패턴) |
| HTTP | Ktor Client (Android: OkHttp, iOS: Darwin) |
| JSON | kotlinx-serialization (트리 탐색) |
| 비동기 | Kotlin Coroutines + Flow |
| 시간 | kotlinx-datetime (Clock.System) |
| 지도 | TMap SDK (Android), Apple MapKit (iOS) |
| GPS | FusedLocationProvider (Android), CoreLocation (iOS) |
| 센서 | SensorManager (Android), CoreMotion (iOS) |
| TTS/STT | Android `TextToSpeech` / `RecognizerIntent`, AVSpeechSynthesizer / SFSpeechRecognizer (iOS) |
| ML | YOLOv8n + TFLite (Android, 통합 진행 중), CoreML (iOS 예정) |
| 배포 | Firebase App Distribution |
| 분석 | Python(matplotlib, pandas) — Kalman Before/After 시각화 |

## 알고리즘 핵심

- **Circular Kalman Filter** (`geo/KalmanHeading.kt`) — bearing(원형각)을 sin/cos 두 직교 성분으로 분해 후 각 성분에 1D Kalman 적용. 350°/10° 같은 경계 문제 회피. GPS accuracy를 measurement noise로 동적 사용.
- **Forward-Only Waypoint Selection** (`tbfw/ForwardOnlyTracker.kt`) — 한 번 지나간 waypoint는 다시 잡지 않음. GPS 튀김으로 인한 안내 혼선 방지.
- **Trust Score 4단계 분류** (`tbfw/TrustScoreCalculator.kt`) — GPS 정확도(40점) + heading 차(30점) + 보행 속도(20점) → HIGH/MEDIUM/LOW/CRITICAL. 등급에 따라 waypoint 통과 거리 차등(HIGH 8m, MEDIUM 12m).
- **Route Annotation** (`tbfw/RouteAnnotator.kt`) — 경로 waypoint 시퀀스를 사전 스캔해 SHARP_TURN / TURN / CURVE / SLIGHT_CURVE 로 분류하고 도달 거리(15~25m 전)에 맞춰 미리 음성 안내. 임계값은 `NavigatorConfig` 로 튜닝 가능.
- **4단계 도착 판정** — FAR(15m+) / APPROACHING(15m) / NEAR(5m) / ARRIVED(2m). 히스테리시스(NEAR→7m, APPROACHING→18m)로 GPS 흔들림 흡수.
- **Cross-track Error + 횡단보도 강화** (`geo/CrossTrack.kt` + `walking/CrosswalkGuard.kt`) — 경로 선분 대비 수직 이탈 거리(부호 있음)로 측면 드리프트 감지. 횡단보도 구간(다음 wp 30m 이내 ~ 직전 wp 20m 이내)에서는 임계값 강화.

## 팀

- **이도윤(@monggu03)** — Android, 알고리즘, KMM 마이그레이션
- **이지민(@jiminlyy)** — AI(YOLOv8n 학습), iOS

## 라이선스

신호등 모델(YOLOv8n) 통합 시점에 AGPL-3.0 라이선스가 적용됩니다 (Ultralytics YOLO 라이선스 의존).
