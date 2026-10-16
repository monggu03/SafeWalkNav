package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.tmap.AddressInfo
import kotlin.math.roundToInt

/**
 * 기능1(내 위치·주변 장소) 안내 문구. 두 플랫폼이 같은 말을 하도록 commonMain 에 둔다.
 *
 * 원칙
 *  - 짧게. 숫자는 대략으로("약 15미터"). GPS 오차보다 정밀한 숫자는 거짓 정확도다.
 *  - 방향을 모르면 지어내지 않는다. 거리만 말하고 이유를 짧게 덧붙인다.
 *  - "건너세요/가세요" 같은 행동 지시는 하지 않는다. 사실만 말한다.
 */
object NearbyPhrases {

    /** 방향을 말할 수 없는 이유. */
    enum class NoDirectionReason {
        /** 나침반 센서가 없는 기기 */
        NO_SENSOR,
        /** 자기장 간섭 등으로 나침반을 믿을 수 없음 — 8자 보정 필요 */
        UNRELIABLE,
        /** 위치를 확인한 지 오래됨 — 그 사이 걸었으면 거리·방향 둘 다 틀린다 */
        STALE_LOCATION,
    }

    /** 10m 미만은 1m 단위, 그 이상은 5m 단위로 반올림. */
    fun distanceText(meters: Float): String {
        if (!meters.isFinite() || meters < 1.5f) return "1미터 이내"
        val m = if (meters < 10f) meters.roundToInt() else ((meters / 5f).roundToInt() * 5)
        return "약 ${m}미터"
    }

    /** 박스(TalkBack 라벨)용 — 방향 없이 이름·분류·거리만. 방향은 누르는 순간 계산한다. */
    fun placeLabel(place: NearbyPlace): String =
        "${place.name}, ${place.category}, ${distanceText(place.distanceM)}"

    /**
     * 박스를 눌렀을 때.
     *
     * @param withinError 장소까지 거리가 위치 오차에 비해 충분히 멀지 않음 — 이때 방향은 크게 틀릴 수 있다
     *   (거리 = 오차 이면 최대 ±90°, 거리 = 오차×2 여야 ±30° 안쪽). 방향을 말하되 그 사실을 붙인다.
     *   "아주 가까워서"라고 하지 않는다 — 40m 떨어진 곳도 오차가 45m 면 여기 해당한다.
     *   방향 **앞에** "대략"을 붙인다 — 단서가 뒤에만 있으면 방향을 먼저 믿어 버린다.
     */
    fun placeDetail(
        place: NearbyPlace,
        clockHour: Int?,
        noDirectionReason: NoDirectionReason?,
        withinError: Boolean = false,
        beaconStarted: Boolean = false,
    ): String {
        val dist = distanceText(place.distanceM)
        if (clockHour != null) {
            val rough = if (withinError) "대략 " else ""
            val caveat = if (withinError) " 위치 오차에 비해 가까워서 방향이 정확하지 않을 수 있습니다." else ""
            val beacon = if (beaconStarted) " $BEACON_HINT" else ""
            return "${place.name}. $rough${clockHour}시 방향, ${dist}.$caveat$beacon"
        }
        val why = when (noDirectionReason) {
            NoDirectionReason.UNRELIABLE -> " 나침반이 부정확해 방향은 알려 드릴 수 없습니다."
            NoDirectionReason.NO_SENSOR, null -> " 이 휴대폰에서는 방향을 알 수 없습니다."
            // 거리도 그때 기준이라 말하지 않는다.
            NoDirectionReason.STALE_LOCATION -> return "${place.name}. $STALE_LOCATION_HINT"
        }
        return "${place.name}. ${dist}.$why"
    }

    const val STALE_LOCATION_HINT =
        "위치를 확인한 지 오래되어 방향과 거리가 지금과 다를 수 있습니다. 다시 찾기를 눌러 주세요."

    /** 건물 이름 (역지오코딩). 비어 있으면 null. */
    fun buildingName(info: AddressInfo?): String? =
        info?.buildingName?.trim()?.takeIf { it.isNotBlank() }

    /** 동 이름 (예: "필동2가"). 건물·기준점이 하나도 없을 때의 마지막 수단. */
    fun dongName(info: AddressInfo?): String? =
        info?.legalDong?.trim()?.takeIf { it.isNotBlank() }

    /**
     * 화면 맨 위 큰 글씨 — 한눈에(저시력자·보호자) 볼 수 있게 짧게.
     */
    fun locationHeadline(info: AddressInfo?, anchors: List<Anchor>): String =
        buildingName(info)?.let { "$it 근처" }
            ?: anchors.firstOrNull()?.let { "${it.spokenName} ${distanceText(it.distanceM)}" }
            ?: dongName(info)
            ?: "위치 이름 없음"

