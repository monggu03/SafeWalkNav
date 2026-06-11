# SafeWalk — 시각장애인 보행 안전 AI 앱

> **동국대학교 컴퓨터공학과 CSC4004 공개SW프로젝트 — 1조**
>
> 시각장애인이 스마트폰 카메라와 GPS만으로 횡단보도를 안전하게 건널 수 있게 돕는 안드로이드 앱입니다. 음향신호기가 설치되지 않은 횡단보도 구간(전국 약 45.3%)에서 신호등 색상과 방향을 직접 인지할 수 있도록, 자체 설계한 AI 모델과 6-Layer 방어적 안전 설계로 *오안내하기보다 침묵한다* 원칙을 구현했습니다.

| 항목 | 링크 |
|---|---|
| 🎬 시연 영상 | https://youtu.be/FpTKEb9lbQ4 |
| 📦 Android APK | [GitHub Releases v1.0.1 (latest)](https://github.com/monggu03/SafeWalkNav/releases/latest) |
| 📱 지원 OS | Android 8.0 (API 26) 이상 |

---

## 핵심 결과 (capstone 산출)

- **자체 설계 AI 아키텍처** — Ultralytics 가 공식 제공하지 않는 YOLOv11n + P2 Head 하이브리드를 yaml 수준에서 직접 설계
- **Distance Ablation 정량 입증** — 50m급 원거리 시뮬레이션 환경에서 IoU≥0.75 기준 검출률을 baseline 14.0% → hybrid **26.4% (+12.4%p)** 로 향상
- **mAP50 0.947 / 모델 크기 5.6 MB** — Float16 양자화 + TFLite + Android NNAPI 가속
- **6-Layer Defense-in-Depth 안전 설계** — Zone Gating · 학습 데이터 가드 · 신뢰도 임계 · Bounding Box 물리 필터 · 3-Frame 안정성 · 다중 소스 교차 검증
- **GPS Kalman Heading** — sin/cos 분해 기반 Circular Kalman Filter 로 0°/360° 경계 문제 해결
- **TMap 곡선 보완** — 폴리라인 누적 곡률 분석 + 5m 가상 Waypoint 삽입 (Clark-Carter 1987 보행 속도 1.44 m/s 근거)
- **실 시각장애인 외출 테스트 7회 이상** — 동국대 / 마포구청 인근, walk_log + Firebase Analytics 5종 커스텀 이벤트 정량 수집

---

## 빠른 체험 (평가자용)

별도 회원가입·로그인 불필요. 권한 4종(카메라·위치·진동·마이크) 만 허용하면 즉시 실행됩니다.

1. [GitHub Releases v1.0.1](https://github.com/monggu03/SafeWalkNav/releases/latest) 에서 `androidApp-release.apk` 다운로드
2. Android 8.0 이상 기기에 설치 (출처를 알 수 없는 앱 허용 필요)
3. 권한 4종 허용 후 실행
4. TalkBack 활성화 상태에서 평가 권장 (시각장애인용 앱 시나리오)

---

## 핵심 기능

- **횡단보도 신호등 인식 AI** — 50m 원거리 보행 신호등 실시간 검출. 적색·녹색·점멸 구분, R→G 전환 직접 포착 시에만 강한 진동 + 음성 안내
- **시계 방향 카메라 조준 안내** — 서울시 신호등 공공데이터(약 62,690 entry) 기반 *"3시 방향에 카메라를 들어주세요"* 음성 안내로 화면을 볼 수 없는 사용자도 정확히 조준 가능
- **6-Layer 방어적 안전 설계** — 정적 초록불에는 "건너세요" 안내 배제. 최종 판단권은 사용자에게 위임 (AI 단일 실패 지점 회피)
- **TMap 보행자 경로 안내** — Forward-Only Waypoint 추적, 4단계 도착 안내(FAR / APPROACHING / NEAR / ARRIVED)
- **경로 사전 분석** — `RouteAnnotator` 가 경로 전체를 SHARP_TURN / TURN / CURVE / SLIGHT_CURVE 로 사전 분류해 15~30m 전 미리 음성 안내
- **GPS Kalman 보행 쏠림 보정** — heading vs 도로 방위 차이 25° 이상 누적 시 *"약간 오른쪽으로 가세요"* 보정 안내, 8초 cooldown
- **walk_log 자동 진단 + Firebase Analytics** — 평균 보행 속도, 방향 상실 경고, R→G 안내, Flicker 감지 횟수 자동 기록

---

## 프로젝트 구조

KMM (Kotlin Multiplatform Mobile) 멀티 모듈로 비즈니스 로직과 OS 의존 코드를 분리했습니다. `shared/commonMain/navigation/` 은 책임별로 7개 하위 패키지로 구성됩니다.

```
SafeWalkNav/
├── shared/                                 # ⭐ KMM 공통 모듈 (Android + iOS 공통)
│   └── src/
│       ├── commonMain/.../navigation/
│       │   ├── NavigationManager.kt        # 최상위 오케스트레이터 (1500+ LOC)
│       │   │
│       │   ├── platform/                   # 플랫폼 추상화 (expect/actual)
│       │   │   ├── Logger.kt
│       │   │   ├── Time.kt
│       │   │   └── GpsLocation.kt
│       │   │
│       │   ├── geo/                        # 좌표·방위·필터 수학 (순수 함수)
│       │   │   ├── BearingMath.kt          #   bearing / angleDiff / distanceBetween
│       │   │   ├── CrossTrack.kt           #   cross-track error 계산
│       │   │   ├── KalmanHeading.kt        #   ⭐ Circular Kalman Filter
│       │   │   └── ClockDirection.kt       #   "3시 방향" 시계 안내
│       │   │
│       │   ├── tmap/                       # TMap REST API
│       │   │   ├── TMapApiClient.kt        #   Ktor 기반 호출
│       │   │   ├── TMapRoute.kt            #   Route / Waypoint / Segment / LatLng
│       │   │   └── POIResult.kt
│       │   │
│       │   ├── route/                      # 경로 위험도·안내 전략
│       │   │   ├── RiskScoreCalculator.kt
│       │   │   ├── SegmentAnalyzer.kt
│       │   │   └── GuidanceStrategy.kt
│       │   │
│       │   ├── signal/                     # 서울 T-data 신호등 API
│       │   │   ├── SignalApiClient.kt
│       │   │   ├── SeoulTrafficSignalLocationApiClient.kt
│       │   │   ├── TrafficSignalLocation.kt
│       │   │   ├── TrafficSignalMatcher.kt   #   ⭐ 4단계 매칭 알고리즘
│       │   │   ├── TrafficLightCountdownService.kt
│       │   │   └── TrafficSignalRemainingTimeParser.kt
│       │   │
│       │   ├── walking/                    # 보행자 행동·로깅·상수
│       │   │   ├── WalkingConstants.kt
│       │   │   ├── WalkingDiagnostic.kt    #   쏠림 진단
│       │   │   ├── CrosswalkGuard.kt       #   ⭐ 횡단보도 zone 게이팅
│       │   │   └── HeadingLogger.kt
│       │   │
│       │   └── tbfw/                       # TBFW 알고리즘 (Trust-Based Forward Waypoint)
│       │       ├── TrustBasedNavigator.kt
│       │       ├── TrustScoreCalculator.kt
│       │       ├── ForwardOnlyTracker.kt
│       │       ├── RouteAnnotator.kt       #   ⭐ 경로 곡선/회전 사전 분류
│       │       ├── PathAnnotation.kt
│       │       ├── MessageBuilder.kt
│       │       └── NavigatorConfig.kt
│       │
│       ├── androidMain/.../navigation/     # Android 전용 actual 구현
│       ├── iosMain/.../navigation/         # iOS 전용 actual 구현
│       └── commonTest/                     # KMP 공통 테스트
│
├── androidApp/                             # ⭐ 안드로이드 앱
│   ├── libs/                               #   TMap SDK aar (gitignored, 직접 배치)
│   ├── google-services.json                #   Firebase 설정 (gitignored)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/safewalknav/
│       │   ├── MainActivity.kt             #   UI / 센서 / TTS / STT 오케스트레이터
│       │   ├── ml/
│       │   │   ├── TrafficLightDetector.kt #   ⭐ TFLite + NNAPI YOLOv11 추론
│       │   │   └── BoundingBoxOverlay.kt   #   시연용 bbox 시각화
│       │   └── location/LocationTracker.kt
│       └── res/
│
├── iosApp/                                 # ⭐ iOS 앱 (SwiftUI, 화면 구현)
│   └── iosApp/
│       ├── Navigation/                     #   메인 내비게이션 화면
│       ├── TBFW/                           #   TBFW 데모 화면
│       ├── Location/                       #   CoreLocation 래퍼
│       └── ML/                             #   CoreML (서울임팩트 단계에서 통합 예정)
│
├── tools/
│   ├── heading_analysis.py                 #   Kalman Before/After 시각화
│   └── generate_dummy_data.py
│
├── settings.gradle.kts
├── build.gradle.kts                        # 루트 — KMP/AGP 플러그인 선언
├── gradle.properties                       # KMM 옵션 + 메모리 설정
├── local.properties                        # API Key (gitignored)
└── keystore.properties                     # Release 서명 (gitignored)
```

---

## 알고리즘 핵심

- **Circular Kalman Filter** (`geo/KalmanHeading.kt`) — bearing(원형각)을 sin/cos 두 직교 성분으로 분해 후 각 성분에 1D Kalman 적용. 350°/10° 같은 경계 문제 회피. GPS accuracy 를 measurement noise 로 동적 사용
- **TrafficSignalMatcher 4단계 정렬** (`signal/TrafficSignalMatcher.kt`) — (1) GPS 반경 50m 후보 추출 → (2) 전방 90° 우선 → (3) TMap 경로상 횡단보도 거리 가중치 → (4) 반대편 신호등 우선
- **RouteAnnotator** (`tbfw/RouteAnnotator.kt`) — 경로 waypoint 시퀀스를 사전 스캔해 SHARP_TURN / TURN / CURVE / SLIGHT_CURVE 로 분류. 도달 거리(15~30m 전)에 맞춰 미리 음성 안내
- **CrosswalkGuard zone 게이팅** (`walking/CrosswalkGuard.kt`) — TMap 횡단보도 반경 25m 이내 진입 시에만 카메라·ML 활성화 (Layer 1 안전 정책)
- **6-Layer Defense-in-Depth 안전 설계** — Confidence 0.5 임계, Bounding Box 화면비 6% 이상, 3-Frame 안정성 필터, 점멸(Flicker) 감지 시 6초 안내 차단
- **Forward-Only Waypoint Selection** (`tbfw/ForwardOnlyTracker.kt`) — 한 번 지나간 waypoint 는 다시 잡지 않음 (GPS 튀김으로 인한 안내 혼선 방지)

---

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
| TensorFlow Lite | 2.14.0 |
| Room | 2.6.1 |

---

## 설치 및 빌드 (Android)

### 0. 프로젝트 위치 — OneDrive 외부 권장

OneDrive 안에 두면 Gradle build/ 폴더가 동기화되면서 빌드 충돌이 자주 발생합니다. `C:\Dev\SafeWalkNav` 같은 외부 경로에 clone 권장:

```bash
mkdir -p /c/Dev
cd /c/Dev
git clone https://github.com/monggu03/SafeWalkNav.git
cd SafeWalkNav
```

### 1. TMap SDK 다운로드

라이선스 정책상 SDK `.aar` 파일은 저장소에 포함되어 있지 않습니다. [TMap 개발자센터](https://tmapapi.tmapmobility.com/) 에서 직접 받으세요. `androidApp/libs/` 디렉토리에 다음 파일을 배치:

```
androidApp/libs/
├── vsm-tmap-sdk-v2-android-2.0.0.aar
└── tmap-sdk-3.5.aar
```

### 2. API 키 등록

[TMap 개발자센터](https://tmapapi.tmapmobility.com/) 와 [서울 열린데이터광장](https://data.seoul.go.kr/) 에서 키를 발급받아 프로젝트 루트의 `local.properties` 에 추가:

```properties
TMAP_APP_KEY=발급받은_TMap_앱_키
SEOUL_API_KEY=발급받은_서울_공공데이터_키
T_DATA_API_KEY=발급받은_서울_T-data_키
```

`local.properties` 는 `.gitignore` 에 포함되어 커밋되지 않습니다.

### 3. Firebase 설정

[Firebase Console](https://console.firebase.google.com/) 에서 프로젝트의 `google-services.json` 을 받아 `androidApp/` 폴더에 직접 배치합니다. 이 파일도 `.gitignore` 로 처리되어 커밋되지 않습니다.

### 4. Release 빌드 (선택)

Release 빌드를 직접 만들려면 keystore 가 필요합니다. 프로젝트 루트에 `keystore.properties` 작성:

```properties
storeFile=safewalknav-release.keystore
storePassword=...
keyAlias=safewalknav
keyPassword=...
```

`keystore.properties` 가 없으면 빌드는 가능하나 unsigned APK 가 생성되어 설치 시 거부됩니다 (조건부 signing 적용).

### 5. 빌드

Android Studio 에서 프로젝트를 열고 Sync 후 실행. CLI 빌드:

```bash
./gradlew :androidApp:assembleDebug    # Debug 빌드
./gradlew :androidApp:assembleRelease  # Release 빌드 (서명됨)
```

APK 출력 경로:
- Debug: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Release: `androidApp/build/outputs/apk/release/androidApp-release.apk`

---

## 테스트

```bash
./gradlew :shared:allTests
```

`commonTest/.../` 아래 `RouteAnnotatorTest`, `TrustBasedNavigatorTest`, `CrosswalkGuardTest`, `TrafficSignalMatcherTest` 등이 알고리즘 핵심을 커버합니다.

---

## 권한 (Android)

| 권한 | 용도 |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS 위치 추적, 횡단보도 zone 감지 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | TMap / 서울 T-Data REST API |
| `CAMERA` | 신호등 인식 AI 추론 |
| `VIBRATE` | 진동 피드백 (방향 보정, R→G 전환) |
| `RECORD_AUDIO` | STT(흔들기 호출) |
| `FOREGROUND_SERVICE` | 백그라운드 TTS / GPS |

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android + 공통), Swift (iOS), Python (모델 학습·분석) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile (KMM, expect/actual 패턴) |
| AI 모델 | YOLOv11n + P2 Head (자체 yaml 설계, hybrid_P2_head.yaml) |
| ML 런타임 | TFLite 2.14 + Android NNAPI delegate (Float16 양자화 5.6 MB) |
| 학습 환경 | Ultralytics + Roboflow pedestrian-traffic-light v1, Google Colab |
| HTTP | Ktor Client (Android: OkHttp, iOS: Darwin) |
| JSON | kotlinx-serialization |
| 비동기 | Kotlin Coroutines + Flow + StateFlow |
| 시간 | kotlinx-datetime |
| 지도 | TMap SDK (Android), Apple MapKit (iOS) |
| GPS / 센서 | FusedLocationProvider, SensorManager (Android), CoreLocation·CoreMotion (iOS) |
| 데이터 | Room 2.6.1 (신호등 위치 캐시) |
| TTS / STT | Android TextToSpeech / RecognizerIntent |
| 분석·모니터링 | Firebase Analytics (5종 커스텀 이벤트) + 자체 walk_log |
| 배포 | GitHub Releases (signed APK), Firebase App Distribution |

---

## 폐기한 기술 (정직한 의사결정)

본 프로젝트는 정량 검증을 거쳐 폐기한 기술도 함께 보고합니다 (최종 보고서 §2.3.5 참조):

- **Depth Anything V2 단안 깊이 추정** — 줄자 실측 결과 5m 이내에서만 신뢰 가능, 7m 이상에서 51%+ 오차 + 거리 역전 현상. 시속 50km 차량 회피용 인식거리(40~60m) 와 괴리. 추가로 사용자 인터뷰 결과 *위험 탐지보다 내비게이션·신호등이 더 중요* 라는 응답이 일관되어 전면 폐기.
- **IMU heading 기반 방위각** — 정지 상태는 안정적이나 보행 중 재현성 부족 (동일 조건 4회 보행 시 급변율 3.9~18.1% 편차). 자력계 기반 heading 의 본질적 한계. GPS Kalman heading 단일 소스로 일원화.

---

## 후속 프로젝트 — 서울임팩트프로젝트

본 capstone 결과물은 **서울임팩트프로젝트 (2026.04 ~ 2026.10)** 의 사회혁신 파트너 프로젝트로 선정되어 후속 개발이 진행 중입니다.

후속 단계에서는:
- iOS 풀 네이티브 + CoreML 통합으로 **App Store 정식 출시** 목표
- **BLE 음향신호기 광고 수신** (경찰청 표준 사양) + 서울시 T-Data 잔여시간 API 결합
- 한국시각장애인연합회 협력 베타테스터 10명 외출 검증 + PSEQ 자기효능감 측정
- 9월 SOVAC 2026 (코엑스) 부스 시연 + 임팩트 보고서 발간

---

## 팀 — 동국대학교 컴퓨터공학과 1조

| 이름 | 담당 | GitHub |
|---|---|---|
| **이도윤** (팀장) | Android · YOLOv11 모델 설계 · KMM 아키텍처 · 6-Layer 안전 시스템 | [@monggu03](https://github.com/monggu03) |
| 김민성 | Circular Kalman Filter · GPS bearing · accuracy gating | — |
| 김수영 | 서울시 신호등 공공데이터 API · TrafficSignalMatcher · 시각장애인 인터뷰 | — |
| 이지민 | iOS SwiftUI · RouteAnnotator (Waypoint 알고리즘) · Depth/IMU 정량 검증 | [@jiminlyy](https://github.com/jiminlyy) |

담당교수: 석문기 교수님

---

## 라이선스

신호등 모델 학습 시 Ultralytics YOLOv11 의존성으로 **AGPL-3.0** 라이선스가 적용됩니다.

---

## 참고 자료

- 한국시각장애인연합회 (2023). 시각장애인 보행 안전 실태 보고서 — 음향신호기 적정 설치 28.0%, 미설치 45.3%
- 보건복지부 (2023). 장애인 실태조사
- Ultralytics. YOLOv11 Documentation
- Roboflow. Pedestrian Traffic Light Dataset v1
- SK Telecom. TMap REST API 개발자 가이드
- Clark-Carter, D. D., Heyes, A. D., & Howarth, C. I. (1987). *The efficiency and walking speed of visually impaired people*. Ergonomics
