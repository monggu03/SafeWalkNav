package com.example.safewalknav.navigation

/**
 * 구간 위험도 비용 함수.
 *
 * TMap LineString 의 roadType / facilityType / name 을 종합해서 위험 등급을 산출.
 * 사용자 안내 정책(사전 안내 거리, TTS 빈도, 출발 시 요약 안내)에 활용된다.
 *
 * ⚠️ 코드 매핑은 2026-05-15 강남역 raw response 관찰 기반 추정값.
 *    아직 확인되지 않은 패턴:
 *      - roadType: 21 (보행자도로/일반인도) 만 다수 관찰. 22 가 첫 구간에 등장.
 *        계단(4/5/11)/육교(9)/지하도(10) 등은 raw 수집되지 않음 → 가설 단계.
 *      - facilityType: "11"(차도 인접) / "15"(보행자도로) / "17"(인도) 확인됨.
 *        공식 문서의 "16=계단", "12=육교" 는 미관찰 → 보수적으로 적용.
 *    계단/육교 포함 경로로 추가 raw 응답을 받아 검증할 것.
 */
object RiskScoreCalculator {

    /**
     * LineString properties 기반 기본 위험도 산출.
     * 횡단보도 직후 승급은 [upgradeForCrosswalk] 에서 별도 후처리.
     */
    fun calculate(roadType: Int, facilityType: Int, name: String): RiskLevel {
        // 1순위: facilityType 의 명확한 위험 시설 (공식 코드표 기준 — 미관찰이지만 보수적으로 처리)
        when (facilityType) {
            16 -> return RiskLevel.DANGER  // 계단
            12 -> return RiskLevel.DANGER  // 육교
            11 -> {
                // ⚠️ 공식 코드표상 "지하보도 진입" 이지만 실제 응답에서는
                //    영동대로/테헤란로 같은 일반 차도 인접 인도에서도 다수 관찰됨.
                //    DANGER 로 단정하지 않고 아래 규칙으로 fallthrough.
            }
        }

        // 2순위: 텍스트 단서 — TMap 이 description/name 에 명시할 수 있음
        when {
            name.contains("계단") -> return RiskLevel.DANGER
            name.contains("육교") -> return RiskLevel.DANGER
            name.contains("지하도") -> return RiskLevel.DANGER
            name.contains("지하보도") -> return RiskLevel.DANGER
        }

        // 3순위: roadType 기반 일반 분류 (관찰값 기준)
        //   roadType 21 == 보행자도로/일반인도 → name 으로 추가 분기
        //   roadType 22 == 미확정 (raw 에서 두 번만 등장)
        return when {
            name == "보행자도로" -> RiskLevel.SAFE
            name.isBlank() -> RiskLevel.NORMAL
            else -> RiskLevel.NORMAL  // "테헤란로", "영동대로" 등 명명된 도로 — 차도 옆 인도로 가정
        }
    }

    /**
     * 횡단보도 waypoint 직후 segment 는 한 단계 승급.
     *
     * Parser 에서 segments 빌드를 끝낸 뒤,
     *   "fromWaypointIndex 의 waypoint 가 CROSSWALK 인 segment" 에 대해 호출.
     */
    fun upgradeForCrosswalk(baseLevel: RiskLevel): RiskLevel = when (baseLevel) {
        RiskLevel.SAFE   -> RiskLevel.CAUTION
        RiskLevel.NORMAL -> RiskLevel.CAUTION
        else             -> baseLevel  // CAUTION, DANGER 는 유지
    }
}
