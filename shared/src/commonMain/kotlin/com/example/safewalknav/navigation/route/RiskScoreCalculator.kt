package com.example.safewalknav.navigation.route

import com.example.safewalknav.navigation.tmap.RiskLevel

/**
 * Segment 위험도 분류 (3단계 단순화 버전).
 *
 * TMap LineString 의 name 으로 segment 의 보행 환경을 판별한다.
 *
 *   SAFE    — name == "보행자도로"  (순수 인도, 안내 빈도 낮춤)
 *   NORMAL  — name 에 차도명 존재    (테헤란로/영동대로 등 — 표준 안내)
 *   CAUTION — 예약 (현재 산출 안 됨, 향후 확장용)
 *
 * 횡단보도는 segment 단위가 아니라 waypoint(pointType == "CROSSWALK") 단위로 판정.
 * "횡단보도 직후 인도 19m" 는 SAFE 로 둔다 — 위험한 건 건너는 순간뿐.
 *
 * ⚠️ 코드 매핑 검증 상태:
 *    - 2026-05-15 강남역 raw response 기준. roadType=21 다수, 22 소수 관찰.
 *    - facilityType 은 향후 계단/육교 경로 확보 시 활용 예정 (현재 미사용).
 */
object RiskScoreCalculator {

    /**
     * Segment 위험도 계산.
     *
     * 현재는 name 만으로 분기. roadType/facilityType 인자는 향후 확장 위해 유지.
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculate(roadType: Int, facilityType: Int, name: String): RiskLevel = when {
        name == "보행자도로" -> RiskLevel.SAFE
        name.isNotEmpty()    -> RiskLevel.NORMAL
        else                 -> RiskLevel.NORMAL  // 도로명 없는 짧은 연결 segment
    }
}