# SafeWalkNav — shared 모듈 진단 (2026-08-11)

> 진단 전용(read-only). 코드·빌드 설정 변경 없음. 모든 판정에 `파일:라인` 근거를 붙였다.
> API 키/토큰의 실제 값은 이 보고서에 옮기지 않았다.
> 1차 진단(`readiness_report.md`)이 남긴 미결 3건(전송처·KMM 규모·아카이브 링크)을 `shared/` 기준으로 보강한다.

---

## 0. 종합

- **개인정보처리방침에 써야 할 외부 전송처**: SK open API 1곳뿐 — `apis.openapi.sk.com` (TMap). 그 외 서버 없음. (§A)
  - 서울시(`seoul.go.kr`) 호출 **없음**. 분석/크래시/텔레메트리 SDK **없음**.
- **HTTP(비암호화) 통신 존재 여부**: **아니오.** shared 의 모든 외부 요청은 `https://` 단일 상수(`baseUrl`)에서 파생된다. (§A)
- **TMap 24시간 저장 제약 위반 소지**: **아니오 (shared 기준).** shared 는 TMap 응답을 디스크·DB·영구 캐시 어디에도 저장하지 않는다. 메모리 캐시 필드조차 없다 — 응답을 파싱해 반환하고 끝. (§C)
  - iOS 앱 쪽에도 좌표/경로를 **디스크에 남기는 활성 경로는 없음**(로거 인프라는 존재하나 dormant). 상세는 §3 C4.
- **KMM 제거 규모**: iOS 가 의존하는 shared 코드는 **4개 파일 / 약 1,042줄**(shared commonMain 1,072줄의 ~97%). Swift 재작성 시 대략 **6개 iOS 파일이 참조를 끊고, 신규 Swift 약 750~800줄**로 대체(이전 심볼 조사 기준). 단, `SignalDecisionEngine` 계열은 **Android 와 공유되는 안전 알고리즘 본체**라 Swift 이전 시 로직이 이원화된다. (§B) — *권고 아님, 사실만.*
- **아카이브 링크 주체**: Xcode 의 Frameworks(link)/Embed 단계는 `platformFilters = (xros,)` 로 iOS 에서 꺼져 있고, **대신 `Compile Kotlin Framework` 스크립트가 `:shared:embedAndSignAppleFrameworkForXcode` 를 돌려 프레임워크를 만들고, `FRAMEWORK_SEARCH_PATHS`(Debug/Release 양쪽 존재) 가 그걸 찾아 정적 링크**한다. `isStatic=true` 라 런타임 임베드 대상이 아니다(=.app 에 shared.framework 없음이 정상). **단, Release/기기 아카이브 실물이 없어 최종 링크 성공은 확인 불가.** (§E)

---

## 1. 네트워크 전송 실태표 (§A)

**공통 사실**
- 모든 요청의 베이스: `private val baseUrl = "https://apis.openapi.sk.com/tmap"` — `TMapApiClient.kt:49`. **HTTPS 단일 상수.** `http://` 리터럴 없음(§A3).
- 모든 요청 인증: `headers { append("appKey", appKey) }` — 헤더명 `appKey`, 값은 **TMap 앱 키**(생성자 주입 `TMapApiClient.kt:42`, Android 는 `BuildConfig.TMAP_APP_KEY`). 실제 값은 이 보고서에 없음(§A5).
- HttpClient 는 shared 내부 생성(`TMapApiClient.kt:51`), 타임아웃 10초. 외부 서버는 위 1곳뿐(§A7).
- 서울시 API 호출 코드 **없음**(§A6 — 전 소스 grep `seoul.go.kr` 0건).

