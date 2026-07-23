# SafeWalk — 시각장애인 보행 신호등 인식 AI

스마트폰 카메라로 **보행 신호등 색을 읽어 음성·진동으로 알려주는** 시각장애인용 횡단보도 안전 앱입니다. 앱을 열면 바로 후방 카메라가 켜지고, 신호등을 비추면 "빨간불입니다. 정지하세요" / "방금 초록불로 바뀌었습니다"처럼 안내합니다. 별도 기기 없이 이미 가진 스마트폰만으로 "지금 건너도 되는가"를 스스로 판단할 수 있게 돕습니다.

- **시연 영상**: https://youtu.be/FpTKEb9lbQ4
- **Android APK**: [GitHub Releases](https://github.com/monggu03/SafeWalkNav/releases/latest) (서명됨)

---

## 왜 만들었나

전국 횡단보도 중 음향신호기가 **적정 설치된 곳은 28%**, **미설치가 45.3%** 입니다(한국시각장애인연합회 2023 실태조사). 최근 4년간 고장 신고가 4,451건, 수리까지 최대 184일이 걸린 사례도 있습니다. 음향신호기 1대 설치에 500~800만 원이 들어 지자체가 단기간에 확충하기 어렵습니다.

시각장애인은 그 공백에서 **옆 사람이 건너는 발소리에 의존해 도박처럼 신호를 판단**하거나, 아예 그 횡단보도를 피해 돌아가거나 외출을 포기합니다. SafeWalk는 인프라가 갖춰질 때까지의 **다리** 역할을 목표로, "횡단보도 그 30초"를 스마트폰만으로 책임집니다.

---

## 핵심 기능

- **보행 신호등 색 인식 (AI, 온디바이스)** — 카메라 프레임에서 보행 신호등(빨강/초록)을 폰 안에서 추론합니다. 서버·인터넷·GPS·API 키가 필요 없습니다. Android는 **한국 보행 신호에 특화 학습된 모델**(kairess, 한국 교차로 6,593장)을 사용합니다.
- **안전 우선 판정 엔진** — 빨강/초록 비대칭 신뢰도(초록 오탐이 더 위험), 연속 프레임 안정화, 점멸 감지·락아웃. 정적 초록에는 "건너세요"를 말하지 않고 **빨강→초록 전환을 직접 포착한 순간에만** 강한 진동으로 알립니다. 이 로직은 Android/iOS 공용 모듈(`shared`)에 있어 **두 플랫폼의 안전 판단이 절대 갈라지지 않습니다.**
- **횡단보도 정렬 안내** — 신호등이 안 잡힐 때, 모델이 검출한 횡단보도(줄무늬) 위치로 "횡단보도가 왼쪽에 보입니다. 카메라를 왼쪽으로 조금 돌려 주세요"처럼 조준을 돕습니다.
- **다중 채널 안내** — 한국어 TTS 음성 + 진동 + 전체화면 색 오버레이(저시력자·보호자용).

---

## 아키텍처

**Kotlin Multiplatform Mobile (KMM)** 구조로, **안전 판정 엔진 하나**를 Android와 iOS가 공유합니다. 나머지(카메라·ML 추론·UI)는 각 플랫폼 네이티브로 구현됩니다.

```
SafeWalkNav/
├── shared/          # KMM 공통 모듈
│   └── navigation/signal/
│       └── SignalDecisionEngine.kt   # 신호 안전 판정 (순수 로직 + 단위 테스트)
│                                     # 두 플랫폼이 공유 → 안전 동작 불일치 방지
│
├── androidApp/      # Android 앱 (Kotlin)
│   └── .../safewalknav/
│       ├── MainActivity.kt   # 카메라·검출 결과 → 음성/진동/오버레이
│       └── ml/               # TFLite(NNAPI) 신호등 검출 + CameraX
│
└── iosApp/          # iOS 앱 (SwiftUI) — CoreML + Vision 추론, shared 엔진 연동
```

> **설계 의도**: 공유 코드를 "많이"가 아니라 "옳게" 두었습니다. 안전상 절대 갈라지면 안 되는 신호 판정만 `shared`에 두고, 플랫폼 SDK에 밀착된 카메라·ML·UI는 각자 네이티브로 둡니다.

> **플랫폼 현황**: Android는 카메라 신호 인식 전체 흐름이 동작하며 한국 특화 모델(kairess)을 씁니다. iOS는 동일한 카메라-인식 구조로 동작하며, 한국 특화 모델의 CoreML 변환 적용이 진행 중입니다.

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin (Android · 공통), Swift (iOS), Python (ML 변환) |
| 멀티플랫폼 | Kotlin Multiplatform Mobile — 안전 판정 엔진 공유 |
| ML | 온디바이스 추론 — TFLite Float16 + NNAPI (Android) / CoreML + Vision (iOS) |
| 카메라 | CameraX (Android) / AVFoundation (iOS) |
| 안내 출력 | TextToSpeech · Vibrator (Android) / AVSpeech · CoreHaptics (iOS) |
| 접근성 | TalkBack / VoiceOver, 첫 실행 안전 고지 게이트 |

---

## 빌드

| 항목 | 버전 |
|------|------|
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.2.0 · Gradle 8.2 |
| Android SDK | minSdk 26 / targetSdk 34 |
| JDK | 17 |

카메라 인식만 사용하므로 **외부 API 키가 필요 없습니다.**

### Android

```bash
git clone https://github.com/monggu03/SafeWalkNav.git
cd SafeWalkNav
./gradlew :androidApp:assembleDebug
# 출력: androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

한국 특화 신호등 모델(`crosswalk_kairess.tflite`)이 `androidApp/src/main/assets/`에 포함되어 있습니다. `keystore.properties`가 있으면 release 서명이 활성화되고, 없으면 debug 서명으로 빌드됩니다.

### iOS

1. macOS + Xcode 15+
2. `iosApp/iosApp.xcodeproj`를 열고 Run — 빌드 시 `shared` 프레임워크가 자동 생성됩니다.

### 테스트

```bash
./gradlew :shared:allTests
```

`shared/src/commonTest/`가 신호 판정 엔진(비대칭 신뢰도·안정성·점멸 락아웃·색 확정)을 단위 테스트로 커버합니다.

---

## 권한 (Android)

| 권한 | 용도 |
|---|---|
| `CAMERA` | 신호등 인식 |
| `VIBRATE` | 진동 피드백 |

---

## 팀

| 이름 | 역할 |
|---|---|
| 이도윤 ([@monggu03](https://github.com/monggu03)) | 팀장 · Android · 신호 판정 알고리즘 · KMM |
| 김민성 | 좌표·필터 알고리즘 |
| 김수영 | iOS |
| 이지민 | AI 모델 |

---

## 라이선스

**GNU AGPL-3.0** — 전문은 [`LICENSE`](./LICENSE) 참조. 신호등 인식 모델이 [kairess/crosswalk-traffic-light-detection-yolov5](https://github.com/kairess/crosswalk-traffic-light-detection-yolov5)(YOLOv5, GPL-3.0) 기반이라 소스 공개 의무가 적용됩니다.

| 구성요소 | 라이선스 |
|---|---|
| kairess 신호등 모델 (YOLOv5) | GPL-3.0 |
| TensorFlow Lite · Coroutines · AndroidX | Apache-2.0 |