    /**
     * 화면 진입 시 첫 안내 — **도로명 주소를 말하지 않는다.**
     *
     *   "현재 동국대학교 신공학관 근처입니다.
     *    충무로역 1번 출구, 2시 방향 약 80미터. 동국대입구 버스정류장, 9시 방향 약 40미터."
     *
     * 건물 이름이 없으면 동 이름으로 시작한다("현재 필동2가입니다."). 기준점 방향은
     * [clockFor] 가 null 을 주면(나침반 불량·오차 원 안) 거리만 말한다.
     */
    fun currentLocation(
        info: AddressInfo?,
        anchors: List<Anchor>,
        accuracyM: Float,
        clockFor: (Anchor) -> Int?,
    ): String {
        val parts = mutableListOf<String>()
        // 오차 경고를 **맨 앞에**. 뒤에 붙이면 사용자는 이미 "○○ 근처입니다"를 믿고 들은 뒤다.
        if (accuracyM > NearbyPolicy.POOR_ACCURACY_M) {
            parts += "위치 오차가 ${distanceText(accuracyM)}라 아래 안내가 정확하지 않을 수 있습니다."
        }
        val building = buildingName(info)
        val dong = dongName(info)
        when {
            building != null -> parts += "현재 $building 근처입니다."
            dong != null -> parts += "현재 ${dong}입니다."
        }
        for (a in anchors) {
            val hour = clockFor(a)
            val dist = distanceText(a.distanceM)
            parts += if (hour != null) "${a.spokenName}, ${hour}시 방향 $dist." else "${a.spokenName}, $dist."
        }
        if (building == null && dong == null && anchors.isEmpty()) parts += "현재 위치의 이름을 찾지 못했습니다."
        return parts.joinToString(" ")
    }

    /**
     * 검색 결과 요약 — **장소 이름은 말하지 않는다.** 개수만 알리고, 박스를 눌러 듣게 한다.
     * 반경을 꽤 넓혔다면(50m 초과) 가장 가까운 곳의 거리만 덧붙인다.
     */
    fun resultSummary(result: NearbyResult): String {
        if (result.places.isEmpty()) {
            return "반경 ${result.radiusM}미터 안에서 찾은 장소가 없습니다."
        }
        val far = if (result.radiusM > 50) {
            " 가장 가까운 곳까지 ${distanceText(result.places.first().distanceM)}입니다."
        } else ""
        return "주변 장소 ${result.places.size}곳을 찾았습니다.$far 장소를 누르면 방향과 거리를 알려 드립니다."
    }

    // ── 방향 찾기 진동 ──
    /** 박스를 눌러 진동 안내가 시작될 때 덧붙인다. 멈추는 법까지 한 번에 알려 준다. */
    const val BEACON_HINT = "그쪽을 향하면 진동이 울립니다. 같은 장소를 다시 누르면 멈춥니다."
    const val BEACON_STOPPED = "방향 찾기를 멈췄습니다."
    const val BEACON_TIMEOUT = "방향 찾기를 마쳤습니다. 다시 하려면 장소를 다시 누르세요."

    const val CALIBRATE_COMPASS =
        "나침반이 부정확합니다. 휴대폰을 8자를 그리듯 천천히 돌려 주세요."

    /** 원인이 인터넷만은 아니다(검색 한도 초과·서버 오류·와이파이 로그인 화면) — 단정하지 않는다. */
    const val SEARCH_FAILED = "주변 장소를 불러오지 못했습니다. 인터넷 연결을 확인하거나 잠시 후 다시 시도해 주세요."

    /** 일부 검색만 실패 — 안 나온 종류가 "없는 것"으로 오해되지 않게 알린다. */
    const val SEARCH_PARTIAL = "일부 장소는 불러오지 못했습니다."
    const val LOCATION_FAILED = "현재 위치를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val LOCATION_PERMISSION_DENIED = "위치 권한이 필요합니다. 설정에서 위치 권한을 허용해 주세요."
    const val LOCATION_OFF = "휴대폰의 위치 기능이 꺼져 있습니다. 위치를 켠 뒤 다시 시도해 주세요."
    const val NOT_CONFIGURED = "앱 설정에 문제가 있어 주변 장소를 찾을 수 없습니다."
    const val SEARCHING = "현재 위치를 확인하고 있습니다. 잠시만 기다려 주세요."
}