| 호출 지점 (파일:라인) | 호스트 | 프로토콜 | 전송되는 사용자 데이터 | 목적 |
|---|---|---|---|---|
| `TMapApiClient.kt:76` `POST $baseUrl/routes/pedestrian` | apis.openapi.sk.com | HTTPS | **출발 위/경도**(`startX/startY`), **목적지 위/경도**(`endX/endY`), 출발/목적지 이름(`startName/endName`, 기본 "출발지"/"목적지"), 좌표계 WGS84 (`body` L83-90) | 보행자 경로 탐색 |
| `TMapApiClient.kt:137` `GET $baseUrl/pois` | apis.openapi.sk.com | HTTPS | **검색어**(`searchKeyword`), 결과 수(`count`), **현재 위/경도**(`centerLat/centerLon`, 위치 있을 때만 L143-146) | 목적지 POI 키워드 검색 |
| `TMapApiClient.kt:211` `GET $baseUrl/geo/reversegeocoding` | apis.openapi.sk.com | HTTPS | **위/경도**(`lat/lon`), 좌표계/주소타입 | 역지오코딩(좌표→주소, 랜드마크 안내) |
| `TMapApiClient.kt:226` `GET $baseUrl/pois/search/around` | apis.openapi.sk.com | HTTPS | **중심 위/경도**(`centerLat/centerLon`), 반경(`radius`), 결과 수 | 도착지 주변 POI(랜드마크) |

**iOS 에서 실제로 호출되는 것**: `searchPedestrianRoute`(NavigationCoordinator), `searchPOI`(DestinationInputView) 2개. `reverseGeocode`/`searchNearbyPOI` 는 shared 에 정의만 있고 iOS 호출부 없음(향후/Android용). 개인정보처리방침 근거로는 4개 엔드포인트 모두 **잠재 전송처**로 기재하는 것이 안전.

> ⚠️ 부수 관측: `searchPOI` 는 진단 `println` 으로 `currentLat/currentLon` 및 POI 좌표를 **stdout 에 출력**한다(`TMapApiClient.kt:133-134, 166, 184`). 네트워크 전송은 아니나, iOS 의 stdout 미러(§C4)가 이를 화면 오버레이로 옮긴다. 디스크 영속은 아님.

---

## 2. iOS ↔ shared 의존 표면 (§B)

`import shared` 6개 파일이 쓰는 Kotlin 심볼 전수. (성격: ㉠DTO ㉡API클라이언트 ㉢순수계산 ㉣상태관리)

| 심볼 | 정의 위치 | 성격 | 정의 파일 줄수 | Android도 사용? | iOS 사용처 |
|---|---|---|---|---|---|
| `TMapApiClient` (+`searchPOI`,`searchPedestrianRoute`) | `tmap/TMapApiClient.kt:41` (메서드 L125, L66) | ㉡ | 576 | **아니오** | AppDependencies:24,38 · NavigationCoordinator:53,122 · DestinationInputView:46,156 |
| `POIResult` | `tmap/POIResult.kt:12` | ㉠ | 18 | 아니오 | NavigationCoordinator:98,115,157 · DestinationInputView:35,41,48,181,219,237 |
| `TMapRoute` | `tmap/TMapRoute.kt:9` | ㉠ | 95 | 아니오 | NavigationCoordinator:38,122 · FollowingController:52 · GuidingView:77,104 |
| `Waypoint` (via `route.waypoints`) | `tmap/TMapRoute.kt:35` | ㉠ | (95) | 아니오 | NavigationCoordinator:145 · FollowingController:53 · GuidingView:111,114 (`.pointType/.lat/.lon`) |
| `LatLng` (via `route.routePoints`) | `tmap/TMapRoute.kt:25` | ㉠ | (95) | 아니오 | GuidingView:109 (`.lat/.lon`) |
| `SignalDecisionEngine` (+`reset`,`decide`,`()`생성자) | `signal/SignalDecisionEngine.kt:42` (L76, L92, L53) | ㉢+㉣ | 353 | **예** | TrafficLightDetector:71,194,199,267 |
| `RawSignalDetection` | `signal/SignalDecisionEngine.kt:263` | ㉠ | (353) | **예** | TrafficLightDetector:235,242 |
| `SignalDecision`(+Announce/Repeat/Flicker/Silent) | `signal/SignalDecisionEngine.kt:291` (L293/301/304/307) | ㉠ | (353) | **예** | TrafficLightDetector:274-314 (`switch`) |
| `SignalTransition` | `signal/SignalDecisionEngine.kt:273` | ㉠(enum) | (353) | **예** | TrafficLightDetector:319-320 (`.name` 분기) |
| `KotlinDouble` (KMP 런타임, shared 정의 아님) | — | 런타임 interop | — | — | NavigationCoordinator:115 · DestinationInputView:159-160 |

