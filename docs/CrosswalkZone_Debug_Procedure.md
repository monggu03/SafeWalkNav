# 횡단보도 Zone 감지 버그 — 진단 절차

> 작성: 2026-05-26  
> 증상: 횡단보도 근처로 가도 `횡단보도=false` 로 떠서 신호등 ML 모델이 안 켜짐.  
> 목적: 어떤 가설 (1~5) 이 진짜 root cause 인지 실기기 로그로 확정.

---

## 1. 빌드 & 설치

```powershell
cd C:\Dev\SafeWalkNav
./gradlew :androidApp:installDebug
```

설치 후 폰에서 앱을 실행.

---

## 2. 외출 — 횡단보도 1개 이상 포함된 경로 안내

1. 출발지 / 도착지 검색 → **NAVIGATING** 진입
2. 자연스럽게 걷기 (단, **횡단보도를 최소 1개 통과**하는 경로여야 함)
3. 횡단보도 코앞 (10m 이내) 에 5초 이상 서있기
4. 횡단보도를 건넌 직후에도 5초 이상 서있기
5. 도착 또는 음성 "종료" 명령으로 안내 종료

> 도중에 화면 우상단 디버그 텍스트도 캡처해두시면 좋아요 (사진 1~2장).

---

## 3. 로그 파일 위치

로그는 외장 저장소에 저장됩니다:

```
/sdcard/Android/data/com.example.safewalknav/files/walk_logs/walk_<날짜시간>.log
```

각 외출마다 새 파일이 만들어집니다 (예: `walk_20260526_141023.log`).

---

## 4. 로그 파일 받기 — 3가지 방법

### 방법 A: adb pull (가장 빠름, 권장)

PC 에 폰을 USB 연결한 상태에서:

```powershell
# 1. 가장 최근 로그 파일명 확인
adb shell ls -t /sdcard/Android/data/com.example.safewalknav/files/walk_logs/

# 2. 가장 최근 파일 받기 (파일명은 위 ls 결과로 교체)
adb pull /sdcard/Android/data/com.example.safewalknav/files/walk_logs/walk_20260526_141023.log C:\Dev\SafeWalkNav\debug_logs\

# 3. 또는 전체 폴더 통째로 받기
adb pull /sdcard/Android/data/com.example.safewalknav/files/walk_logs C:\Dev\SafeWalkNav\debug_logs\
```

### 방법 B: 폰에서 직접 (Files / 내 파일)

1. 폰 **Files (내 파일)** 앱 열기
2. **내장 메모리 → Android → data → com.example.safewalknav → files → walk_logs**
3. 가장 최근 `walk_*.log` 파일 → **공유** → 카카오톡/이메일/Drive 로 전송

> ⚠️ Android 11+ 부터 `Android/data` 폴더가 일반 파일 앱에서 안 보일 수 있음. 그땐 방법 A 사용.

### 방법 C: USB 케이블로 파일 탐색기에서 복사

1. 폰을 PC 에 USB 연결 → **파일 전송 (MTP) 모드** 선택
2. `내 PC → 폰이름 → 내부저장공간 → Android → data → com.example.safewalknav → files → walk_logs`
3. 파일 복사

---

## 5. 로그 보낼 때 같이 알려주세요

분석에 도움 되는 추가 정보:

- [ ] **로그 파일** (`walk_*.log`)
- [ ] **출발지 / 도착지** (예: "테헤란로 233 → 강남역 11번 출구")
- [ ] **횡단보도 통과 횟수** (걸으면서 몇 개 건넜는지)
- [ ] **각 횡단보도 앞에서 음성 안내가 나왔는지** ("빨간불입니다" 등)
- [ ] **디버그 텍스트 화면 캡처 1~2장** (횡단보도 근처에서)

---

## 6. 로그에서 확인할 부분 (Claude 가 분석)

로그에는 다음 정보가 들어있어요:

### 시작 시 한 번 — 경로 dump
```
[14:10:23.000] === SafeWalkNav 외출 로그 시작 ...
[14:10:25.123] 경로 로드: 28개 waypoint (CROSSWALK 3개, 총 850m)
[14:10:25.124] 🚦 [5] type=CROSSWALK turn=211 road=0 dist=120 desc=횡단보도
[14:10:25.125]    [6] type=TURN turn=2 road=0 dist=150 desc=우회전
...
```

- **CROSSWALK 0개** 면 → 가설 2 (TMap sparse) 확정
- **CROSSWALK N개** 이면 → 다음 GPS_TICK 분석으로

### GPS 매 2초마다 — 위치 + 디버그
```
[14:11:05.234] GPS_TICK lat=37.50012 lon=127.03512 ±8m spd=1.2m/s dest=420m | 횡단보도=false | idx=4/28 | wp=WAYPOINT | seg='테헤란로' road=NORMAL risk=NORMAL | turnType=0 | desc= | nearestXW=idx5 dist=87m (total 3개)
```

- `nearestXW=idx5 dist=87m` → 87m 떨어진 5번째 waypoint 가 가장 가까운 횡단보도
- 거리가 50m 이하로 떨어졌는데도 `횡단보도=false` 면 → 다른 가설 의심
- `nearestXW=없음` 이면 → 가설 2 확정

### 횡단보도 진입 시
```
[14:11:42.567] Crosswalk zone ENTER — TL 안내 활성화
```

이 줄이 안 보이면 → zone 진입 자체가 한 번도 안 됐다는 뜻.

### 신호등 검출 시
```
[14:11:45.890] TL announced: 빨간불입니다. 정지하세요. (conf=0.92, box=0.18x0.24, total 1 det)
```

---

## 7. 가설별 분석 매트릭스

| 로그 증상 | 의심 가설 | 다음 조치 |
|----------|----------|----------|
| `CROSSWALK 0개` (시작 dump) | 가설 2 | TMap API 호출 파라미터 점검 |
| `nearestXW=없음` 줄곧 | 가설 2 | 위와 동일 |
| `nearestXW dist≤50m` 인데 `횡단보도=false` | 가설 1 또는 4 | classifyPointType 수정본 반영 확인 |
| `idx` 가 GPS tick 마다 안 변함 | 가설 5 | syncWaypointIndexForwardOnly 디버깅 |
| `dist=80~120m` 인데 횡단보도 코앞 | GPS 정확도 / waypoint 위치 부정확 | TMap 응답 좌표 정확도 점검 |
| `Crosswalk zone ENTER` 한 번도 없음 | 위 모든 가설 가능 | GPS_TICK 로그 분석 |

---

## TL;DR

1. **빌드 + 설치** → `./gradlew :androidApp:installDebug`
2. **횡단보도 있는 경로로 안내 시작 + 걷기**
3. **로그 받기** → `adb pull /sdcard/Android/data/com.example.safewalknav/files/walk_logs C:\Dev\SafeWalkNav\debug_logs\`
4. **로그 파일을 Claude 에게 보여주기**

로그만 있으면 root cause 가 5가지 가설 중 어느 것인지 확정할 수 있어요.
