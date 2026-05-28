# 안드로이드 인수인계 — 네비게이션 알고리즘 연동

> 분석 기준: `main` (커밋 `b355ef7`, 2026-05-25)
> 대상 독자: 도윤 (안드로이드 담당)
> 전제: 본문에 적힌 KMM `shared` 모듈은 이미 안드로이드 빌드 대상에 포함돼 있다.

---

## 0. 한 줄 요약

> **`commonMain` 의 `NavigationManager` 를 코루틴에서 호출하고, 몇 개의 `StateFlow` 만 collect 하면 끝.** 가상 waypoint / forward-only / RouteAnnotator 같은 내부 로직은 전부 `shared/commonMain` 에 들어 있고, 안드로이드는 **진입점 연결 + 초기 방향 안내 UX (§5)** 두 가지만 자체 구현하면 된다.

---

## 1. 이미 안드로이드 측에 들어가 있는 것 (= 손댈 필요 없음)

| 파일 | 역할 |
|------|------|
| [shared/androidMain/.../navigation/LocationConverter.kt](../../shared/src/androidMain/kotlin/com/example/safewalknav/navigation/LocationConverter.kt) | `android.location.Location.toGpsLocation()` 확장 함수. 그대로 호출만 하면 됨 |
| [shared/androidMain/.../navigation/AndroidHeadingLogger.kt](../../shared/src/androidMain/kotlin/com/example/safewalknav/navigation/AndroidHeadingLogger.kt) | CSV 헤딩 로거 actual 구현 |
| [shared/androidMain/.../audio/SpatialBeeper.android.kt](../../shared/src/androidMain/kotlin/com/example/safewalknav/audio/SpatialBeeper.android.kt) | 가상 waypoint 통과 시 비프음 — AudioTrack 으로 사인파 합성. 별도 작업 불필요 |
| [shared/androidMain/.../navigation/Time.android.kt](../../shared/src/androidMain/kotlin/com/example/safewalknav/navigation/Time.android.kt) | `currentTimeMillis()` actual |
| [shared/androidMain/.../navigation/platform/Logger.kt](../../shared/src/androidMain/kotlin/com/example/safewalknav/navigation/platform/Logger.kt) | 로그 actual |

→ **새로 작성해야 하는 actual/어댑터는 없다.**

---

## 2. 안드로이드가 해야 하는 일 (5단계)

### 2.1 `NavigationManager` 인스턴스 생성

```kotlin
val navigationManager = NavigationManager(
    tMapApiClient = TMapApiClient(/* TMap API key */),
    signalApiClient = SignalApiClient(/* Seoul signal API key */),
    headingLogger = AndroidHeadingLogger(applicationContext),   // 또는 NoopHeadingLogger
    trafficSignals = emptyList(),  // 시작 시 비워두고 updateTrafficSignals() 로 채움
)
```

### 2.2 GPS 콜백에서 `updateLocation()` 호출

```kotlin
// FusedLocationProviderClient 콜백
val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
        val loc = result.lastLocation ?: return
        lifecycleScope.launch {
            navigationManager.updateLocation(loc.toGpsLocation())
        }
    }
}
```

> `updateLocation` 은 `suspend` 함수. 코루틴 컨텍스트 필요.

### 2.3 음성/UI 출력 연결 (StateFlow collect)

| StateFlow | 타입 | 어디에 쓰나 |
|-----------|------|----------|
| `guidanceMessage` | `StateFlow<String>` | **★ TTS 엔진으로 발화.** 핵심 출력 |
| `arrivalState` | `StateFlow<ArrivalState>` | FAR/APPROACHING/NEAR/ARRIVED 단계 UI |
| `isNavigating` | `StateFlow<Boolean>` | 안내 시작/종료 UI |
| `distanceToDestination` | `StateFlow<Float>` | 실시간 도착지 거리 (m) |
| `isInCrosswalkZone` | `StateFlow<Boolean>` | 횡단보도 진입 게이팅 (예: ML 신호등 검출 ON/OFF) |
| `annotations` | `StateFlow<List<PathAnnotation>>` | 지도 위 곡선/회전 마커. 디버그 패널에도 사용 |
| `announcementLog` | `StateFlow<List<String>>` | 디버그 — 최근 발화 20개 |
| `debugMessage` | `StateFlow<String>` | 디버그 텍스트 (한 줄) |