**iOS 미참조(정의만 존재)**: `RouteSegment`(L57), `RiskLevel`(L84), `ArrivalState`(L90), `SilentReason`(L281), `SignalDecisionConfig`(L320), `distanceBetween`(`geo/GeoDistance.kt:18`). 이 중 `RouteSegment/RiskLevel/distanceBetween` 은 `TMapApiClient` 내부에서 사용(iOS 는 자체 haversine 보유 — FollowingController:116, DestinationInputView:233).

**§B5 핵심 (Android 공유 여부)** — androidApp 전수 조사 결과:
- androidApp 은 shared 를 **`signal/SignalDecisionEngine` 계열만** import 한다(`androidApp/.../MainActivity.kt:29-32`, 사용 L63,245,251-272,338-352).
- androidApp 은 **shared/tmap 을 전혀 쓰지 않는다**(grep `navigation.tmap|TMapApiClient|POIResult|TMapRoute` on androidApp/src → 0건). Android 는 자체 TMap 구현을 별도 보유(shared 밖).
- 따라서 **양 플랫폼 공유 코드는 사실상 `SignalDecisionEngine.kt`(353줄) 하나**다. `tmap/*`+`geo/*` 는 **iOS 전용 소비자**(Android 미사용).

**§B 지정 4개 심볼 확인**:
- `NavigationManager`, `RouteAnnotator`, `KalmanHeading` — **저장소 어디에도 클래스로 존재하지 않는다**(전 소스 grep). `TMapRoute.kt:43-44` 주석에만 이름이 남은 **stale 참조**. iOS 는 이 셋을 호출하지 않는다(iOS 의 대응 로직은 Swift `FollowingController`).
- `SignalDecisionEngine` — **유일하게 실존하며 iOS 가 호출**(TrafficLightDetector). 그리고 이것이 **Android 와 공유되는 알고리즘 본체**(신뢰도 비대칭·안정성·점멸 판정). Swift 이전 = 안전 로직 이원화.

**§B6 Android 전용 shared 비중**: shared commonMain 에 **Android 전용 파일은 없음**(별도 sourceSet 없음, `iosMain/androidMain` 소스 디렉터리 미존재 — commonMain/commonTest 만). iOS 가 안 쓰는 것은 파일이 아니라 일부 내부 enum/보조 타입 수준. 즉 shared 는 "iOS 의 TMap+신호 + Android 의 신호"를 담는 모듈이며, 사장(死藏) 코드 비중은 거의 0.

> **한 줄 결론**: iOS 가 shared 에 의존하는 부분은 주로 **TMap 클라이언트·경로/POI DTO(iOS 전용)** 와 **신호 판정 엔진(Android 공유)** 이며, Swift 전환 시 대략 **iOS 6개 파일의 참조 교체 + 신규 Swift 약 750~800줄** 규모. (작업 권고는 하지 않음.)

---

## 3. 데이터 저장·캐싱 (§C)

