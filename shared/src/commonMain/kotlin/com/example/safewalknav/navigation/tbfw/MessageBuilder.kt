package com.example.safewalknav.navigation.tbfw

/**
 * 안내 문장 생성기.
 *
 * 2026-05-23 — TrustBasedNavigator 폐기에 따라 GPS 점프 / waypoint 통과 안내 함수 제거.
 *   현재 노출 메서드:
 *     - buildAnnotationAnnounce: RouteAnnotator 의 PathAnnotation 음성화
 *     - buildInitialHeadingMessage: 출발 직전 회전 안내 (iOS HeadingGuide 사용)
 *     - buildFlatPosePromptMessage: 스마트폰 자세 안내 (iOS HeadingGuide 사용)
 *
 * 모든 함수는 순수 함수 — 외부 상태 의존 없음.
 */
object MessageBuilder {

    /**
     * RouteAnnotator 가 만든 PathAnnotation 한 건에 대한 음성 안내 문장.
     * STRAIGHT 또는 direction == NONE 이면 빈 문자열을 돌려준다 (안내 안 함).
     */
    fun buildAnnotationAnnounce(annotation: PathAnnotation): String {
        val dir = when (annotation.direction) {
            TurnDirection.LEFT -> "왼쪽"
            TurnDirection.RIGHT -> "오른쪽"
            TurnDirection.UTURN -> "뒤쪽"
            TurnDirection.STRAIGHT -> return ""
            TurnDirection.NONE -> return ""
        }
        return when (annotation.type) {
            PathSegmentType.SLIGHT_CURVE ->
                "곧 ${dir}으로 완만히 휘어집니다."
            PathSegmentType.CURVE ->
                "곧 ${dir}으로 휘어집니다."
            PathSegmentType.SLIGHT_TURN ->
                "곧 ${dir}으로 살짝 꺾습니다."
            PathSegmentType.TURN ->
                "곧 ${dir}으로 꺾습니다."
            PathSegmentType.SHARP_TURN ->
                "곧 ${dir}으로 크게 꺾습니다."
            PathSegmentType.INTERNAL_CURVE ->
                "곧 ${dir}으로 휘어집니다."
            PathSegmentType.STRAIGHT -> ""
        }
    }

    /**
     * 초기 방향 안내 — 사용자가 출발 전, 어느 방향으로 돌아야 하는지 알려준다.
     *
     * @param diffDeg 목표 bearing - 현재 heading (-180 ~ +180). 양수면 오른쪽으로 돌아야 함.
     * @param tolerance 이 값 이내이면 "정면" 으로 판정.
     */
    fun buildInitialHeadingMessage(diffDeg: Double, tolerance: Double): String {
        val absDiff = kotlin.math.abs(diffDeg)
        val direction = if (diffDeg > 0) "오른쪽" else "왼쪽"
        return when {
            absDiff < tolerance -> "정면입니다. 직진하세요."
            absDiff < 45.0 -> "${direction}으로 약 ${absDiff.toInt()}도 돌아주세요."
            absDiff < 135.0 -> "${direction}으로 약 90도 돌아주세요."
            else -> "뒤로 돌아주세요."
        }
    }

    /** 자세 안내 — 평평하지 않으면 발화. */
    fun buildFlatPosePromptMessage(): String =
        "스마트폰을 손바닥 위에 평평하게 들어주세요."
}