```kotlin
lifecycleScope.launch {
    navigationManager.guidanceMessage.collect { message ->
        if (message.isNotBlank()) ttsEngine.speak(message)
    }
}
```

> **주의**: `guidanceMessage` 는 같은 메시지가 연속해서 emit 될 수 있다.
> 직접 dedup 하지 말 것 — NavigationManager 내부에서 `forceRepeat` 플래그로 의도적으로 중복 발화하는 케이스가 있음 (도착 단계 반복 안내 등).

### 2.4 경로 시작/종료 호출

```kotlin
// POI 검색
val results = navigationManager.searchDestination(
    keyword = "스타벅스",
    currentLat = userLat,
    currentLon = userLon,
    radiusKm = 1.0f,
)

// 경로 시작 (suspend)
val ok = navigationManager.startNavigation(
    startLat = userLat, startLon = userLon,
    endLat = poi.lat, endLon = poi.lon,
    endName = poi.name,
    frontLat = poi.frontLat, frontLon = poi.frontLon,  // 입구 좌표 (있으면)
    suppressInitialSummary = false,  // 시작 멘트 안 쓸 거면 true
)

// 종료
navigationManager.stopNavigation()
```

### 2.5 (선택) 신호등 데이터 주입

서울시 신호 API 데이터를 가져왔으면 한 번 넘겨주면 NavigationManager 가 횡단보도 zone에서 자동으로 매칭함.

```kotlin
navigationManager.updateTrafficSignals(signalList)
```

---

## 3. 알고리즘 핵심 (안드 담당이 알아야 하는 만큼만)

### 3.1 두 가지 출력 채널

| 채널 | 트리거 | 출처 |
|------|--------|------|
| **음성** (`guidanceMessage`) | annotation 사전 안내, waypoint 도착, 도착 단계 전환, 횡단보도 zone 진입 등 | `NavigationManager.speak()` |
| **스테레오 비프** (`spatialBeeper.playBeep`) | **곡선 구간에 5m 간격으로 삽입된 가상 waypoint** 를 사용자가 통과할 때 | `handleVirtualWaypointPassed()` |

비프는 안드로이드 actual 이 AudioTrack 으로 즉시 동작. **외부에서 콜백을 주입하거나 enable 할 필요 없음.**

### 3.2 사전 분석 (1회) → 진행 추적 (tick마다) 구조

- `startNavigation` 안에서 한 번만 실행: TMap 응답 → `RouteAnnotator.annotateHybrid()` → 곡선/회전 annotation 생성 → `expandWithVirtualWaypoints()` 로 곡선 구간 5m 가상점 삽입
- 매 `updateLocation` 호출마다: forward-only 인덱스 전진 → annotation 발화 후보 선택 → 가상 waypoint 통과 시 비프

### 3.3 Forward-only 인덱스 (절대 외부에서 건드리지 말 것)

`NavigationManager` 내부에 5개의 상태 변수가 분산되어 forward-only 를 구현하고 있다:

- `currentWaypointIndex`, `currentRoutePointIndex` — 절대 뒤로 가지 않음
- `announcedAnnotationIds`, `lastPreAnnouncedIndex`, `lastCrosswalkAnnouncedWpIdx` — 발화 중복 방지

→ **외부에서 리셋하지 말 것.** `stopNavigation()` 호출만 사용.

---

## 4. 튜닝 노브 ([NavigatorConfig](../../shared/src/commonMain/kotlin/com/example/safewalknav/navigation/tbfw/NavigatorConfig.kt))

