# 신호등 모델 (Hybrid v2.1) — 실기기 테스트 가이드

> 마지막 업데이트: 2026-05-24  
> 모델 통합 PR 이후 시각장애인 보행 시나리오에서 빨강/초록 검출이 안정적으로 동작하는지 검증.

---

## 1. 빌드 & 설치

### 1-1. 모델 파일 위치 (이미 들어가 있음)
```
androidApp/src/main/assets/
└── safewalknav_tl.tflite       ← Hybrid v2.1 (5.6 MB, float16 양자화)
```
이전 YOLOv8n 모델 (`pedestrian_tl.tflite`) 은 APK 용량 절감 위해 제거됨.

**모델 핵심 정보**:
- 아키텍처: YOLOv11n backbone + P2 head (자체 설계 yolo11-p2.yaml)
- mAP50: 0.9476
- 양자화: float16 (float32 대비 절반 크기, 정확도 손실 < 0.5%p)
- Output: `[1, 6, 34000]` — P2 head 포함 anchor 수 (160² + 80² + 40² + 20²)

### 1-2. 빌드 명령
```bash
# 디버그 APK
./gradlew :androidApp:assembleDebug

# 실기기 설치 (USB 디버깅 ON)
./gradlew :androidApp:installDebug
```

### 1-3. 로그 모니터링
```bash
adb logcat -s SafeWalkNav TrafficLightDetector TrafficLightAnalyzer
```
첫 실행 시 다음과 같은 로그가 떠야 정상:
```
TrafficLightDetector: Model loaded: safewalknav_tl.tflite (NNAPI on, 4 threads CPU fallback)
TrafficLightDetector: Input shape: [1, 640, 640, 3]
TrafficLightDetector: Output shape: [1, 6, 34000]
```
**Output shape 의 34000** 은 P2 head 포함의 증거 — 8400 이 뜨면 모델이 잘못 들어간 것.

---

## 2. 동작 조건 (중요)

신호등 검출은 **횡단보도 zone 안에서만** 동작합니다 (배터리 절약).
- `inCrosswalkZone == true` 일 때만 ML 추론 수행
- 일반 보행 중에는 카메라 분석 자체가 스킵됨

따라서 단순히 신호등 앞에 서있다고 인식하는 게 아니라, **경로 안내 중 횡단보도 waypoint 에 진입한 상태에서만** 동작합니다.

테스트 전 확인:
- [ ] 출발지/도착지 검색 후 NAVIGATING 상태 진입
- [ ] 화면 우상단 디버그 텍스트에 횡단보도 zone 진입 표시 확인
- [ ] 카메라 미리보기가 화면에 표시되는지 확인 (PR-UX2)

---

## 3. 정확도 테스트 시나리오

### 3-1. 기본 시나리오 (필수)
| # | 상황 | 기대 동작 | 합격 기준 |
|---|------|----------|----------|
| 1 | 빨간불 (정면) | "빨간불입니다. 정지하세요." TTS + 짧은 진동 | 3초 이내 발화 |
| 2 | 초록불 (정면) | "초록불입니다." TTS + 짧은 진동 | 3초 이내 발화 |
| 3 | 빨강 → 초록 전환 | 즉시 "초록불입니다." 발화 + 진동 | cooldown 무시하고 즉시 |
| 4 | 같은 색 5초 이상 유지 | 5초마다 1회만 재발화 | 과도한 반복 X |
| 5 | 횡단보도 zone 밖 | 아무 발화 없음 | 추론 자체 안 함 |

### 3-2. 엣지 케이스 (안전 핵심)
| # | 상황 | 기대 동작 | 합격 기준 |
|---|------|----------|----------|
| 6 | 빨간불을 초록으로 잘못 안내 | **절대 발생 X** | 0건 — 안전 critical |
| 7 | 초록불을 빨강으로 잘못 안내 | 발생 가능 (안전한 false alarm) | 1% 미만 권장 |
| 8 | 멀리 있는 빨간 점/간판 noise | 안내 X | 6% box 필터로 차단됨 |
| 9 | 측면 신호등 (옆 차로) | 안내 가능 (보행자 시점이라면) | 정면 우선 |
| 10 | 검출 실패 (역광/안개) | 안내 없음 (false positive 보다 안전) | 침묵 = OK |

