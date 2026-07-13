# SafeWalk 프로젝트 컨텍스트 (Claude 세션 인수인계용)

> 이 문서는 새 Claude cowork 세션이 SafeWalk 프로젝트의 현재 상태를 빠르게 파악하기 위한 인수인계 문서입니다. 첫 메시지에서 *"이 문서 읽고 시작해줘"* 라고 하면 됩니다.

---

## 1. 프로젝트 정체성

| 항목 | 내용 |
|---|---|
| **이름** | SafeWalk — 시각장애인 보행 안전 AI 앱 |
| **학교·과목** | 동국대학교 컴퓨터공학과 CSC4004 공개SW프로젝트 (2026 1학기) |
| **팀** | 1조 (4명) |
| **팀장** | 이도윤 ([@monggu03](https://github.com/monggu03), 2022111944@dgu.ac.kr) |
| **팀원** | 김민성, 김수영, 이지민 |
| **담당교수** | 석문기 교수님 |
| **작업 폴더** | `C:\Dev\SafeWalkNav` |
| **GitHub** | https://github.com/monggu03/SafeWalkNav |
| **시연 영상** | https://youtu.be/FpTKEb9lbQ4 |
| **Android APK** | https://github.com/monggu03/SafeWalkNav/releases/latest (v1.0.1) |

### 한 줄 정의

> 음향신호기 미설치 횡단보도(전국 45.3%)에서 시각장애인이 스마트폰 카메라와 GPS만으로 신호등 색상을 인지하고 안전하게 횡단할 수 있도록 돕는 안드로이드 앱. 자체 설계 AI(YOLOv11n + P2 Head) + 6-Layer Defense-in-Depth 안전 설계.

---

## 2. 현재 진행 단계 — capstone 완료, 서울임팩트 단독 집중

> **2026-06-15 업데이트:** capstone(공개SW프로젝트) 전 과정 완료(발표 포함). 이제 **서울임팩트프로젝트 단독 트랙**으로 전환. 두 트랙 동시 진행 종료.

### 트랙 A — capstone ✅ 완료 (종료)

- ✅ Android v1.0.1 GitHub Releases 배포 완료 (signed APK, 176MB)
- ✅ 최종 보고서 27페이지 작성·제출
- ✅ 시연 영상 YouTube 미공개 업로드
- ✅ 실 시각장애인 외출 테스트 7회 (마포구청 인근)
- ✅ build.gradle.kts main 머지 완료 (`hasReleaseSigning` 플래그 통합)
- ✅ **발표 완료** — capstone 전 과정 종료

### 트랙 B — 서울임팩트프로젝트 (10월말까지) ← **현재 유일한 포커스**

- 임팩트스퀘어 주관 사회혁신 프로젝트
- 지원금 500만원 확보
- 1차 멘토링 (손예문 매니저) 완료 — 피드백 반영 결정
- 사전 과제 제출 완료, 2차시 강의 + SOVAC 부스 참여 결정 완료
- 7~10월 iOS 출시 + BLE 음향신호기 + 베타테스터 모집 + App Store 출시 목표

### ⚠️ 중요 정정 — iOS 실제 구현 현황 (2026-06-15 코드 점검 결과)

원래 이 문서는 "iOS = capstone 단계 SwiftUI 화면만, 서울임팩트에서 풀 네이티브 신규 개발"이라고
기재했으나, **실제 코드 점검 결과 iOS는 이미 상당 부분 풀 네이티브로 구현되어 동작 중**:

| 영역 | 상태 |
|---|---|
| iOS 코어 (GPS·카메라·CoreML 추론·TTS/STT·네비·온보딩) | ✅ 사실상 완료 |
| CoreML 신호등 모델 변환 (`best.mlpackage`, ~5.8MB) | ✅ 완료 (변환 이미 끝남) |
| T-Data 잔여시간 (shared NavigationManager 경유) | ✅ 연결됨 |
| 신호제어기 위치(서울 API) + 캐시 | ✅ 완료 |
| 6-Layer 안전 (shared 재사용 + iOS ML 에스컬레이션) | 🟡 검증 필요 |
| **BLE 음향신호기 수신 (CoreBluetooth)** | ❌ 미구현 (최대 신규 작업) |
| **BLE 비콘 시범 설치 PoC** | ❌ 미착수 |
| **Firebase Analytics (iOS)** | ❌ 미구현 (Android엔 있음) |
| **App Store 준비 (서명·Connect·프라이버시 라벨·TestFlight)** | ❌ 미착수 |
| **임팩트 측정 (PSEQ + walk_log KPI 집계)** | ❌ 미착수 |
| Mac 확보 (Xcode 빌드·출시 전제) | ⏳ 결정 필요 |

→ **결론:** "iOS 신규 개발"은 사실상 끝났고, 진짜 남은 건 **① BLE(완전 신규) ② App Store 출시
③ 임팩트 측정 ④ Analytics 연결** 4가지. 일정에 여유 발생.

---

## 3. 재정비된 로드맵 (2026-06-15 — iOS 실제 현황 반영)

> 기존 로드맵은 "7~8월 iOS 풀 네이티브 신규 개발"을 가정했으나, iOS 코어가 이미 완성되어
> 있어 **BLE·출시·임팩트 측정 중심으로 재편**. (CoreML 변환·카메라·네비는 이미 done)

| 시기 | 작업 |
|---|---|
| **6월 말 (즉시)** | Mac 확보 결정 → 실기기 빌드 + **이미 된 기능 회귀 테스트** + Apple Developer Program 등록 + App Store Connect 셋업 |
| **7월** | BLE 음향신호기 수신(CoreBluetooth) 개발 + Info.plist 블루투스 권한 / Firebase Analytics iOS 통합 / 6-Layer 안전 동작 검증 |
| **8월** | BLE 비콘 시범 설치 PoC(동국대 인근 20개) / T-Data 잔여시간 + BLE 결합 UX / walk_log → 임팩트 KPI 집계 파이프라인 / 골전도 이어폰 등 하드웨어 조달 |
| **9월** | TestFlight 베타 + 한시련 협력 베타테스터 10명 모집 + PSEQ 사전 설문 + **9/21~22 SOVAC 부스 (코엑스)** |
| **10월** | App Store 정식 출시 + PSEQ 12주 후 설문 + 임팩트 보고서 발표 |

### 가장 시급한 다음 액션
1. **Mac 확보** — 팀원 Mac 공유 여부 확인(이미 iosApp 작업한 사람 있음) → 없으면 중고 Mac mini vs 클라우드 Mac
2. **회귀 테스트** — 이미 구현된 iOS 기능들이 실기기에서 정상 동작하는지 검증 (재개발 아님, 검증)
3. **BLE 음향신호기** — 서울임팩트 최대 신규 작업, 가장 먼저 착수할 개발 항목

---

## 4. 서울임팩트프로젝트 결정사항 (변경 금지)

### 4.1 방향성

**"대한민국의 OKO를 만들자"** — 벨기에 OKO 앱(Ayes Technologies → 2025.07 미국 Polara 인수)의 단순함을 카피하되 *한국 차별화* 를 더함.

### 4.2 플랫폼

- **iOS only 풀 네이티브** (Swift + SwiftUI + AVFoundation + CoreML + CoreBluetooth + MapKit)
- Android 폐기, KMM 폐기, TMap 폐기
- 이유: 한국 시각장애인 다수가 2009 아이폰 + VoiceOver 이래 iPhone 사용

### 4.3 기능 — *횡단보도 그 30초만* 집중

**유지·강화**:
- 횡단보도 신호등 인식 AI (YOLOv11n + P2 Head, CoreML 변환)
- 시계 방향 카메라 조준 안내 ("3시 방향에 카메라를 들어주세요")
- 6-Layer Defense-in-Depth 안전 설계
- walk_log + Firebase Analytics

**신규 추가**:
- BLE 음향신호기 광고 수신 (경찰청 표준)
- 서울시 T-Data 잔여시간 API 결합
- 시범 BLE 비콘 5~10개 설치 PoC (동국대 인근)

**제거**:
- TMap 보행자 경로 (Apple Maps 로 대체)
- GPS Kalman 좌우 보정
- 가상 Waypoint 곡선 보완
- 코너 사전 안내

### 4.4 500만원 예산 분배 (확정)

| 항목 | 금액 |
|---|---|
| iOS 개발 활동비 (4명 × 4개월) | 180만원 |
| Apple Developer Program | 15만원 |
| 한시련 협력 베타테스터 10명 (PSEQ 사례비) | 150만원 |
| 한국 신호등 데이터셋 추가 학습 + CoreML 변환 | 50만원 |
| BLE 비콘 시범 설치 (20개) | 40만원 |
| 골전도 이어폰 (Shokz × 5) | 50만원 |
| SOVAC 부스 운영 | 60만원 |
| 디자인 + 홍보 | 35만원 |
| 예비비 | 20만원 |
| **합계** | **500만원** |

### 4.5 멘토 피드백 (정확히 반영)

- "가격 장벽 해소" 프레임 ❌ → **"일상 결핍 해소"** 프레임 ✅
- 모든 기능 한 번에 ❌ → **선택과 집중** ✅

### 4.6 임팩트 KPI

- **PSEQ 자기효능감 점수 변화** (베타테스터 10명, 사전·12주 후)
- **음향신호기 미설치 구간 안전 통과 횟수** (walk_log `route_has_audio_signal=false`)
- **신호등 인식 정확도** (R→G 전환 정확도 포함, 목표 95%)
- **주간 외출 빈도 증가** (4주 vs 12주 비교)

---

## 5. 폐기한 기술 (혹시 다시 거론될 경우 — 재논의 X)

### 5.1 Depth Anything V2 단안 깊이 추정 (capstone §2.3.5.가)
- 줄자 실측: 7m 34% / 9m 51% / 10m 61% 오차
- 거리 역전 현상 (9m → 4.4m, 10m → 3.9m)
- 시속 50km 차량 회피용 인식거리(40~60m) 와 괴리
- 사용자 인터뷰: 위험 탐지보다 *내비게이션·신호등이 더 중요*
- → **전면 폐기**

### 5.2 IMU heading (capstone §2.3.5.나)
- 정지: 100% 안정, 보행 시 재현성 X (동일 조건 4회 급변율 3.9~18.1%)
- 자력계 기반 heading 의 본질적 한계
- → **GPS Kalman heading 단일 소스로 일원화**

### 5.3 KMM (서울임팩트 단계)
- capstone 단계에서는 사용
- 서울임팩트 단계 = iOS 풀 네이티브로 전환
- → **Swift 답게 깔끔한 코드 우선**

### 5.4 TMap 보행자 경로 (서울임팩트 단계)
- → **Apple Maps + MKDirections 로 대체** (OKO 패턴 카피)

---

## 6. 핵심 파일·위치 가이드

### 6.1 작업 폴더 구조 (`C:\Dev\SafeWalkNav`)

```
SafeWalkNav/
├── androidApp/                            # Android 앱 (capstone 완료)
│   ├── build.gradle.kts                  # signing config (hasReleaseSigning 플래그)
│   └── src/main/java/com/example/safewalknav/
│       ├── MainActivity.kt               # DEMO_MODE=true 토글 적용됨
│       └── ml/
│           ├── TrafficLightDetector.kt   # YOLOv11 + P2 Head TFLite
│           └── BoundingBoxOverlay.kt     # 시연용 bbox (6dp, 18sp)
│
├── shared/                                # KMM 공유 모듈 (capstone)
│   └── src/commonMain/.../navigation/
│       ├── NavigationManager.kt
│       ├── geo/KalmanHeading.kt          # Circular Kalman Filter
│       ├── signal/TrafficSignalMatcher.kt # 4단계 매칭 (반대편 우선)
│       └── walking/CrosswalkGuard.kt     # Zone Gating 25m
│
├── iosApp/                                # iOS (capstone: SwiftUI 화면만)
│                                          # 서울임팩트: 풀 네이티브 신규 개발 예정
│
├── local.properties                       # gitignored — TMAP_APP_KEY, SEOUL_API_KEY, T_DATA_API_KEY
├── keystore.properties                    # gitignored — Release 서명
├── safewalknav-release.keystore           # gitignored — keystore 파일
├── google-services.json                   # gitignored — Firebase 설정 (androidApp/ 안)
├── README.md                              # 프로젝트 소개 (외부 공개용)
└── CONTEXT.md                             # 이 문서
```

### 6.2 capstone 산출물

- 최종 보고서: 27페이지 PDF (제출 완료)
- 시연 영상: https://youtu.be/FpTKEb9lbQ4
- APK: GitHub Releases v1.0.1 (signed, 176MB)

### 6.3 외부 작업물

- **노션**: 팀 협업·회의록 (이도윤 관리)
- **Firebase Console**: SafeWalkNav 프로젝트 (Analytics + App Distribution)
- **GitHub**: monggu03/SafeWalkNav (main 브랜치)

---

## 7. capstone 핵심 정량 결과 (인용용)

- **Distance Ablation**: 50m급 시뮬레이션 IoU≥0.75 기준 검출률 baseline 14.0% → hybrid **26.4% (+12.4%p)**
- **모델 크기**: YOLOv11n + P2 Head, Float16 양자화 **5.6 MB**
- **mAP50**: **0.947** (P2 Head 하이브리드)
- **외출 테스트**: 7회 이상 (마포구청 + 동국대 인근)
- **6/6 walk_log 분석**: 좌우 보정 6회, 코너 안내 6회, R→G 전환 4회, Flicker 감지 2회 등 핵심 기능 발화 검증

---

## 8. 한국 시각장애인 통계 (인용용)

- 음향신호기 적정 설치율 **28.0%** (한시련 2023 실태조사, 6,349개소 대상)
- 음향신호기 미설치율 **45.3%**
- 점자블록 적정 설치율 **4.0%** (7,019개소 대상)
- 최근 4년간 음향신호기 고장·오작동 **4,451건**, 수리까지 최대 **184일**
- 보행자 사망률 OECD 평균의 **3배** (The Diplomat 2021)
- 한국 시각장애인은 2009 아이폰 + VoiceOver 이래 iPhone 다수 사용 (더인디고 김혜일 칼럼)

---

## 9. 협력 가능 한국 기관

1. **한국시각장애인연합회 (한시련)** — 베타테스터 채널, 음향신호기 통계 보유 (capstone 단계 이미 인터뷰 협력)
2. **엘비에스테크 (G-EYE+)** — 한국 보행 내비 선도 (매출 27.4억)
3. **투아트 (설리번플러스)** — 200개국 사용자 AI 시각보조
4. **도로교통공단 / 서울시 T-Data** — 신호 잔여시간 API
5. **한국장애인고용공단 (KEAD)** — 보조공학기기 R&D 12억 사업

---

## 10. 새 cowork 첫 메시지 예시

새 cowork 시작 시 이렇게 말하면 됩니다:

```
이전 cowork 에서 SafeWalk 프로젝트 진행 중이었어. 
C:\Dev\SafeWalkNav 폴더 mount 했고, CONTEXT.md 파일에 
현재 상황·결정사항·다음 작업 다 정리돼 있어. 
그 파일 읽고 시작해줘. 다음에 할 작업은 [구체 작업] 이야.
```

또는:

```
SafeWalk 프로젝트 이어갈 거야. C:\Dev\SafeWalkNav\CONTEXT.md 
읽고 현재 상황 파악해줘.
```

---

## 11. 자주 묻는 질문 (Claude 가 헷갈리는 부분)

**Q. capstone 과 서울임팩트프로젝트는 같은 거야?**
→ 아니. capstone(6/12) = 동국대 졸업프로젝트, 서울임팩트(10월) = 사회혁신 후속 사업. 같은 SafeWalk 앱이지만 *capstone 결과물 그대로 * + *서울임팩트는 iOS 신규 트랙*.

**Q. Android 도 계속 개발해?**
→ 서울임팩트 단계에서는 *iOS 풀 네이티브* 집중. Android v1.0.1 은 *capstone 결과물* 로 유지(개발 중단).

**Q. iOS 는 이제 막 시작이야?**
→ 아니. 2026-06-15 코드 점검 결과 *iOS 코어는 이미 동작* (GPS·CoreML 추론·카메라·TTS/STT·네비·온보딩). "신규 개발"이 아니라 *BLE 추가 + 출시 + 임팩트 측정* 이 남은 일. (§2 정정 표 참조)

**Q. CoreML 변환 아직 안 했어?**
→ 이미 했음. `iosApp/iosApp/ML/best.mlpackage` (~5.8MB) 가 Vision 으로 실시간 추론 중. 추가 최적화(양자화/프루닝)는 선택.

**Q. TMap 계속 써?**
→ capstone 단계는 사용. 서울임팩트 단계는 *Apple Maps + MKDirections* 로 전환 (OKO 카피).

**Q. IMU/Depth Anything V2 다시 시도?**
→ ❌ 폐기 확정. 보고서 §2.3.5 에 정량 검증으로 폐기 근거 다 적혀있음.

**Q. 멘토 피드백 다시 확인?**
→ "가격 → 결핍" 프레임 + "선택과 집중". 다른 거 없음.

---

## 12. 작성 정보

- 작성일: 2026-06-10 (capstone 마감 2일 전)
- **최종 업데이트: 2026-06-15** — capstone 완료·서울임팩트 단독 전환, iOS 실제 현황 정정, 로드맵 재정비 (Claude cowork 세션과 함께)
- 작성자: 이도윤 (이전 Claude cowork 세션과 함께)
- 이 문서는 *gitignored 아님* — 팀원·후배도 봐도 좋은 자료
- 업데이트 시점: 서울임팩트프로젝트 단계 변경·완료 시