| # | 판정 | 근거 |
|---|---|---|
| C1 저장 코드 | **shared: 없음.** Room/SQLDelight/DataStore/파일쓰기/DB 어느 것도 shared 에 없음. `mutableListOf` 는 전부 파싱 지역변수(TMapApiClient.kt:267-483)로 응답을 조립해 반환할 뿐 영속 아님. | shared/src grep(cache/room/sqldelight/datastore/db/write) → 파싱 지역변수 외 0건 |
| C2 만료/삭제 | **해당 없음.** 저장이 없으므로 24시간 제약 대상 자체가 없음 → **위반 소지 없음(shared 기준)**. | (C1과 동일 근거) |
| C3 메모리 캐시 수명 | **shared 에 캐시 필드 없음**(`cachedNearbyPOIs`/`cachedAddress` 등 부재). 반환값은 호출자가 들고 있는 동안만 메모리에 존재 → 앱 종료 시 소멸. iOS 는 `@Published currentRoute` 등 in-memory 상태로만 보유(NavigationCoordinator), 종료 시 소멸. | grep `cached` shared → 0건 |
| C4 위치/경로 디스크 로그 | **shared: 없음.** iOS: 로거 인프라(`NavLogFile` → `<Documents>/walk_logs/*.log`)가 존재하나 **dormant** — `NavLogFile.shared.start()` 호출부가 앱 어디에도 없어 파일 핸들이 열리지 않고 `append()` 는 no-op(`NavLogFile.swift:82` "활성 상태 아니면 무시"). `StdoutCapture.setUp()` 은 `iosAppApp.swift:16` 에서 **DEBUG 무관 무조건** 실행되어 stdout(=좌표 println 포함)을 가로채지만, 목적지는 **화면 in-memory 오버레이(DebugLogger)** 이고 디스크로는 안 감(NavLogFile inactive). → **실기 좌표가 디스크에 남는 활성 경로 없음.** | NavLogFile.swift:8,44,64,82 · DebugLogger.swift:56 · StdoutCapture.swift:87 · iosAppApp.swift:16 · grep `NavLogFile.shared.start` → 0건 |
| C5 좌표 영구 보관 기능 | **없음.** 즐겨찾기/최근 목적지 등 좌표 영속 기능 부재. iOS `UserDefaults` 사용은 `hasAgreedToSafetyNotice`(AppDependencies:42) + 디버그 플래그(DestinationInputView:112,184,200)뿐 — **좌표 아님**. Android `SharedPreferences` 는 안전고지 동의(SafetyNoticeActivity.kt:73) — 좌표 아님. | 해당 파일 라인 |

> ⚠️ 주의: 위 dormant 판정은 "현재 소스에서 `start()` 미호출" 기준이다. 이후 누군가 `NavLogFile.shared.start()` 를 배선하면 stdout 의 좌표 println 이 그대로 디스크에 축적된다. Release 배포 전 `StdoutCapture` 를 `#if DEBUG` 로 감싸는 것을 후속 단계에서 검토 권장(이번 단계는 진단만).

---

## 4. required-reason API / 서드파티 (§D)

| # | 판정 | 근거 |
|---|---|---|
| D1 required-reason API(shared iOS 코드) | **해당 없음.** shared 는 `iosMain` **소스 디렉터리가 없다**(commonMain/commonTest 만). iOS 타깃에 들어가는 shared 자체 코드는 순수 Kotlin common + Ktor darwin 엔진 라이브러리뿐 — `NSUserDefaults`/파일 타임스탬프/`systemUptime`/디스크용량 API 직접 사용 없음. | `find shared/src -maxdepth 1 -type d` → commonMain, commonTest |
| D2 기기 식별자 | **없음.** `identifierForVendor`/IDFA/광고ID 수집 코드 shared 에 부재. | grep 0건 |
| D3 서드파티 라이브러리(gradle) | shared/build.gradle.kts 의존: **coroutines-core 1.7.3**, **ktor-client-core 2.3.7**, **kotlinx-serialization-json 1.6.2**, **ktor-client-content-negotiation 2.3.7**, **ktor-serialization-kotlinx-json 2.3.7**; androidMain **ktor-client-okhttp 2.3.7**; iosMain **ktor-client-darwin 2.3.7**. 네트워크 사용: **Ktor(클라이언트) 계열만** — 그리고 그 트래픽은 §A 의 TMap 1곳으로만 나감(코드가 그렇게 호출). serialization/coroutines 는 네트워크 아님. **분석/크래시/텔레메트리 SDK 없음.** | build.gradle.kts:40-57 |