`NavigationManager` 가 내부적으로 `NavigatorConfig()` 기본값을 사용한다. 안드에서 튜닝하고 싶으면 NavigationManager 생성자/필드를 손대야 하지만, **현재는 기본값으로 충분히 동작 검증된 상태**.

| 그룹 | 주요 값 | 영향 |
|------|--------|------|
| 곡선 분류 | `noiseAngleThresholdDeg=10°`, `turnPeakThresholdDeg=30°`, `curveCumulativeThresholdDeg=30°`, `sharpThresholdDeg=70°` | 어느 각도부터 회전/곡선으로 잡을지 |
| 안내 시점 | `announceDistanceCurveM=15m`, `announceDistanceTurnM=20m`, `announceDistanceSharpM=25m` | 코너 몇 m 전에 미리 발화할지 |
| 가상 waypoint | `virtualWaypointSpacingM=5m`, `curveDeviationLowM=1m`, `curveDeviationHighM=3m`, `curveDeviationCriticalM=5m` | 가상점 간격, 비프 임계값(이상 시 음성으로 자동 전환) |

전체 필드 / 사용처는 [`docs/algorithm/current_navigation_algorithm.md` §6](current_navigation_algorithm.md) 참고.

---

## 5. 초기 방향 안내 UX (안드도 구현 필요) ⭐

iOS 는 `HeadingGuide.swift` 와 `AutoOnboardingCoordinator.swift` 가 이 UX 를 담당.
**안드도 동등한 사용자 경험을 제공해야 한다.** UI/센서 코드는 플랫폼 종속이라 따로 작성해야 하지만, **문구 생성과 기준값은 전부 commonMain 에 있으니 그것을 그대로 호출하면 된다.**

### 5.1 무엇을 구현하나 — 5단계 시퀀스

`AutoOnboardingCoordinator.swift` 참조. 안내 시작 직후 자동 실행되는 온보딩.

| 단계 | TTS 발화 | 종료 조건 | 폴백 |
|------|---------|----------|------|
| **1. summary** | `navigationManager.buildInitialSummary()` 결과 (예: "스타벅스까지 250미터, 약 4분 소요됩니다. 횡단보도 2개. 안내를 시작합니다.") | 4초 경과 후 자동으로 stage 2 로 | — |
| **2. flatPose** | "스마트폰을 평평하게 들어주세요." | gravity 벡터가 평평 자세 조건 충족 (§5.3) | 15초 안에 못 잡으면 "그대로 진행합니다." 발화 후 강제로 stage 3 |
| **3. rotating** | "천천히 한 바퀴 도세요." | trueHeading 이 목표 bearing 의 `±15°` (`initialHeadingToleranceDeg`) 안에 들어옴 | 15초 경과 시 "다시 한번 천천히 도세요." 1회 재시도. 재시도도 실패하면 "정면을 잡지 못했습니다. 그대로 출발합니다." 후 강제 finish |
| **4. confirming** | "방향이 맞습니다. 멈춰주세요." | 일치 상태가 **1초간 유지**됨 | 1초 안에 벗어나면 stage 3 으로 복귀 (회전 멘트는 재발화하지 않음) |
| **5. done** | "정면입니다. 직진하세요." | — | `onCompleted` 콜백 호출 → 정상 안내 시작 |

폴백 상수(iOS 값과 맞출 것):
- `summaryDelaySec = 4.0`
- `confirmHoldSec = 1.0`
- `maxRotatingSec = 15.0`
- `maxFlatPoseSec = 15.0`
- `maxRotationRetries = 1`

### 5.2 별도로 필요한 "출발 후 자세 모니터링" (HeadingGuide 의 v1 모드)

iOS `HeadingGuide.swift` 는 위 온보딩과는 별개로, **출발 후에도 자세가 무너지면 다시 자세 안내를 발화하는 모드**가 있다. 안내 시작 직전에 사용하며, 사용자가 실제로 걷기 시작하면 자동 종료:

