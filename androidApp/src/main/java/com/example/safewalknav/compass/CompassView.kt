package com.example.safewalknav.compass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 보행 중 화면에 표시되는 나침반 모양 시각화.
 *
 * 두 개의 화살표를 같은 원 안에 그려 사용자 진행 방향과 도로 진행 방향을 비교한다.
 *   - 초록 화살표 (target / road) : 도로가 가야 할 방향 (NavigationManager.targetBearing)
 *   - 흰  화살표 (user / heading) : 사용자가 현재 향한 방향 (NavigationManager.compassHeading)
 *
 * 두 화살표가 같은 방향을 가리키면 잘 가고 있는 것. 어긋나 있으면 그 각도가 사용자가
 * 보정해야 할 양. WalkingDiagnostic 가 25° 이상이면 음성 발화하지만, 그 전에도 시각으로
 * 미리 확인할 수 있게 한다 (저시력자 / 보호자 / 시연자용).
 *
 * 좌표계:
 *   - 화면 위쪽이 사용자가 향한 방향 (즉 user heading 화살표는 항상 위로 그림).
 *   - 도로 화살표는 (targetBearing - userHeading) 만큼 회전해서 그림.
 *     일치 → 위쪽 같이 가리킴. 도로가 오른쪽으로 휘면 도로 화살표가 오른쪽으로 회전.
 *
 * 디자인:
 *   - 외곽: 어두운 원형 배경 (검정 + 약간 투명)
 *   - 사용자 화살표: 흰색, 굵게, 짧게 (위 방향 고정)
 *   - 도로 화살표: 초록색, 가늘게, 길게 (회전해서 가야 할 방향 표시)
 *   - 중앙: 사용자 위치 원 점
 *
 * 사용:
 *   val view = CompassView(context)
 *   view.setHeading(userAzimuth, roadBearing)
 *
 * setHeading 은 항상 UI 스레드에서 호출해야 한다.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var userHeadingDeg: Float = 0f      // 0~360, 사용자가 향한 방향
    private var targetBearingDeg: Float = 0f    // 0~360, 도로가 가야 할 방향

    private val density: Float = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")   // 검정 + 80% 알파
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#444444")
        strokeWidth = 2f * density
    }

    private val userArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val targetArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33CC33")     // 초록
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD700")     // 노랑 (사용자 현재 위치)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO  // 시각장애인은 음성 안내로
    }

    /**
     * 두 방향을 갱신해서 다시 그린다.
     * @param userHeading 사용자 현재 향한 방향 (0~360)
     * @param targetBearing 도로 진행 방향 (0~360)
     */
    fun setHeading(userHeading: Float, targetBearing: Float) {
        userHeadingDeg = normalize(userHeading)
        targetBearingDeg = normalize(targetBearing)
        invalidate()
    }

    private fun normalize(deg: Float): Float {
        var v = deg % 360f
        if (v < 0) v += 360f
        return v
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f * 0.85f

        // 배경 원
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 도로 화살표 — 사용자 향한 방향 기준 상대 각도로 그림.
        //   상대 각도 = targetBearing - userHeading
        //   화면 위쪽이 사용자가 향한 방향이므로,
        //     상대 각도 0 → 위쪽
        //     상대 +90 → 오른쪽
        //     상대 -90 → 왼쪽
        val relativeDeg = angleDiff(targetBearingDeg, userHeadingDeg)
        val targetLen = radius * 0.90f
        drawArrow(canvas, cx, cy, relativeDeg, targetLen, targetArrowPaint, isUser = false)

        // 사용자 화살표 — 항상 위쪽 (상대 0).
        val userLen = radius * 0.65f
        drawArrow(canvas, cx, cy, 0f, userLen, userArrowPaint, isUser = true)

        // 중심 점
        canvas.drawCircle(cx, cy, 6f * density, centerDotPaint)
    }

    /**
     * 두 절대 방위각 사이의 차이를 -180~+180 범위로 정규화.
     * `from` 에서 `to` 까지 시계방향이 양수.
     */
    private fun angleDiff(to: Float, from: Float): Float {
        var d = to - from
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /**
     * 중심에서 출발해 deg 방향(상대 각도, 0이 위쪽)으로 length 만큼 뻗는 화살표.
     * isUser=true 면 짧고 두꺼운 화살표 머리만, isUser=false 면 가늘고 긴 화살표.
     */
    private fun drawArrow(
        canvas: Canvas,
        cx: Float, cy: Float,
        deg: Float,
        length: Float,
        paint: Paint,
        isUser: Boolean,
    ) {
        // 상대 deg → screen 좌표계 회전 각도.
        // 화면 위쪽(=사용자 방향) 이 deg=0 이고 시계방향 +.
        // Canvas 의 sin/cos 좌표계는 오른쪽이 +x, 아래쪽이 +y.
        // 위쪽(스크린 위) = (-y), 시계방향 회전을 매핑하려면:
        //   x = sin(rad), y = -cos(rad)
        val rad = Math.toRadians(deg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()

        val tipX = cx + dx * length
        val tipY = cy + dy * length

        // 화살표 머리 폭
        val headWidth = if (isUser) length * 0.45f else length * 0.30f
        val headLength = if (isUser) length * 0.55f else length * 0.35f

        // 머리 좌우 점 — tip 으로부터 뒤쪽으로 headLength, 옆으로 headWidth/2
        // 화살표 진행 방향의 수직 단위 벡터 = (-dy, dx)
        val perpX = -dy
        val perpY = dx

        val baseX = tipX - dx * headLength
        val baseY = tipY - dy * headLength

        val leftX = baseX + perpX * headWidth / 2f
        val leftY = baseY + perpY * headWidth / 2f
        val rightX = baseX - perpX * headWidth / 2f
        val rightY = baseY - perpY * headWidth / 2f

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(path, paint)

        // 도로 화살표는 머리 뒤로 가는 꼬리(stem) 도 그려서 길이감 강조.
        if (!isUser) {
            val stem = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f * density
            }
            canvas.drawLine(cx, cy, baseX, baseY, stem)
        }
    }
}

/**
 * 두 방위각 차이의 절대값 (0~180).
 * UI 갱신 외부 헬퍼 — CompassView 자체엔 안 쓰임.
 */
fun absoluteAngleDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return abs(d)
}
