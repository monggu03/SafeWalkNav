package com.example.safewalknav.navigation

/**
 * 현재 위치에서 목적지 방향을 시계 방향으로 안내.
 *
 * 사용자의 진행 방향(userBearing)을 12시 기준으로 두고, 목적지가 어느 시간 위치에 있는지 계산.
 * 예: userBearing = 0° (북쪽 진행 중), 목적지가 동쪽(90°)에 있으면 → "3시"
 *
 * KMM commonMain — Android/iOS 공통.
 *
 * @param currentLat 현재 위도
 * @param currentLon 현재 경도
 * @param targetLat 목적지 위도
 * @param targetLon 목적지 경도
 * @param userBearing 사용자 진행 방향 (0~360°)
 * @return "12시", "3시" 등 시계 방향 문자열
 */
fun getClockDirection(
    currentLat: Double,
    currentLon: Double,
    targetLat: Double,
    targetLon: Double,
    userBearing: Float
): String {
    // 목적지까지의 절대 방위각
    val absoluteBearing = bearing(currentLat, currentLon, targetLat, targetLon)

    // 사용자 진행 방향 기준 상대 각도
    var relativeBearing = absoluteBearing - userBearing
    if (relativeBearing < 0) relativeBearing += 360f
    if (relativeBearing >= 360) relativeBearing -= 360f

    // 시계 방향으로 변환 (30° = 1시간, +15° offset 으로 가장 가까운 시간으로 반올림)
    val clockHour = ((relativeBearing + 15) / 30).toInt() % 12
    val hour = if (clockHour == 0) 12 else clockHour

    return "${hour}시"
}
