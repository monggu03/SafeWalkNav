# iOS ↔ shared SignalDecisionEngine 배선 노트

> 작성: 2026-07 통일 작업
> 상태: **코드 작성 완료. Mac 빌드 검증 전.**
> 이 배선은 Windows 환경에서 컴파일러 없이 작성되었습니다. 아래는 Mac 빌드 시 확인할 KMP↔Swift 상호운용 가정입니다.

---

## 무엇을 바꿨나

`iosApp/iosApp/ML/TrafficLightDetector.swift` 의 **판정 로직만** 교체했습니다. 카메라·CoreML·미탐지 단계 안내는 그대로입니다.

- 예전: `handleResults` 에서 `confidence >= 0.5` 한 줄 필터 → 라벨별 즉시 발화. 정적 초록에도 "건너세요". 점멸 감지·비대칭·진동 없음.
- 지금: 검출 → `RawSignalDetection` 변환 → `signalEngine.decide(...)` → `SignalDecision` 분기 → TTS·햅틱. **Android `MainActivity.onTrafficLightDetected` 와 동일 구조.**

두 플랫폼이 이제 `shared/SignalDecisionEngine` 하나를 공유합니다 → 안전 동작이 구조적으로 일치.

## 함께 바꾼 것

- `shared/.../SignalDecisionEngine.kt`: **무인자 보조 생성자** `constructor() : this(SignalDecisionConfig())` 추가.
  KMP 는 Kotlin 기본 인자를 Swift 에 노출하지 않으므로, 이게 없으면 Swift 에서 `SignalDecisionEngine()` 를 못 만든다. Android 는 영향 없음.

---

## Mac 빌드 시 확인할 KMP↔Swift 상호운용 가정

빌드가 실패하면 십중팔구 아래 이름 매핑 중 하나다. 각 항목의 "가정"이 실제 생성된 `shared.framework` 헤더와 맞는지 확인할 것.

| # | 항목 | 코드에서 쓴 형태 (가정) | 확인 방법 |
|---|---|---|---|
| 1 | sealed class 하위 타입 | `SignalDecisionAnnounce`, `SignalDecisionRepeat`, `SignalDecisionFlicker`, `SignalDecisionSilent` | Xcode 에서 `SignalDecision` 자동완성. KMP 는 중첩 클래스를 `부모+자식` 으로 평탄화 → 표준 |
| 2 | enum 원본명 접근 | `t.name` / (필요시 `reason.name`) 이 `"RED_NEW"` 등 반환 | Kotlin enum 은 `.name`/`.ordinal` 을 Swift 로 노출 → 표준. **만약 `.name` 이 안 되면** enum 엔트리 직접 비교로 대체: `t == SignalTransition.redNew` |
| 3 | Kotlin `Int` | Swift `Int32` (`classId: Int32`, `decision.color` 비교 `== 0`) | 표준 매핑 |
| 4 | Kotlin `Long` | Swift `Int64` (`nowMs`, `gapMs`) | 표준 매핑 |
| 5 | `RawSignalDetection` 생성자 | `RawSignalDetection(classId:confidence:boxWidth:boxHeight:)` | data class 주 생성자 파라미터명 그대로 |
| 6 | `decide` 시그니처 | `signalEngine.decide(detections: [RawSignalDetection], nowMs: Int64)` | Kotlin `List` 파라미터에 Swift 배열 전달 → 표준 |
| 7 | 무인자 생성자 | `SignalDecisionEngine()` | 위 보조 생성자로 보장 |
| 8 | sealed 분기 | `switch decision { case let a as SignalDecisionAnnounce: ... }` | `as` 캐스팅 → 표준 |

### 만약 enum `.name` 이 문제라면 (플랜 B)
`messageForTransition(_:)` 의 `switch t.name { case "RED_NEW": ... }` 를 아래로 교체:
```swift
if t == SignalTransition.redNew || t == SignalTransition.greenToRed {
    // 빨강 계열
} else if t == SignalTransition.redToGreen {
    // R→G "건너세요"
} else if t == SignalTransition.staticGreen {
    // 정적 초록 "대기"
}
```
(KMP 가 `RED_NEW` → `.redNew` 로 카멜케이스 변환한다는 가정. `.name` 방식이 이 불확실성을 피하려는 의도였음.)

---

## 빌드 후 반드시 실기기 확인 (안전)

배선이 컴파일돼도, **안전 동작이 Android 와 실제로 같은지** 확인해야 한다:

1. **정적 초록에 "건너세요" 안 하는가** — 초록불을 계속 비출 때 "일단 멈춰서 다음 신호를 기다리세요" 만 나와야 함. (예전 iOS 의 가장 위험한 버그였음)
2. **빨강→초록 전환에서만 "건너세요" + 진동** — 빨강 보다가 초록으로 바뀌는 순간에만.
3. **약한 초록(0.45~0.55)엔 침묵** — 애매한 초록에 건너라고 하지 않는가.
4. **점멸 시 경고 + 강한 진동** — "신호가 깜빡입니다".
5. **진동이 실제로 오는가** — iOS 는 원래 진동이 없었음. 햅틱 신규 추가분.

---

## 남은 통일 과제 (이 배선 이후)

- iOS UI(SwiftUI)와 Android UI(View)는 여전히 코드 공유 0% — 수동 정합. (Compose Multiplatform 도입은 큰 재작성이라 별도 판단)
- 안내 문구가 Android(`MainActivity`)·iOS(`messageForTransition`) 양쪽에 하드코딩 — 향후 `SignalTransition` 기반 공유 로컬라이즈 테이블로 통일 가능.
- 미탐지 3단계 안내가 양쪽 하드코딩 중복 — shared 로 옮길 수 있음.