> 참고: iosMain 의 Ktor darwin 엔진은 내부적으로 `NSURLSession` 을 쓴다(Apple required-reason 목록 비대상). shared 코드가 required-reason 카테고리 API(UserDefaults/파일타임스탬프/부팅시간/디스크용량)를 부르는 곳은 없음. iOS 앱 쪽 `UserDefaults` 는 §C5 참조(앱 privacy manifest 대상은 iosApp 몫, 이번 범위 밖).

---

## 5. 아카이브 구성 (§E)

| # | 판정 | 근거 |
|---|---|---|
| E1 프로젝트 구조 | **동기화 폴더(file system synchronized) 방식.** `PBXFileSystemSynchronizedRootGroup` **3회**, pbxproj 내 `.swift` 리터럴 **0개**, `PBXBuildFile` 4개(대부분 프레임워크). | project.pbxproj: grep 카운트 |
| E2 Secrets.plist 번들 포함 구조 | **자동 포함 구조.** 동기화 폴더라 `iosApp/iosApp/Secrets.plist` 가 소스 그룹째 번들에 들어간다. pbxproj 에 `Secrets` 리터럴 없음(동기화 방식과 일치, 개별 나열 불필요). | pbxproj grep `Secrets` 0건 · `iosApp/iosApp/Secrets.plist` 존재 |
| E3 실제 .app 내 Secrets.plist | **Debug 시뮬레이터: 포함 ✅** (`iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app/Secrets.plist` 존재). **Release/기기: 확인 불가** — 실물 제품 빌드가 없다. 유일한 기기 빌드는 인덱서용 `Index.noindex/.../Debug-iphoneos` 로 리소스를 복사하지 않는 빌드라 여기의 Secrets.plist 부재는 무의미. | find Products(제외 Index.noindex) → Debug-iphonesimulator 만; `Release-iphone*` 디렉터리 0건 |
| E4 Compile Kotlin Framework 스크립트 | 아래 전문(원문 그대로). | project.pbxproj:182 |
| E5 Release/iphoneos 처리 | **처리하도록 설계됨.** 스크립트가 `$CONFIGURATION`/`$PLATFORM_NAME`/`$ARCHS` 를 Gradle 태스크에 전달, `FRAMEWORK_SEARCH_PATHS[arch=*] = .../xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` 가 **Debug·Release 양쪽 빌드설정에 존재**(pbxproj:347 Debug블록, 383 Release블록 내). framework{} 블록은 iosX64/iosArm64/iosSimulatorArm64 **세 타깃 공통**, `isStatic=true`. | pbxproj:325/361 · build.gradle.kts:26-37 |
| E6 iOS 실기기(arm64) 타깃 | **선언됨 ✅.** `iosArm64()` — build.gradle.kts:29 (`if(isRunningOnMac)` 안, 이 Mac 에서 활성). 기기 프레임워크 산출물 실재: `shared/build/bin/iosArm64/debugFramework/shared.framework`, `shared/build/xcode-frameworks/Debug/iphoneos26.5/shared.framework`. | build.gradle.kts:26-37 · find 산출물 |

**E4 — `Compile Kotlin Framework` 스크립트 전문** (project.pbxproj:182, 빌드단계 순서 pbxproj:98 → Frameworks(100) → Embed(102) 보다 먼저 실행):
```sh
# Type a script or drag a script file from your workspace to insert its path.
cd "$SRCROOT/.."

if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

./gradlew :shared:embedAndSignAppleFrameworkForXcode \
  -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
  -Pkotlin.native.cocoapods.archs="$ARCHS" \
  -Pkotlin.native.cocoapods.configuration=$CONFIGURATION
```

### E 핵심 — "무엇이 iOS용 shared.framework 를 링크·임베드하는가"