### 3-3. 환경별 테스트
- [ ] 주간 (맑음)
- [ ] 주간 (흐림)
- [ ] 일몰/일출 (역광)
- [ ] 야간
- [ ] 비 오는 날
- [ ] 카메라 흔들림 (걸을 때 자연스러운 motion blur)

---

## 4. 성능 측정

### 4-1. 추론 속도 (디버그 로그)
`TrafficLightDetector.detect()` 호출 전후 시간 측정:

```kotlin
val t0 = System.nanoTime()
val detections = detector.detect(bitmap)
val ms = (System.nanoTime() - t0) / 1_000_000
Log.d("TL_PERF", "inference=${ms}ms detections=${detections.size}")
```

기대 성능 (모바일 디바이스 기준):
| 디바이스 | NNAPI | CPU only |
|---------|-------|----------|
| Pixel 6+ (Tensor) | 3-8 ms | 15-25 ms |
| 갤럭시 S22+ | 5-12 ms | 20-30 ms |
| 중급 (4 GB RAM) | 10-20 ms | 30-50 ms |
| 저사양 | 20-40 ms | 50-80 ms |

**한계선**: 30 ms 초과 시 frame skip 간격 (`333ms`) 늘려야 할 수도 있음.

### 4-2. 배터리 영향
30 분간 NAVIGATING 모드로 횡단보도 통과 5회 시:
- 배터리 소모 < 8% 권장
- 발열 < 40°C 권장

---

## 5. 트러블슈팅

### 증상 → 원인 → 대응

**"Model loaded" 로그가 안 뜸**
- 원인: `assets/safewalknav_tl.tflite` 누락 또는 빌드 실패
- 대응: `./gradlew clean assembleDebug` 재빌드, APK 안에 .tflite 들어갔는지 확인 (`unzip -l app-debug.apk | grep tflite`)

**NNAPI crash (특정 디바이스)**
- 원인: 일부 OEM (구형 갤럭시 등) NNAPI 드라이버 버그
- 대응: `TrafficLightDetector.kt` 의 `setUseNNAPI(true)` → `setUseNNAPI(false)` 로 변경 후 CPU only 로 폴백

**추론 결과가 항상 빈 리스트**
- 원인 1: input shape 불일치 (모델 input != 640x640)
- 원인 2: confidence threshold 너무 높음 (`0.5f` 기본)
- 대응: 디버그 모드로 threshold 0.3 로 낮춰서 출력 보기 → 정상이면 임계값만 조정

**발화가 너무 느림 (5초 이상 지연)**
- 원인: frame skipping 333ms × 여러 프레임 분석 실패
- 대응: 카메라 노출/대비 환경 확인, 햇빛 방향 조정

**같은 색이 반복 발화됨**
- 원인: `SIGNAL_SPEAK_INTERVAL_MS` (5000ms) 또는 `lastSpokenSignalColor` 로직 버그
- 대응: 로그에 `TL announced` 와 `TL (cooldown)` 비율 확인

---

## 6. 검증 체크리스트 (실배포 전)

- [ ] 빌드 성공, 디바이스 설치 OK
- [ ] 첫 실행 로그에 "Model loaded: safewalknav_tl.tflite" 출력
- [ ] 횡단보도 zone 진입 시 카메라 미리보기 표시
- [ ] 시나리오 1~5 (기본) 전부 PASS
- [ ] 시나리오 6 (빨강→초록 오분류) **0건**
- [ ] 주간/야간/우천 환경 중 최소 2개 환경 테스트
- [ ] 추론 속도 < 30 ms (NNAPI on)
- [ ] 30분 사용 시 배터리 < 8%, 발열 < 40°C

---

## 7. 다음 단계 (별도 PR)

- [ ] CITS API 연동 — 잔여시간 안내 ("3초 후 빨간불")
- [ ] iOS CoreML 모델 통합 (best_mlpackage.zip)
- [ ] 거리 추정 (bbox 크기 → 신호등까지 거리)
- [ ] 다중 신호등 처리 (현재는 nearest 1개만 안내)