- gravity 가 평평하지 않으면 → `MessageBuilder.buildFlatPosePromptMessage()` 발화
- 평평하면 → `MessageBuilder.buildInitialHeadingMessage(diff, tolerance)` 로 회전 안내
- 다음 조건이 만족되면 자동 stop:
  - `GpsLocation.speed >= 0.5 m/s` (분명한 보행) **또는**
  - 시작 지점에서 `>= 3m` 이동 (속도 미보고 디바이스 대비 안전망)

`AutoOnboardingCoordinator` 가 더 풍부한 시퀀스 버전이므로 **둘 중 하나만 골라 안드 구현하면 됨.** 추천은 AutoOnboardingCoordinator 와 동일한 5단계 시퀀스.

### 5.3 평평 자세 판정 (안드 센서 변환 가이드)

iOS 는 `CMDeviceMotion.gravity` 의 (x, y, z) 를 사용. 화면이 하늘을 향한 평평 자세에서 `gravity ≈ (0, 0, -1)` 이 된다. 판정 조건:

```
|gravity.z + 1.0| < flatPoseGravityZTolerance   (= 0.2)
|gravity.x| < flatPoseGravityXYTolerance        (= 0.3)
|gravity.y| < flatPoseGravityXYTolerance        (= 0.3)
```

**안드 매핑**: `SensorManager` 의 `Sensor.TYPE_GRAVITY` 를 그대로 사용 (단위 m/s², 중력 가속도 9.81 으로 정규화 필요). iOS gravity 는 g 단위로 정규화된 값(±1)이므로 안드 값을 9.81 로 나눠 비교하거나, 임계값을 `0.2 * 9.81 ≈ 1.96` 로 환산해서 비교. **부호 주의** — Android `TYPE_GRAVITY` 의 z 축 부호는 디바이스 좌표계 기준 (화면 위쪽 향함 = +z), iOS gravity 와 반대. 즉 안드는 `|gravity.z - 9.81| < 1.96` 같은 형태가 된다 (실측 후 부호 확인 필수).

> 추정컨대 위 부호 환산만으로는 안전하지 않을 수 있으니, **실측 로그로 평평 자세에서 (x, y, z) 가 어떤 값으로 나오는지 먼저 확인**한 뒤 임계 부등식을 맞춰야 함 (확인 필요).

### 5.4 trueHeading (진북 기준 방위각) — 안드 매핑

iOS 는 `CLHeading.trueHeading` 을 그대로 받음 (자북 → 진북 declination 자동 보정). 음수면 보정 필요 신호.

**안드 매핑 권장**:
1. `Sensor.TYPE_ROTATION_VECTOR` 로 디바이스 회전 → `SensorManager.getRotationMatrixFromVector` → `getOrientation` 으로 azimuth (magnetic) 얻기
2. 현재 GPS 위치로 `android.hardware.GeomagneticField` 생성 → `declination` 으로 자북→진북 보정
3. `trueHeading = (magneticAzimuth + declination + 360) % 360`

> `NavigationManager` 내부의 Kalman heading 은 **GPS bearing 기반**(IMU 별도) 이라, 초기 방향 안내용 진북 azimuth 는 위 경로로 별도 계산해야 함.

### 5.5 호출 흐름 (안드 측 의사 코드)

```kotlin
// 안내 시작 직후
val summary = navigationManager.buildInitialSummary()
autoOnboardingCoordinator.start(
    summary = summary,
    currentLocation = currentGpsLocation,
    firstWaypoint = navigationManager.currentRoute!!.waypoints.first(),
    onCompleted = {
        // GPS 콜백 wiring 활성화 → navigationManager.updateLocation(...) 시작
    },
)
```

`startNavigation` 호출 시 `suppressInitialSummary = true` 로 넘기면 NavigationManager 가 요약 멘트 발화를 건너뛴다 (안드 코디네이터가 직접 발화하기 때문). iOS 도 이렇게 동작.