1. **Xcode 의 Frameworks(link)/Embed Frameworks 단계는 iOS 에서 비활성.** 두 PBXBuildFile 이 `platformFilters = (xros, )` 라 **visionOS(xrOS) 전용**으로만 적용된다(pbxproj:10, 11). 그래서 이 단계들은 iphoneos/iphonesimulator 빌드에 관여하지 않는다.
2. **대신 링크 경로는 빌드설정 + 스크립트가 담당한다:**
   - `Compile Kotlin Framework` 스크립트(E4)가 `:shared:embedAndSignAppleFrameworkForXcode` 를 현재 `$PLATFORM_NAME/$ARCHS/$CONFIGURATION` 으로 실행 → `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)/shared.framework` 산출.
   - `FRAMEWORK_SEARCH_PATHS[arch=*] = $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` (Debug·Release 모두)가 그 산출물을 찾게 하여 `import shared` 모듈 해석 + 링크를 가능케 한다.
3. **`isStatic=true`** 이므로 정적 프레임워크 — 심볼이 앱 바이너리에 링크되고 **런타임 임베드 대상이 아니다.** 실제로 Debug 시뮬레이터 .app 안에 `shared.framework` 가 **없음**(정적 링크의 정상 결과). pbxproj:30 의 파일참조 경로(`.../Debug/iphonesimulator26.4/...`)는 IDE 표시용 고정 참조일 뿐, 실제 해석은 위 파라미터화된 search path 로 이뤄진다.

### E — 남는 확인 불가/리스크

- **Xcode 링크 단계가 iOS 에서 꺼져 있는데(1), 정적 프레임워크를 바이너리에 링크시키는 명시적 `-framework shared`(OTHER_LDFLAGS)는 pbxproj 에서 확인되지 않았다.** 현재 모듈 해석은 `FRAMEWORK_SEARCH_PATHS` 로 되지만, 최종 심볼 링크가 현재 설정만으로 Release/기기 아카이브에서 깨끗이 성사되는지는 **실제 아카이브 없이 단정 불가.** 기존 Debug 시뮬레이터 .app 은 platformFilters 변경 이전 산출물일 가능성도 배제 못 함.
- **Release/기기 제품 빌드가 저장소·DerivedData 어디에도 없음**(E3). 따라서 "Release 아카이브에서 shared.framework 링크·Secrets.plist 포함"은 **확인 불가 — 실제 `Archive` 1회 필요.**

---

## 6. ❓ 확인 불가 항목

| 항목 | 왜 확인 못 했는가 | 누가 어떻게 확인해야 하는가 |
|---|---|---|
| Release/기기 아카이브에서 shared.framework 정적 링크 성공 | Release·Debug-iphoneos 실물 제품 빌드가 없음(인덱서 빌드만 존재). read-only 원칙상 빌드 미수행. | 개발자가 Xcode `Product > Archive` 1회 실행 후 결과 바이너리에 shared 심볼 링크/실행 확인 |
| Release .app 내 Secrets.plist 포함 | 위와 동일(Release 빌드 부재). Debug 시뮬레이터에서는 포함 확인됨. | 아카이브 후 `.xcarchive/Products/Applications/iosApp.app/Secrets.plist` 존재 확인 |
| Xcode 링크 단계 비활성 상태에서 명시적 링크 플래그 유무 | pbxproj 에 `OTHER_LDFLAGS -framework shared` 미발견 — 정적 링크가 search path만으로 성립하는지 실빌드 없이 단정 불가 | 아카이브 로그의 링커 커맨드(`ld ... -framework shared` 여부) 확인 |
| TMap 앱 키의 실제 노출/키 값 | 정책상 키 값을 열람·기록하지 않음(§A5). | 별도 시크릿 점검 절차(범위 밖) |
| App privacy manifest(iosApp) 완성도 | 이번 범위는 shared. iosApp `UserDefaults`/required-reason 은 1차 진단·iosApp 담당. | iosApp 진단(readiness_report.md) 후속 |

---

*본 진단으로 코드·빌드 설정·gradle·gitignore 변경 없음. 산출물은 이 파일 1개.*
