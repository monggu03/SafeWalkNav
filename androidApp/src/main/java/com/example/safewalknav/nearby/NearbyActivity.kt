package com.example.safewalknav.nearby

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.example.safewalknav.BuildConfig
import com.example.safewalknav.MainActivity
import com.example.safewalknav.R
import com.example.safewalknav.a11y.AppSettings
import com.example.safewalknav.a11y.PermissionGate
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.WindowFocus
import com.example.safewalknav.navigation.geo.clockHour
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.geo.initialBearingDeg
import com.example.safewalknav.navigation.geo.relativeBearingDeg
import com.example.safewalknav.navigation.nearby.Anchor
import com.example.safewalknav.navigation.nearby.FusedFix
import com.example.safewalknav.navigation.nearby.GeoPoint
import com.example.safewalknav.navigation.nearby.LocationAnchors
import com.example.safewalknav.navigation.nearby.LocationFusion
import com.example.safewalknav.navigation.nearby.NearbyAssembler
import com.example.safewalknav.navigation.nearby.NearbyPhrases
import com.example.safewalknav.navigation.nearby.NearbyPlace
import com.example.safewalknav.navigation.nearby.NearbyResult
import com.example.safewalknav.navigation.nearby.PointGridIndex
import com.example.safewalknav.navigation.tmap.AddressInfo
import com.example.safewalknav.navigation.tmap.POIResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 기능1: 내 위치 알려주기 + 주변 장소 박스.
 *
 * 흐름
 *  1) 위치 권한 확인 → 위치 기능 켜짐 확인
 *  2) 최대 8초 동안 픽스를 모아 보정([LocationSampler] → shared [LocationFusion])
 *  3) 주소(역지오코딩) + 카테고리별 거리순 검색을 **동시에** 요청
 *  4) "현재 위치는 ○○ 근처입니다. 주변 장소 N곳을 찾았습니다." — 장소 이름은 말하지 않는다
 *  5) 박스를 누르면 **그 순간의** 정면 방향 기준 "○시 방향, 약 N미터"
 *
 * 방향을 누르는 순간 계산하는 이유: 사용자는 목록을 듣는 동안 몸을 돌린다.
 * 검색 시점 방향을 박스에 박아 두면 몇 초 뒤엔 틀린 말이 된다.
 */
class NearbyActivity : AppCompatActivity() {

    private lateinit var tvAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var placesContainer: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var btnCamera: Button

    private lateinit var speaker: Speaker
    private lateinit var heading: HeadingTracker
    private lateinit var sampler: LocationSampler
    private lateinit var beacon: DirectionBeacon
    private val tmap get() = TMapProvider.client

    private var searchJob: Job? = null
    private var fix: FusedFix? = null

    /** [fix] 를 얻은 시각(단조 시계). 오래된 위치로 방향을 말하지 않기 위해. */
    private var fixAtMs = 0L

    /** 화면을 떠나며 검색을 취소했다 — 돌아오면 다시 찾는다. */
    private var refreshOnReturn = false

    /**
     * 마지막 위치 설명에 쓰인 재료. 위치 제목에 TalkBack 포커스가 갈 때마다
     * **그 순간의 방향으로** 문장을 다시 만든다(몇 분 전 방향을 그대로 읽으면 틀린 말이 된다).
     */
    private var locationContext: LocationContext? = null

    private class LocationContext(val address: AddressInfo?, val anchors: List<Anchor>, val fix: FusedFix)

    /** 방향 찾기 진동이 가리키고 있는 장소. 같은 박스를 다시 누르면 멈춘다. */
    private var beaconPlace: NearbyPlace? = null

    /** 8자 보정 안내는 화면당 한 번만 — 계속 말하면 잔소리가 된다. */
    private var calibratePrompted = false

    /**
     * 위치 권한이 영구 거부(다시 묻지 않음) 상태 — 시스템이 창을 더는 안 띄운다.
     * 예전에는 [다시 찾기] 가 즉시 거부되는 창만 다시 불러 같은 안내가 반복됐다(빠져나갈 길 없음).
     * 이제 [다시 찾기] 가 앱 설정 화면을 연다.
     */
    private var locationPermissionBlocked = false
    private var locationPermissionRequestedAt = 0L

    /** 설정 화면으로 보냈다 — 돌아오면 권한을 다시 보고 찾기 시작하거나 무엇이 빠졌는지 말한다. */
    private var sentToSettings = false