### 5.6 공유 함수/상수 (commonMain — 그대로 호출)

| 항목 | 위치 |
|------|------|
| 회전 안내 문구 | `MessageBuilder.buildInitialHeadingMessage(diffDeg, tolerance)` |
| 자세 안내 문구 | `MessageBuilder.buildFlatPosePromptMessage()` |
| 목표 bearing 계산 | `bearing(lat1, lon1, lat2, lon2)` ([BearingMath.kt](../../shared/src/commonMain/kotlin/com/example/safewalknav/navigation/geo/BearingMath.kt)) |
| 시작 지점에서 멀어진 거리 | `distanceBetween(...)` (동일 파일) |
| 평평 자세 임계값 | `NavigatorConfig.flatPoseGravityZTolerance / XYTolerance` |
| 방향 일치 허용 오차 | `NavigatorConfig.initialHeadingToleranceDeg = 15.0` |

→ **한국어 문구나 임계값을 안드 측에서 새로 작성하지 말 것.** 두 플랫폼이 UX 가 어긋난다.

### 5.7 iOS 전용이라 안드에서 무시해도 되는 것

| iOS 컴포넌트 | 안드 |
|--------------|------|
| `CLLocationConverter` (iOS) | 안드는 `Location.toGpsLocation()` 이미 있음 |
| `TtsManager` (iOS AVSpeechSynthesizer 래퍼) | 안드는 `TextToSpeech` 로 자체 구현 |
| `HeadingDelegate` 패턴 | 안드는 SensorEventListener 로 직접 처리 |

---

## 6. 메모리에 적혀있을 수 있는 옛 클래스 이름들

이전 설계에는 `UserState`, `TrustScoreCalculator`, `ForwardOnlyTracker`, `TrustBasedNavigator`, `NavigationResult` 같은 클래스가 있었다고 메모리에 적혀있을 수 있는데, **전부 코드에 없음**. 2026-05-23 에 폐기됨 (사유: magnetic heading 노이즈로 trust score 실측 부정확).

지금 코드의 매핑은:

| 옛 이름 | 지금 | 위치 |
|---------|------|------|
| TrustBasedNavigator | RouteAnnotator + NavigationManager | tbfw/RouteAnnotator.kt + NavigationManager.kt |
| TrustScoreCalculator | (폐기) | — |
| ForwardOnlyTracker | `syncWaypointIndexForwardOnly()` 메서드 | NavigationManager.kt L1126 |
| UserState | StateFlow 8개로 분산 | NavigationManager.kt L84~143 |
| NavigationResult | StateFlow + AnnotatedRoute | — |

코드 읽을 때 그 이름들로 grep 해도 안 나오니 헷갈리지 말 것.

---

## 7. 빠른 동작 확인 체크리스트

코드 연결 후 첫 실행에서 이것만 확인:

- [ ] `startNavigation` 호출 후 콘솔에 `══════════ [NavManager] 경로 로드 완료 ══════════` 가 찍히는가
- [ ] 그 아래 `=== Route Annotation Log ===` 가 찍히고 `[Annotations]` 섹션에 곡선/회전이 잡혀 있는가 (없으면 RouteAnnotator 가 동작하지 않은 것)
- [ ] GPS 한 번 들어왔을 때 `guidanceMessage` 가 emit 되는가
- [ ] `isNavigating` 이 true 인가
- [ ] 곡선 구간을 지날 때 `[VirtualGen]` 로그가 보이고 비프음이 나는가

문제 생기면 `announcementLog` (최근 발화 20개) 와 `debugMessage` (한 줄 디버그) 를 먼저 확인.

---

## 8. 더 깊이 알고 싶으면

[`current_navigation_algorithm.md`](current_navigation_algorithm.md) — 알고리즘 전체 분석 문서 (10개 섹션, 600줄). 이 인수인계 문서로 막힐 때만 참고.
