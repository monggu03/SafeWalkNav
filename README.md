# SafeWalk — 시각장애인 보행 안전 AI 앱

시각장애인을 위한 **횡단보도 보행 안전 앱**. 음향신호기가 없거나 고장 난 횡단보도에서, 스마트폰 카메라와 GPS만으로 신호등 색을 인식해 음성·진동으로 알려줍니다.

**Kotlin Multiplatform Mobile (KMM)** 으로 Android/iOS가 동일한 내비게이션 로직을 공유합니다.

> 동국대학교 컴퓨터공학과 CSC4004 공개SW프로젝트 — 1조

- **시연 영상**: https://youtu.be/FpTKEb9lbQ4
- **Android APK**: [GitHub Releases](https://github.com/monggu03/SafeWalkNav/releases/latest) (v1.0.1, signed)

---

## 왜 만들었나

전국 횡단보도 중 음향신호기가 **적정 설치된 곳은 28%**, **미설치가 45.3%** 입니다(한국시각장애인연합회 2023 실태조사). 최근 4년간 고장 신고가 4,451건이고 수리까지 최대 184일이 걸린 사례도 있습니다. 음향신호기 1대 설치에 500~800만 원이 들어 지자체가 단기간에 확충하기 어렵습니다.

SafeWalkNav는 **인프라가 갖춰질 때까지의 다리** 역할을 목표로, 시각장애인이 이미 가진 스마트폰만으로 "횡단보도 그 30초"를 책임집니다.

---

## 핵심 기능

- **신호등 색상 인식 (AI)** — 자체 설계 **YOLOv11n + P2 Head** 모델로 `ped_red` / `ped_green` 검출. 원거리(50m급) 검출률을 baseline 대비 **+12.4%p** 향상 (mAP50 0.947).
- **횡단보도 Zone Gating** — 횡단보도 25m 이내에서만 카메라·ML 추론을 켜서 배터리·발열 억제.
- **시계 방향 조준 안내** — 카메라를 어디로 향할지 모르는 문제를 `"3시 방향에 카메라를 들어주세요"` 형태로 해결.
- **출발 전 방향 정렬 온보딩** — 경로 요약 → 평평 자세 → 회전 → 정면 확인 → 출발. 회전 중 목표 방향에 가까울수록 빨라지는 **실시간 스테레오 비프**로 멈출 타이밍을 안내.
- **도보 내비게이션** — TMap 보행자 경로 REST API, 4단계 도착 안내(FAR / APPROACHING / NEAR / ARRIVED).
- **경로 사전 분석** — `RouteAnnotator`가 경로를 곡선/회전/직진으로 사전 분류해 굽은 길을 미리 안내.
- **신호 잔여시간** — 서울 T-data 신호제어기 API 연동 (60초 쿨다운 캐싱 + 경과시간 보정).
- **신호등 4단계 매칭** — 광폭 도로에서 사용자 바로 앞이 아닌 **반대편 신호등**(20~50m)을 우선 선택.
- **음성 안내** — 한국어 TTS / STT(음성 목적지 입력), 거리 기반 오디오 비콘, 스테레오 패닝.

---

## 프로젝트 구조

```
SafeWalkNav/
├── shared/                                  # ⭐ KMM 공통 모듈 (Android + iOS)
│   └── src/commonMain/.../navigation/
│       ├── NavigationManager.kt             # 최상위 오케스트레이터
│       ├── platform/                        # expect/actual 추상화
│       │   ├── Logger.kt  Time.kt  GpsLocation.kt
│       ├── geo/                             # 좌표·방위·필터 수학 (순수 함수)
│       │   ├── BearingMath.kt               #   bearing / angleDiff / distance
│       │   ├── CrossTrack.kt                #   cross-track error
│       │   ├── KalmanHeading.kt             #   Circular Kalman 필터
│       │   ├── RouteBearingProfile.kt
│       │   └── ClockDirection.kt            #   "3시 방향" 시계 안내
│       ├── tmap/                            # TMap 보행자 경로 REST API
│       │   ├── TMapApiClient.kt  TMapRoute.kt  POIResult.kt
│       ├── signal/                          # 서울 T-data 신호등 API
│       │   ├── SignalApiClient.kt
│       │   ├── SeoulTrafficSignalLocationApiClient.kt
│       │   ├── TrafficSignalMatcher.kt      #   4단계 매칭 (반대편 우선)
│       │   ├── TrafficSignalRemainingTimeParser.kt
│       │   └── TrafficIntersectionParser.kt  TrafficSignalLocation.kt
│       ├── walking/
│       │   ├── CrosswalkGuard.kt            #   횡단보도 Zone Gating (25m)
│       │   └── HeadingLogger.kt             #   CSV 로깅 인터페이스
│       ├── route/RiskScoreCalculator.kt
│       ├── audio/SpatialBeeper.kt           # 스테레오 비프 (expect/actual)
│       └── tbfw/                            # 경로 사전 안내
│           ├── RouteAnnotator.kt            #   곡선/회전 사전 분류
│           ├── AnnouncementSelector.kt      #   안내 시점 선택
│           ├── MessageBuilder.kt  PathAnnotation.kt  NavigatorConfig.kt
│
├── androidApp/                              # ⭐ Android 앱
│   └── src/main/java/com/example/safewalknav/
│       ├── MainActivity.kt                  # UI/센서/오디오/TTS/STT 오케스트레이터
│       ├── ml/                              # 신호등 검출
│       │   ├── TrafficLightDetector.kt      #   TFLite (NNAPI) 추론
│       │   ├── TrafficLightAnalyzer.kt      #   CameraX ImageAnalysis
│       │   └── BoundingBoxOverlay.kt        #   시연용 bbox 시각화
│       ├── onboarding/                      # 출발 전 방향 정렬
│       │   ├── AutoOnboardingCoordinator.kt
│       │   ├── HeadingSensor.kt  PoseSensor.kt
│       ├── traffic/                         # 신호등 위치 로컬 캐시 (Room)
│       ├── location/LocationTracker.kt      # FusedLocationProvider
│       └── compass/CompassView.kt
│
├── iosApp/                                  # ⭐ iOS 앱 (SwiftUI) — UI 재설계 중
│   └── iosApp/
│       ├── AppDependencies.swift            # DI 컨테이너
│       ├── ML/TrafficLightDetector.swift    # CoreML + Vision 추론
│       ├── Navigation/AutoOnboardingCoordinator.swift
│       ├── Location/  Sensors/  Audio/  Traffic/  ViewModels/  Debug/
│       └── CameraPreview.swift
│
├── ml_experiments/                          # YOLO 학습·평가 스크립트 (gitignored)
├── models/                                  # 학습된 모델 원본 (gitignored)
├── docs/                                    # 테스트 가이드·알고리즘 문서·설문지
├── settings.gradle.kts                      # :androidApp, :shared
└── local.properties                         # API 키 (gitignored)
```

---

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.2.0 |
| Gradle | 8.2 |
| Android SDK | minSdk 26 / targetSdk 34 / compileSdk 34 |
| JDK | 17 |
| Ktor | 2.3.7 |

- **Android 빌드**: Windows / macOS / Linux 어디서나 가능
- **iOS 빌드**: **macOS + Xcode 15+ 필수** (Kotlin/Native가 ARM64 framework를 생성)

---

## 설치 및 빌드 (Android)

### 1. Clone

OneDrive 안에 두면 Gradle `build/` 동기화 충돌이 자주 발생합니다. **`C:\Dev\SafeWalkNav` 같은 외부 경로** 권장.

```bash
git clone https://github.com/monggu03/SafeWalkNav.git
cd SafeWalkNav
```

> TMap **SDK(.aar)는 필요 없습니다.** 지도 화면을 쓰지 않고 경로 REST API만 사용하므로 SDK 의존을 제거했습니다.

### 2. API 키 등록

[TMap 개발자센터](https://tmapapi.tmapmobility.com/)와 [서울 열린데이터광장](https://data.seoul.go.kr/)에서 키를 발급받아 루트의 `local.properties`에 추가합니다.

```properties
TMAP_APP_KEY=발급받은_TMap_앱_키
SEOUL_API_KEY=발급받은_서울_공공데이터_키
T_DATA_API_KEY=발급받은_서울_T-data_키
SEOUL_API_KEY=발급받은_서울_열린데이터_키
```

`local.properties` 는 `.gitignore` 에 포함되어 커밋되지 않습니다.

### 3. Firebase 설정

[Firebase Console](https://console.firebase.google.com/)에서 `google-services.json`을 받아 **`androidApp/` 폴더에 배치**합니다. 이 파일도 gitignore 처리됩니다.

### 4. Release 빌드 (선택)

```bash
./gradlew :androidApp:assembleDebug
```

출력: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`

> **Release 서명**: `keystore.properties`가 존재할 때만 release signing이 활성화됩니다(`hasReleaseSigning` 플래그). 없으면 debug 서명으로 빌드됩니다.

---

## 설치 및 빌드 (iOS)

1. macOS + Xcode 15+
2. `./gradlew :shared:assembleSharedDebugXCFramework` — KMP framework 생성
3. `iosApp/iosApp.xcodeproj` 열고 실기기/시뮬레이터에서 Run
4. `iosApp/iosApp/Secrets.plist` 생성 후 `TMapAppKey` / `TDataApiKey` / `SeoulApiKey` 입력 (gitignored)
5. 시뮬레이터 위치 재생이 필요하면 `docs/gpx/*.gpx`를 Xcode의 **Debug → Simulate Location**으로 주입

---

## 테스트

```bash
./gradlew :shared:allTests
```

`shared/src/commonTest/` 아래 `RouteAnnotatorTest`, `AnnouncementSelectorTest`, `MessageBuilderTest`, `TrafficSignalMatcherTest`, `CrosswalkGuardTest` 가 알고리즘 핵심을 커버합니다.

---

## 권한 (Android)

| 권한 | 용도 |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS 위치 |
| `CAMERA` | 신호등 인식 (횡단보도 25m 이내에서만 활성화) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | TMap · 서울 T-data REST API |
| `VIBRATE` | 진동 피드백 |
| `FOREGROUND_SERVICE` | 백그라운드 TTS |

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android + 공통), Swift (iOS), Python (ML 실험) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile (expect/actual) |
| HTTP | Ktor Client (Android: OkHttp / iOS: Darwin) |
| 직렬화 | kotlinx-serialization |
| 비동기 | Coroutines + Flow |
| ML | **YOLOv11n + P2 Head** — TFLite Float16 + NNAPI (Android) / CoreML + Vision (iOS) |
| 로컬 캐시 | Room (Android) / JSON 캐시 (iOS) |
| 좌표 변환 | proj4j (EPSG:5186 → WGS84) |
| GPS · 센서 | FusedLocationProvider · SensorManager (Android) / CoreLocation · CoreMotion (iOS) |
| TTS · STT | TextToSpeech · SpeechRecognizer (Android) / AVSpeechSynthesizer · SFSpeechRecognizer (iOS) |
| 배포 | GitHub Releases (signed APK), Firebase App Distribution |

---

---

## 폐기한 기술 (정직한 의사결정)

본 프로젝트는 정량 검증을 거쳐 폐기한 기술도 함께 보고합니다 (최종 보고서 §2.3.5 참조):

- **Depth Anything V2 단안 깊이 추정** — 줄자 실측 결과 5m 이내에서만 신뢰 가능, 7m 이상에서 51%+ 오차 + 거리 역전 현상. 시속 50km 차량 회피용 인식거리(40~60m) 와 괴리. 추가로 사용자 인터뷰 결과 *위험 탐지보다 내비게이션·신호등이 더 중요* 라는 응답이 일관되어 전면 폐기.
- **IMU heading 기반 방위각** — 정지 상태는 안정적이나 보행 중 재현성 부족 (동일 조건 4회 보행 시 급변율 3.9~18.1% 편차). 자력계 기반 heading 의 본질적 한계. GPS Kalman heading 단일 소스로 일원화.

- **YOLOv11n + P2 Head** — 기본 YOLOv11n은 P3/P4/P5에서만 검출해 원거리 신호등(작은 객체)에 약합니다. 고해상도 P2 레벨(stride 4) 검출 헤드를 추가해 원거리 검출률을 baseline 14% → **26.4% (+12.4%p)** 로 끌어올렸습니다(50m급 시뮬레이션, IoU≥0.75). Float16 양자화로 모델 크기 **5.6 MB**.
- **Circular Kalman Filter** (`geo/KalmanHeading.kt`) — bearing(원형각)을 sin/cos 두 성분으로 분해해 각각 1D Kalman을 적용. 359°/0° 경계 문제를 회피하며, GPS accuracy를 measurement noise로 동적 사용합니다.
- **신호등 4단계 매칭** (`signal/TrafficSignalMatcher.kt`) — ①경로 방향 정렬 → ②사용자 10m 이내 신호등 후순위(카메라에 안 잡힘) → ③횡단보도 거리 최소 → ④방위각 차 최소. 광폭 도로(10차선 35~50m)의 반대편 신호등을 놓치지 않습니다.
- **횡단보도 Zone Gating** (`walking/CrosswalkGuard.kt`) — 횡단보도 25m 이내에서만 카메라·추론을 활성화. 상시 구동이 아니므로 배터리·발열 부담이 낮습니다.
- **Route Annotation** (`tbfw/RouteAnnotator.kt`) — 경로를 사전 스캔해 SHARP_TURN / TURN / CURVE / SLIGHT_CURVE로 분류하고 도달 15~25m 전에 미리 안내합니다. 임계값은 `NavigatorConfig`로 튜닝 가능.

### 검증 후 폐기한 접근

정량 검증 결과 기대에 미치지 못해 **의도적으로 제거**한 기능들입니다.

| 폐기 | 이유 |
|---|---|
| **단안 깊이 추정** (Depth Anything V2) | 줄자 실측 오차 7m 34% / 10m 61%, 거리 역전 현상. 차량 회피에 필요한 40~60m 인식과 괴리 |
| **IMU heading 기반 보행 방향 보정** | 정지 시엔 안정적이나 보행 중 재현성 없음(동일 조건 4회 급변율 3.9~18.1%). 자력계 기반 heading의 본질적 한계 |
| **보행 중 좌우 방향 보정** | 흰지팡이 좌우 탐지 보행은 *정상* 보행인데 이탈로 오판정. 발화 폭주 유발 |

> IMU(나침반)는 **신호등 조준용 시계 방향 안내**에만 사용합니다 — 정지 상태의 1회성 방향 판정이라 위 한계에 해당하지 않습니다.

---

## 후속 프로젝트 — 서울임팩트프로젝트

동국대학교 컴퓨터공학과 CSC4004 공개SW프로젝트 1조 (지도: 석문기 교수님)

- **이도윤** ([@monggu03](https://github.com/monggu03)) — 팀장 / Android · 알고리즘 · KMM
- **김민성** — GPS · Kalman Filter
- **김수영** — iOS
- **이지민** — AI (YOLOv11n + P2 Head 학습)

---

## 라이선스

본 프로젝트는 **GNU AGPL-3.0** 라이선스를 따릅니다. 전문은 [`LICENSE`](./LICENSE) 파일을 참조하세요.

신호등 인식 모델이 [Ultralytics YOLO](https://github.com/ultralytics/ultralytics)(AGPL-3.0)를 기반으로 하며, 학습된 모델 가중치가 본 저장소에 포함되어 있으므로 AGPL-3.0의 소스 공개 의무가 적용됩니다.

> 상용·폐쇄 배포를 원하는 경우 Ultralytics의 별도 엔터프라이즈 라이선스가 필요합니다.

### 서드파티 고지

| 구성요소 | 라이선스 |
|---|---|
| Ultralytics YOLO (YOLOv11n + P2 Head) | AGPL-3.0 |
| TensorFlow Lite | Apache-2.0 |
| Ktor · kotlinx-serialization · Coroutines | Apache-2.0 |
| AndroidX (CameraX, Room) | Apache-2.0 |
| proj4j | Apache-2.0 |
