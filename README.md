# SafeWalk — 시각장애인 보행 안전 AI

스마트폰 카메라로 **보행 신호등 색을 읽어 음성·진동으로 알려주는** 시각장애인용 횡단보도 안전 앱입니다. 음향신호기가 없거나 고장 난 횡단보도에서, 별도 기기 없이 이미 가진 스마트폰만으로 "지금 건너도 되는가"를 스스로 판단할 수 있게 돕습니다.

- **시연 영상**: https://youtu.be/FpTKEb9lbQ4
- **Android APK**: [GitHub Releases](https://github.com/monggu03/SafeWalkNav/releases/latest) (서명됨)

---

## 왜 만들었나

전국 횡단보도 중 음향신호기가 **적정 설치된 곳은 28%**, **미설치가 45.3%** 입니다(한국시각장애인연합회 2023 실태조사). 최근 4년간 고장 신고가 4,451건, 수리까지 최대 184일이 걸린 사례도 있습니다. 음향신호기 1대 설치에 500~800만 원이 들어 지자체가 단기간에 확충하기 어렵습니다.

시각장애인은 그 공백에서 **옆 사람이 건너는 발소리에 의존해 도박처럼 신호를 판단**하거나, 아예 그 횡단보도를 피해 돌아가거나 외출을 포기합니다. SafeWalk는 인프라가 갖춰질 때까지의 **다리** 역할을 목표로, 이미 가진 스마트폰만으로 "횡단보도 그 30초"를 책임집니다.

---

## 핵심 기능

- **보행 신호등 색 인식 (AI)** — 자체 설계 **YOLOv11n + P2 Head** 모델로 `ped_red` / `ped_green` 검출. 원거리(50m급) 검출률을 baseline 대비 **+12.4%p** 향상(mAP50 0.947), Float16 양자화로 모델 5.6 MB.
- **안전 우선 판정 엔진** — 빨강/초록 비대칭 신뢰도(초록 오탐이 더 위험), 연속 프레임 안정화, 점멸 감지·락아웃. 정적 초록에는 "건너세요"를 말하지 않고, **빨강→초록 전환을 직접 포착한 순간에만** 강한 진동으로 알립니다. 이 로직은 Android/iOS 공용 모듈(`shared`)에 있어 두 플랫폼 동작이 일치합니다.
- **횡단보도 Zone Gating** — 횡단보도 25m 이내에서만 카메라·추론을 켜서 배터리·발열 억제.
- **시계 방향 조준 안내** — 카메라를 어디로 향할지 모르는 문제를 `"3시 방향에 카메라를 들어주세요"` 형태로 해결.
- **출발 전 방향 정렬** — 경로 요약 → 평평 자세 → 회전 → 정면 확인 → 출발. 목표 방향에 가까울수록 빨라지는 실시간 스테레오 비프로 멈출 타이밍을 안내.
- **도보 내비게이션** — TMap 보행자 경로 REST API, 4단계 도착 안내(FAR / APPROACHING / NEAR / ARRIVED), 거리 기반 오디오 비콘(스테레오 패닝).
- **신호등 4단계 매칭** — 광폭 도로에서 사용자 바로 앞이 아닌 **반대편 신호등**(20~50m)을 우선 선택.
- **음성 입출력** — 한국어 TTS 안내 / STT 음성 목적지 입력.

---

## 아키텍처

**Kotlin Multiplatform Mobile (KMM)** 구조로, 내비게이션·신호 판정 로직을 Android와 iOS가 공유합니다.

```
SafeWalkNav/
├── shared/          # KMM 공통 모듈 — 두 플랫폼이 공유
│   └── navigation/
│       ├── NavigationManager.kt      # 최상위 오케스트레이터
│       ├── signal/SignalDecisionEngine.kt   # 신호 안전 판정 (순수 로직 + 단위 테스트)
│       ├── geo/      # 좌표·방위·Kalman 필터 (순수 함수)
│       ├── tmap/     # TMap 보행자 경로 REST API
│       ├── signal/   # 서울 신호제어기 API · 신호등 매칭
│       ├── walking/  # 횡단보도 Zone Gating
│       └── tbfw/     # 경로 사전 분석 (곡선/회전 예고)
│
├── androidApp/      # Android 앱 (Kotlin)
│   └── .../safewalknav/
│       ├── MainActivity.kt           # UI·센서·오디오·TTS/STT 오케스트레이터
│       ├── ml/       # TFLite(NNAPI) 신호등 검출 + CameraX
│       ├── onboarding/               # 출발 전 방향 정렬
│       ├── traffic/  # 신호등 위치 로컬 캐시 (Room)
│       └── location/ # FusedLocationProvider
│
├── iosApp/          # iOS 앱 (SwiftUI) — CoreML+Vision 추론, shared 엔진 연동
├── ml_experiments/  # YOLO 학습·평가 스크립트 (gitignored)
└── models/          # 학습된 모델 원본 (gitignored)
```

> **플랫폼 현황**: Android는 전체 흐름(음성 목적지 → 도보 안내 → 신호 인식)이 동작합니다. iOS는 공유 판정 엔진과 카메라 데모가 동작하며, 메인 내비게이션 UI는 진행 중입니다.

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android · 공통), Swift (iOS), Python (ML) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile (expect/actual) |
| ML | YOLOv11n + P2 Head — TFLite Float16 + NNAPI (Android) / CoreML + Vision (iOS) |
| HTTP · 직렬화 | Ktor Client, kotlinx-serialization |
| 비동기 | Coroutines + Flow |
| 로컬 캐시 | Room (Android) / JSON (iOS) |
| 좌표 변환 | proj4j (EPSG:5186 → WGS84) |
| GPS · 센서 | FusedLocationProvider · SensorManager (Android) / CoreLocation · CoreMotion (iOS) |
| TTS · STT | TextToSpeech · SpeechRecognizer (Android) / AVSpeech · SFSpeech (iOS) |

