# SafeWalkNav

시각장애인을 위한 보행 내비게이션 Android 앱. TMap SDK 기반의 경로 안내 + GPS 추적 + TTS/진동 피드백을 제공합니다.

## 빌드 환경

- Android Studio (Giraffe 이상 권장)
- JDK 17
- Android SDK 34, minSdk 26
- Gradle 8.2

## 설치 및 빌드

### 1. TMap SDK 다운로드

이 저장소는 TMap SDK 라이선스 정책상 SDK `.aar` 파일을 포함하지 않습니다. [TMap 개발자센터](https://tmapapi.tmapmobility.com/)에서 직접 받아주세요.

다운받은 파일을 `app/libs/`에 배치:

```
app/libs/
├── vsm-tmap-sdk-v2-android-2.0.0.aar
└── tmap-sdk-3.5.aar
```

### 2. TMap API 키 발급 및 등록

[TMap 개발자센터](https://tmapapi.tmapmobility.com/)에서 앱 키를 발급받은 뒤, 프로젝트 루트의 `local.properties`에 추가합니다:

```properties
TMAP_APP_KEY=발급받은_앱_키
```

> `local.properties`는 `.gitignore`에 포함되어 커밋되지 않습니다.

### 3. 빌드

Android Studio에서 프로젝트를 열고 Sync 후 실행하면 됩니다. CLI 빌드:

```bash
./gradlew assembleDebug
```

## 디렉터리 구조

```
SafeWalkNav/
├── app/
│   ├── libs/                 # TMap SDK aar (직접 배치, gitignored)
│   └── src/main/
│       ├── java/com/example/safewalknav/
│       │   ├── MainActivity.kt
│       │   ├── location/      # GPS 추적
│       │   └── navigation/    # TMap REST API 클라이언트
│       └── AndroidManifest.xml
├── README/                   # 작업 핸드오프 문서
├── build.gradle.kts
└── local.properties          # SDK 경로 + TMAP_APP_KEY (gitignored)
```

## 주요 권한

- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS 위치
- `INTERNET`, `ACCESS_NETWORK_STATE` — TMap REST API 호출
- `VIBRATE` — 진동 피드백
- `FOREGROUND_SERVICE` — TTS 백그라운드 동작