    /** 우리가 띄운 권한 창이 떠 있다 — 그동안의 onPause 는 "떠남"이 아니다. */
    private var permissionDialogShowing = false

    /** 안내를 말하던 중에 화면을 떠났다 — 돌아오면 다시 들려준다. */
    private var speechCutOff = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDialogShowing = false
            if (hasFineLocation()) {
                locationPermissionBlocked = false
                refresh()
            } else {
                locationPermissionBlocked = PermissionGate.isBlocked(
                    this, Manifest.permission.ACCESS_FINE_LOCATION, locationPermissionRequestedAt
                )
                tvAddress.text = "위치 권한 필요"
                speaker.speak(locationPermissionMessage())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby)
        title = "내 위치와 주변 장소"

        tvAddress = findViewById(R.id.tvAddress)
        tvStatus = findViewById(R.id.tvStatus)
        placesContainer = findViewById(R.id.placesContainer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCamera = findViewById(R.id.btnCamera)

        speaker = Speaker(this, tvStatus)
        heading = HeadingTracker(this)
        sampler = LocationSampler(this)
        beacon = DirectionBeacon(this, heading).apply {
            onTimeout = {
                beaconPlace = null
                speaker.speak(NearbyPhrases.BEACON_TIMEOUT)
            }
            onCompassLost = {
                beaconPlace = null
                speaker.speak("${NearbyPhrases.BEACON_STOPPED} ${NearbyPhrases.CALIBRATE_COMPASS}")
            }
        }

        // 위치 제목: TalkBack 이 포커스할 때마다 지금 방향으로 위치 설명을 새로 만든다.
        ViewCompat.setAccessibilityDelegate(tvAddress, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                currentLocationMessage()?.let { info.contentDescription = it }
            }
        })

        btnRefresh.setOnClickListener { refresh() }
        // 보행 신호등이 가까우면 곧바로 신호등 카메라로 — 홈으로 돌아갈 필요 없이.
        btnCamera.setOnClickListener {
            // 진행 중인 검색도 멈춘다. 안 그러면 몇 초 뒤 이 화면의 음성이 신호등 카메라 안내 위로
            // "주변 장소 N곳을 찾았습니다"를 덮어 말한다. 돌아오면 다시 찾는다(onResume).
            cancelSearchForLeaving()
            stopBeacon()
            speaker.stop()
            startActivity(Intent(this, MainActivity::class.java))
        }

        if (BuildConfig.TMAP_APP_KEY.isBlank()) {
            tvAddress.text = "설정 문제"
            btnRefresh.isEnabled = false
            speaker.speak(NearbyPhrases.NOT_CONFIGURED)
            return
        }

        if (savedInstanceState != null) {
            sentToSettings = savedInstanceState.getBoolean(STATE_SENT_TO_SETTINGS, false)
            locationPermissionBlocked = savedInstanceState.getBoolean(STATE_PERMISSION_BLOCKED, false)
        }
        if (hasFineLocation()) {
            refresh()
        } else if (savedInstanceState == null) {
            // 재생성(프로세스 복원) 때 다시 띄우면 권한 창이 겹쳐 뜬다 — 처음 열 때만 묻는다.
            requestLocationPermission()
        } else if (!sentToSettings) {
            tvAddress.text = "위치 권한 필요"
            speaker.speak(locationPermissionMessage())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SENT_TO_SETTINGS, sentToSettings)
        outState.putBoolean(STATE_PERMISSION_BLOCKED, locationPermissionBlocked)
    }

    override fun onResume() {
        super.onResume()
        heading.start()
        if (sentToSettings) {
            sentToSettings = false
            if (hasFineLocation()) {
                locationPermissionBlocked = false
                refresh()
            } else {
                // 설정에서 허용하지 않고 돌아옴 — 무엇이 빠졌는지 말한다(아무 말 없으면 멈춘 줄 안다).
                tvAddress.text = "위치 권한 필요"
                speaker.speak(locationPermissionMessage())
            }
            return
        }
        // 돌아왔을 때 다시 찾는다 — ① 떠나며 검색을 끊었거나 ② 위치가 오래됐을 때.
        // 신호등 카메라로 길을 건너고 돌아오면 예전 박스는 수십 m 전 위치 기준이라, 누르면 엉뚱한 방향을 말한다.
        // (같은 자리면 장소 목록은 캐시에서 나와 API 비용은 거의 없다)
        val stale = fix != null && SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_RETURN_MS
        if (refreshOnReturn || stale) {
            refreshOnReturn = false
            speechCutOff = false   // 다시 찾으면서 새로 안내한다
            refresh()
            return
        }
        // 안내(위치 설명·요약)를 듣다가 나갔다 돌아옴 — 끊긴 안내를 처음부터 다시 들려준다.
        // 상태 줄에는 마지막으로 한 말이 그대로 있다(라이브 리전 토글용 보이지 않는 문자만 뗀다).
        if (speechCutOff) {
            speechCutOff = false
            val last = tvStatus.text.toString().replace("​", "").trim()
            if (last.isNotEmpty()) speaker.speak(last)
        }
    }

    /**
     * 위치 권한을 얻는 길. 영구 거부면 앱 설정 화면을, 아니면 권한 창을 연다.
     * TalkBack 없이 쓰는 사람은 권한 창이 떴다는 것도 모르므로 미리 말해 둔다.
     */
    private fun requestLocationPermission() {
        if (locationPermissionBlocked) {
            speaker.speak(OPENING_SETTINGS)
            sentToSettings = AppSettings.open(this)
            return
        }
        if (!speaker.isScreenReaderOn()) speaker.speak(PERMISSION_DIALOG_HINT)
        locationPermissionRequestedAt = PermissionGate.markRequested()
        permissionDialogShowing = true   // 권한 창이 이 화면을 가려도(onPause) 위 안내는 끝까지 들려준다
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** 지금 상태에 맞는 위치 권한 안내 — 무엇을 눌러/켜야 하는지까지. */
    private fun locationPermissionMessage(): String {
        // 안드로이드 12+: "대략적 위치"만 허용 — 오차가 수 km 라 주변 안내가 성립하지 않는다.
        val approximateOnly = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            locationPermissionBlocked && approximateOnly -> LOCATION_BLOCKED_APPROXIMATE
            locationPermissionBlocked -> LOCATION_BLOCKED
            approximateOnly -> LOCATION_APPROXIMATE
            else -> LOCATION_DENIED
        }
    }

    /**
     * 화면이 완전히 가려졌다 — 안전망. 보통은 onPause 에서 이미 멈췄다.
     * 권한 창이 떠 있는 동안 홈 버튼을 누른 경우(onPause 에서는 일부러 안 멈춤)가 여기서 걸린다.
     */
    override fun onStop() {
        super.onStop()
        cancelSearchForLeaving()
        // 우리가 설정 화면을 연 경우만 "허용한 뒤 돌아오세요"를 끝까지 들려준다.
        if (!sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    private fun cancelSearchForLeaving() {
        if (searchJob?.isActive == true) {
            searchJob?.cancel()
            refreshOnReturn = true
        }
    }

    /** 지금 방향 기준의 위치 설명 (아직 검색 전이면 null). */
    private fun currentLocationMessage(): String? {
        val c = locationContext ?: return null
        // 박스 탭과 같은 기준 — 위치가 오래됐으면 방향을 빼고 그 사실을 붙인다.
        // (화면을 켠 채 걸은 뒤 제목을 들으면 옛 위치 기준 "○시 방향"이 나오던 문제)
        val stale = SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_TAP_MS
        val message = NearbyPhrases.currentLocation(c.address, c.anchors, c.fix.accuracyM) { a ->
            if (stale) null else clockHourTo(c.fix, a.lat, a.lon, a.distanceM)
        }
        return if (stale) "$message ${NearbyPhrases.STALE_LOCATION_HINT}" else message
    }

    /**
     * 홈 버튼·최근 앱·화면 끄기 → **즉시** 말과 검색을 멈춘다.
     * 예전에는 onStop 에서만 멈춰서, 앱 밖으로 나간 뒤에도 안내가 조금 더 이어졌다.
     * 예외: 우리가 띄운 권한 창이 이 화면을 가린 경우(그 창을 설명하는 안내를 끊지 않게),
     *       우리가 연 설정 화면으로 가는 경우("허용한 뒤 돌아오세요"를 끝까지).
     */
    override fun onPause() {
        super.onPause()
        // 화면을 떠나면 방향 진동도 끝 — 주머니 속에서 계속 떨면 안 된다.
        stopBeacon()
        heading.stop()
        if (!permissionDialogShowing && !sentToSettings) {
            cancelSearchForLeaving()   // 남의 화면 위로 "주변 장소 N곳을 찾았습니다"가 나오지 않게
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        stopBeacon()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun stopBeacon() {
        if (::beacon.isInitialized) beacon.stop()
        beaconPlace = null
    }

    /**
     * 정밀 위치 권한. Android 12+ 에서 "대략적 위치"만 허용하면 오차가 수 km 라
     * 10m 주변 안내가 성립하지 않으므로 정밀 권한을 요구한다.
     */
    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ==================== 검색 ====================

    private fun refresh() {
        if (searchJob?.isActive == true) return
        if (!hasFineLocation()) {
            // 권한 창을 그냥 닫았던 경우 — [다시 찾기] 로 다시 물을 수 있어야 한다.
            // 영구 거부면 설정 화면을 연다.
            requestLocationPermission()
            return
        }
        if (!sampler.isLocationEnabled()) {
            // 지난 결과를 비운다 — 남겨 두면 TalkBack 이 제목에서 옛 위치 설명을 읽고,
            // 옛 박스를 누르면 옛 위치 기준 방향을 말한다.
            stopBeacon()
            placesContainer.removeAllViews()
            locationContext = null
            fix = null
            tvAddress.text = "위치 기능 꺼짐"
            speaker.speak(NearbyPhrases.LOCATION_OFF)
            return
        }

        stopBeacon()
        refreshOnReturn = false
        placesContainer.removeAllViews()
        tvAddress.text = "내 위치 확인 중"
        locationContext = null   // 지난 위치 설명이 남아 읽히지 않게
        btnRefresh.isEnabled = false
        speaker.speak(NearbyPhrases.SEARCHING)

        searchJob = lifecycleScope.launch {
            try {
                // 보행등 데이터는 위치를 모으는 동안 미리 읽어 둔다(첫 회만 수십 ms).
                val signalIndexJob = async { PedSignalRepository.get(this@NearbyActivity) }
                val samples = sampler.sample()
                val f = LocationFusion.fuse(samples)
                if (f == null) {
                    tvAddress.text = "위치를 찾지 못함"
                    speaker.speak(NearbyPhrases.LOCATION_FAILED)
                    return@launch
                }
                fix = f
                fixAtMs = SystemClock.elapsedRealtime()
                heading.setLocation(f.lat, f.lon)

                // 주소(건물명·동)와 키워드별 검색을 동시에. 하나가 실패해도 나머지는 쓴다.
                // 같은 자리(25m)·2분 안이면 직전 장소 목록을 재사용한다 — 거리·방향은 아래에서 새로 계산.
                // (재사용한 결과는 다시 저장하지 않는다 — 저장할 때마다 2분이 새로 시작돼 영영 안 바뀐다)
                // 주소는 매번 새로 묻는다(TMapProvider.kt 의 NearbySearchCache 주석).
                //
                // ⚠️ 동시에 [MAX_CONCURRENT_REQUESTS] 건까지만 보낸다. HTTP 엔진(OkHttp)은 같은 서버에
                //    5건까지만 동시에 보내고 나머지는 줄을 세우는데, 10초 제한 시간은 줄 서 있는 동안에도 흐른다.
                //    약전계에서 세 번째 묶음이 줄만 서다 시간 초과로 "실패" 처리됐다. 여기서 세면 시계는
                //    실제로 보낼 때부터 간다.
                //    대신 줄 서는 시간까지 합친 전체 마감([SEARCH_DEADLINE_MS])을 둔다 — 약전계에서 요청마다
                //    10초씩 4·4·3 세 번 기다려 30초 넘게 무음이 되지 않게. 마감을 넘긴 건 실패(null)로 친다.
                val (address, groups) = coroutineScope {
                    val gate = Semaphore(MAX_CONCURRENT_REQUESTS)
                    val addr = async(Dispatchers.IO) {
                        withTimeoutOrNull(SEARCH_DEADLINE_MS) { gate.withPermit { tmap.reverseGeocodeDetail(f.lat, f.lon) } }
                    }
                    val g = NearbySearchCache.get(f.lat, f.lon) ?: (SEARCH_CATEGORIES + ANCHOR_ONLY_KEYWORDS)
                        .map { c ->
                            async(Dispatchers.IO) {
                                c to withTimeoutOrNull(SEARCH_DEADLINE_MS) {
                                    gate.withPermit { tmap.searchNearbyByKeyword(c, f.lat, f.lon) }
                                }
                            }
                        }
                        .awaitAll()
                        .also { NearbySearchCache.put(f.lat, f.lon, it) }
                    addr.await() to g
                }
                logSearchResults(f, groups)

                val byKeyword: Map<String, List<POIResult>> =
                    groups.mapNotNull { (k, pois) -> pois?.let { k to it } }.toMap()
                // 박스용 업종 검색이 전부 실패 = 검색 실패. (지하철 출구·교차로 같은 기준점 검색만 성공해도
                // 예전에는 "일부만 실패"로 쳐서 "찾은 장소가 없습니다"라고 단정했다)
                // 인터넷 검색이 실패해도 멈추지 않는다 — 보행 신호등은 앱 안 데이터라 여전히 알려 줄 수 있다.
                val searchFailed = SEARCH_CATEGORIES.none { byKeyword.containsKey(it) }
                // 일부만 실패 — 안 나온 종류(예: 버스정류장)가 "없는 것"으로 오해되지 않게 알린다.
                val searchPartial = !searchFailed && byKeyword.size < groups.size

                // ── 1) 지금 어디인가 — 도로명 대신 기준점(지하철 출구·정류장·교차로)으로 ──
                val exits = byKeyword[LocationAnchors.KEYWORD_SUBWAY_EXIT].orEmpty()
                    .filter { LocationAnchors.isSubwayExit(it.name) }
                val stops = byKeyword[LocationAnchors.KEYWORD_BUS_STOP].orEmpty()
                    .filter { LocationAnchors.isBusStop(it) }
                val crossings = byKeyword[LocationAnchors.KEYWORD_INTERSECTION].orEmpty()
                    .filter { LocationAnchors.isIntersection(it.name) }
                val signals = nearbyPedSignals(signalIndexJob.await(), f)
                val anchors = LocationAnchors.pick(
                    f.lat, f.lon, exits, stops, crossings,
                    nearestPedSignal = signals.firstOrNull()?.let { s ->
                        GeoPoint(s.lat, s.lon) to distanceBetween(f.lat, f.lon, s.lat, s.lon)
                    },
                )
                tvAddress.text = NearbyPhrases.locationHeadline(address, anchors)
                // TalkBack 사용자가 제목에 포커스하면 **전체 위치 설명**을 (그 순간의 방향으로) 다시 듣는다.
                // 아래 상태 줄은 박스를 누를 때마다 그 장소 안내로 덮여서, 위치 설명을 다시 들을 곳이 없어진다.
                locationContext = LocationContext(address, anchors, f)
                val locationMsg = currentLocationMessage().orEmpty()

                // ── 2) 주변 장소 박스 — 업종 검색 + 지하철 출구·교차로(거른 것만) ──
                val boxGroups = SEARCH_CATEGORIES.mapNotNull { k ->
                    val pois = if (k == LocationAnchors.KEYWORD_BUS_STOP) stops else byKeyword[k]
                    pois?.let { k to it }
                } + listOf("지하철 출구" to exits, "교차로" to crossings, SIGNAL_CATEGORY to signals)
                val result = NearbyAssembler.assemble(f.lat, f.lon, f.accuracyM, boxGroups)
                renderPlaces(result)

                var summary = when {
                    // 하나도 못 찾았는데 검색이 (일부라도) 실패했다 — "없다"고 단정하지 않는다.
                    (searchFailed || searchPartial) && result.places.isEmpty() -> NearbyPhrases.SEARCH_FAILED
                    // 검색은 실패했지만 보행 신호등(앱 안 데이터)·기준점은 찾았다
                    searchFailed -> "${NearbyPhrases.SEARCH_FAILED} ${NearbyPhrases.resultSummary(result)}"
                    searchPartial -> "${NearbyPhrases.resultSummary(result)} ${NearbyPhrases.SEARCH_PARTIAL}"
                    else -> NearbyPhrases.resultSummary(result)
                }
                if (heading.hasSensor && heading.isUnreliable && !calibratePrompted) {
                    calibratePrompted = true
                    summary += " ${NearbyPhrases.CALIBRATE_COMPASS}"
                }
                announceResults(locationMsg, summary)
            } finally {
                btnRefresh.isEnabled = true
            }
        }
    }

    /**
     * 검색 결과 안내.
     *
     *  - TalkBack 꺼짐: 앱 음성으로 "위치 설명 + 요약" 한 번에.
     *  - TalkBack 켜짐: **위치 제목으로 접근성 포커스를 옮겨** 위치 설명을 읽히고(제목의 설명 = 전체 문장),
     *    요약은 상태 줄(라이브 리전)로 그 뒤에 이어 읽힌다.
     *    왜: [다시 찾기]를 눌렀으니 포커스는 맨 아래 버튼에 남아 있다. 그대로 두면 박스로 가려면
     *    거꾸로 스와이프해야 하고, 위치 설명을 상태 줄에도 넣으면 같은 말을 두 번 듣는다.
     *    포커스를 맨 위로 올리면 다음 스와이프가 곧 첫 장소 박스다(상태 줄은 목록 아래에 있다).
     *    (포커스 낭독이 먼저 시작되고, 라이브 리전은 순서를 기다렸다 읽힌다 — 그래서 포커스를 먼저 건다)
     */
    private fun announceResults(locationMsg: String, summary: String) {
        if (!speaker.isScreenReaderOn()) {
            speaker.speak("$locationMsg $summary")
            return
        }
        WindowFocus.runWhenFocused(tvAddress) {
            // 이미 제목에 포커스가 있으면 TalkBack 이 다시 읽지 않는다 — 한 번 풀었다가 다시 건다.
            tvAddress.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
            tvAddress.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            speaker.speak(summary)
        }
    }

    // ==================== 박스 ====================

    private fun renderPlaces(result: NearbyResult) {
        placesContainer.removeAllViews()
        for (place in result.places) {
            placesContainer.addView(makeCard(place))
        }
    }

    private fun makeCard(place: NearbyPlace): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x1F, 0x1F, 0x1F))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.rgb(0x55, 0x55, 0x55))
            }
            isClickable = true
            isFocusable = true
            // 박스 전체를 한 덩어리로 읽힌다: "CU 필동점, 편의점, 약 8미터"
            contentDescription = NearbyPhrases.placeLabel(place)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnClickListener { onPlaceTapped(place) }
        }
        // TalkBack: "버튼, 두 번 탭하면 방향과 거리 듣기"
        ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK, "방향과 거리 듣기"
                    )
                )
            }
        })

        card.addView(TextView(this).apply {
            text = place.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        card.addView(TextView(this).apply {
            text = "${place.category} · ${NearbyPhrases.distanceText(place.distanceM)}"
            setTextColor(Color.rgb(0xFF, 0xD7, 0x00))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return card
    }

    /** 누른 순간의 정면 방향으로 시계 방향을 계산해 말한다. */
    private fun onPlaceTapped(place: NearbyPlace) {
        val f = fix ?: return
        // 거리가 위치 오차의 2배는 돼야 방향 오차가 ±30°(시계 한 칸) 안으로 들어온다.
        // (거리 = 오차면 최대 ±90° — "2시"가 실제로는 11시일 수도 있다)
        val withinError = place.distanceM < f.accuracyM * DIRECTION_MIN_DISTANCE_RATIO

        // 같은 장소를 다시 누르면 방향 찾기 진동을 멈춘다 (켜고 끄는 방법이 하나라 외우기 쉽다).
        if (beacon.isRunning && beaconPlace === place) {
            stopBeacon()
            speaker.speak(NearbyPhrases.BEACON_STOPPED)
            return
        }
        // 다른 장소를 누르면 이전 진동은 끝 — 두 곳을 동시에 가리키면 헷갈린다.
        stopBeacon()

        // 화면을 켠 채 걸어 다녔다면 박스의 기준 위치가 이미 멀어졌을 수 있다 — 방향을 지어내지 않는다.
        if (SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_TAP_MS) {
            speaker.speak(NearbyPhrases.placeDetail(place, null, NearbyPhrases.NoDirectionReason.STALE_LOCATION))
            return
        }
        if (!heading.hasSensor) {
            speaker.speak(NearbyPhrases.placeDetail(place, null, NearbyPhrases.NoDirectionReason.NO_SENSOR))
            return
        }
        if (heading.isUnreliable) {
            var message = NearbyPhrases.placeDetail(place, null, NearbyPhrases.NoDirectionReason.UNRELIABLE)
            if (!calibratePrompted) {
                calibratePrompted = true
                message += " ${NearbyPhrases.CALIBRATE_COMPASS}"
            }
            speaker.speak(message)
            return
        }
        val h = heading.currentTrueHeadingDeg()
        if (h == null) {
            // 센서는 있는데 아직 첫 값이 안 왔다 — 방향을 지어내지 않는다.
            speaker.speak(NearbyPhrases.placeDetail(place, null, NearbyPhrases.NoDirectionReason.UNRELIABLE))
            return
        }
        val bearing = initialBearingDeg(f.lat, f.lon, place.lat, place.lon)
        val hour = clockHour(relativeBearingDeg(bearing, h))
        // 방향 찾기 진동은 방향을 믿을 수 있을 때만 — 오차 원 안(아주 가까움)이면 방향이 사실상
        // 무작위라 진동이 엉뚱한 쪽을 가리킨다.
        // 진동 모터가 없으면 "진동이 울립니다"라고 약속하지 않는다.
        val startBeacon = !withinError && beacon.canVibrate
        if (startBeacon) {
            beacon.start(bearing)
            beaconPlace = place
        }
        speaker.speak(NearbyPhrases.placeDetail(place, hour, null, withinError, beaconStarted = startBeacon))
    }

    /**
     * 가까운 보행 신호등(서울시 데이터) → 박스용 장소.
     *
     * 한 횡단보도 모퉁이에 신호등이 여러 개 서 있으므로 [SIGNAL_GROUP_M] 안은 하나로 묶고,
     * 최대 [SIGNAL_MAX_BOXES] 개만. 대개 "내 쪽 신호등"과 "건너편 신호등"이 남는다 —
     * 건너편 것이 곧 신호등 카메라로 비춰야 할 방향이다.
     *
     * ⚠️ 데이터가 닿지 않는 곳(서울 밖·데이터 공백)이면 **빈 목록**이다. "신호등이 없다"는 뜻이 아니므로
     *    그렇게 말하지 않는다 (iOS PedestrianSignalIndex 와 같은 원칙).
     */
    private fun nearbyPedSignals(index: PointGridIndex?, f: FusedFix): List<POIResult> {
        if (index == null || !index.isInsideCoverage(f.lat, f.lon)) return emptyList()
        val kept = mutableListOf<GeoPoint>()
        for ((p, _) in index.nearest(f.lat, f.lon, SIGNAL_SEARCH_RADIUS_M)) {
            if (kept.any { distanceBetween(it.lat, it.lon, p.lat, p.lon) <= SIGNAL_GROUP_M }) continue
            kept += p
            if (kept.size >= SIGNAL_MAX_BOXES) break
        }
        return kept.map { POIResult(SIGNAL_NAME, it.lat, it.lon, "", category = SIGNAL_CATEGORY) }
    }

    /**
     * 지금 정면 기준 시계 방향. 나침반이 없거나 불량이거나, 대상이 위치 오차 원 안이면 null —
     * 그때는 방향을 지어내지 않고 거리만 말한다.
     */
    private fun clockHourTo(f: FusedFix, lat: Double, lon: Double, distanceM: Float): Int? {
        if (!heading.hasSensor || heading.isUnreliable) return null
        if (distanceM < f.accuracyM * DIRECTION_MIN_DISTANCE_RATIO) return null
        val h = heading.currentTrueHeadingDeg() ?: return null
        return clockHour(relativeBearingDeg(initialBearingDeg(f.lat, f.lon, lat, lon), h))
    }

    /**
     * 실기 진단용 — 키워드마다 가장 가까운 3곳과 거리를 logcat 에 남긴다 (디버그 빌드만).
     * "주변 장소가 없다"가 또 나오면 `adb logcat -s NEARBY_DIAG` 로 원인을 바로 본다:
     *   n=0 이면 검색 자체가 비었고, n>0 인데 거리가 수백 m 면 TMap 정렬/반경 문제다.
     */
    private fun logSearchResults(f: FusedFix, groups: List<Pair<String, List<POIResult>?>>) {
        if (!BuildConfig.DEBUG) return
        Log.d(DIAG_TAG, "fix=${f.lat},${f.lon} acc=${f.accuracyM}m samples=${f.sampleCount}")
        for ((keyword, pois) in groups) {
            if (pois == null) {
                Log.d(DIAG_TAG, "keyword=$keyword FAILED")
                continue
            }
            val nearest = pois
                .map { it to distanceBetween(f.lat, f.lon, it.lat, it.lon) }
                .sortedBy { it.second }
                .take(3)
                .joinToString { (p, d) -> "${p.name}(${d.toInt()}m,${p.category ?: "-"})" }
            Log.d(DIAG_TAG, "keyword=$keyword n=${pois.size} nearest=$nearest")
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /**
         * 카테고리별로 따로 검색한다 — 한 번에 묶으면 하나가 0건일 때 나머지까지 안 나온다는 보고가 있다.
         * 시각장애인 보행에서 기준점이 되는 곳 위주. 늘리면 호출 수가 그만큼 는다.
         */
        private val SEARCH_CATEGORIES = listOf(
            "편의점", "카페", "음식점", "약국", "병원", "은행", LocationAnchors.KEYWORD_BUS_STOP, "지하철역",
        )

        /** 위치 설명(기준점)용으로만 검색하는 키워드. 결과는 걸러서 박스에도 넣는다. */
        private val ANCHOR_ONLY_KEYWORDS = listOf(
            LocationAnchors.KEYWORD_SUBWAY_EXIT, LocationAnchors.KEYWORD_INTERSECTION,
        )

        private const val DIAG_TAG = "NEARBY_DIAG"

        /**
         * 방향을 믿고 말할 수 있는 최소 거리 = 위치 오차 × 이 값.
         * 방향 오차 ≈ asin(오차 / 거리) — 2배면 30°(시계 한 칸), 1배면 90°.
         * 이보다 가까우면 기준점은 거리만, 박스는 단서를 붙여 말하고 방향 진동은 켜지 않는다.
         */
        private const val DIRECTION_MIN_DISTANCE_RATIO = 2f

        // 위치 권한 안내 (안드로이드 권한 흐름 전용이라 shared 의 NearbyPhrases 가 아니라 여기에 둔다)
        private const val LOCATION_DENIED =
            "위치 권한이 필요합니다. 다시 찾기를 누르면 권한을 다시 요청합니다."
        private const val LOCATION_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있어 주변 안내를 할 수 없습니다. 다시 찾기를 눌러 정확한 위치를 허용해 주세요."
        private const val LOCATION_BLOCKED =
            "위치 권한이 거부되어 있어 다시 물을 수 없습니다. 다시 찾기를 누르면 설정 화면이 열립니다. 권한에서 위치를 허용해 주세요."
        private const val LOCATION_BLOCKED_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 다시 찾기를 누르면 설정 화면이 열립니다. 권한의 위치 항목에서 정확한 위치 사용을 켜 주세요."
        private const val OPENING_SETTINGS =
            "설정 화면을 엽니다. 권한에서 위치를 허용한 뒤 돌아오세요."
        private const val PERMISSION_DIALOG_HINT =
            "곧 위치 권한을 묻는 창이 뜹니다. 허용을 눌러 주세요."
        private const val STATE_SENT_TO_SETTINGS = "sentToSettings"
        private const val STATE_PERMISSION_BLOCKED = "locationPermissionBlocked"

        /** TMap 동시 요청 상한 — OkHttp 의 서버당 동시 요청(5)보다 작게. refresh() 의 주석 참조. */
        private const val MAX_CONCURRENT_REQUESTS = 4

        /** 검색 전체 마감(줄 서는 시간 포함). 요청 하나의 제한(10초)보다 조금 길게. */
        private const val SEARCH_DEADLINE_MS = 12_000L

        /** 다른 화면에 다녀왔을 때 이보다 오래된 위치면 다시 찾는다 (보통 걸음으로 약 70m). */
        private const val STALE_FIX_ON_RETURN_MS = 60_000L

        /** 박스를 눌렀을 때 이보다 오래된 위치면 방향을 말하지 않는다. 제자리에서 목록을 천천히 듣는 시간은 허용. */
        private const val STALE_FIX_ON_TAP_MS = 180_000L

        // 보행 신호등 박스
        private const val SIGNAL_NAME = "보행 신호등"
        private const val SIGNAL_CATEGORY = "횡단보도 신호등"
        private const val SIGNAL_SEARCH_RADIUS_M = 100.0
        private const val SIGNAL_GROUP_M = 15f
        private const val SIGNAL_MAX_BOXES = 3
    }
}