---

## 빌드

| 항목 | 버전 |
|------|------|
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.2.0 · Gradle 8.2 |
| Android SDK | minSdk 26 / targetSdk 34 |
| JDK | 17 |

Android는 Windows/macOS/Linux 어디서나, iOS는 macOS + Xcode 15+ 에서 빌드합니다.

### Android

```bash
git clone https://github.com/monggu03/SafeWalkNav.git
cd SafeWalkNav
```

루트의 `local.properties`에 API 키를 등록합니다([TMap 개발자센터](https://tmapapi.tmapmobility.com/), [서울 열린데이터광장](https://data.seoul.go.kr/)에서 발급):

```properties
TMAP_APP_KEY=발급받은_TMap_앱_키
T_DATA_API_KEY=발급받은_서울_T-data_키
SEOUL_API_KEY=발급받은_서울_열린데이터_키
```

[Firebase Console](https://console.firebase.google.com/)에서 `google-services.json`을 받아 `androidApp/`에 배치합니다. 두 파일 모두 `.gitignore` 처리되어 커밋되지 않습니다.

```bash
./gradlew :androidApp:assembleDebug
# 출력: androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

> `keystore.properties`가 있으면 release 서명이 활성화되고, 없으면 debug 서명으로 빌드됩니다.

### iOS

1. macOS + Xcode 15+
2. `iosApp/iosApp.xcodeproj`를 열고 Run — 빌드 시 `shared` 프레임워크가 자동 생성됩니다.
3. `iosApp/iosApp/Secrets.plist`에 `TMapAppKey` / `TDataApiKey` / `SeoulApiKey` 입력 (gitignored)

### 테스트

```bash
./gradlew :shared:allTests
```

`shared/src/commonTest/`가 신호 판정 엔진과 내비게이션 알고리즘(경로 분석·신호등 매칭·Zone Gating 등)의 핵심을 단위 테스트로 커버합니다.

---

## 권한 (Android)

| 권한 | 용도 |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS 위치 |
| `CAMERA` | 신호등 인식 (횡단보도 25m 이내에서만 활성화) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | TMap · 서울 신호 API |
| `VIBRATE` | 진동 피드백 |
| `FOREGROUND_SERVICE` | 백그라운드 TTS |

---

## 팀

| 이름 | 역할 |
|---|---|
| 이도윤 ([@monggu03](https://github.com/monggu03)) | 팀장 · Android · 알고리즘 · KMM |
| 김민성 | GPS · Kalman Filter |
| 김수영 | iOS |
| 이지민 | AI (YOLOv11n + P2 Head 학습) |

---

## 라이선스

**GNU AGPL-3.0** — 전문은 [`LICENSE`](./LICENSE) 참조. 신호등 인식 모델이 [Ultralytics YOLO](https://github.com/ultralytics/ultralytics)(AGPL-3.0) 기반이고 학습된 가중치가 저장소에 포함되어 있어 AGPL-3.0의 소스 공개 의무가 적용됩니다. 상용·폐쇄 배포에는 Ultralytics 엔터프라이즈 라이선스가 필요합니다.

| 구성요소 | 라이선스 |
|---|---|
| Ultralytics YOLO | AGPL-3.0 |
| TensorFlow Lite · Ktor · kotlinx-serialization · Coroutines · AndroidX · proj4j | Apache-2.0 |
