package com.example.safewalknav.ml

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 신호등 검출 결과를 카메라 PreviewView 위에 바운딩박스로 시각화하는 오버레이.
 *
 * Capstone 발표/시연용 — 실 사용자(시각장애인)는 카메라 화면을 보지 않으므로 production 가치가 없지만,
 * 심사위원/교수님이 "정말 모델이 신호등을 인식하고 있는가" 를 즉시 시각적으로 확인할 수 있게 한다.
 * 기본적으로 `BuildConfig.DEBUG` 빌드에서만 [MainActivity] 가 cameraPreviewContainer 에 attach 한다.
 *
 * 좌표계 노트:
 *   [TrafficLightDetector] 의 bbox 는 0~1 정규화 좌표 — TFLite 입력 640×640 기준.
 *   PreviewView 가 FILL_CENTER 로 카메라 프레임을 채우므로 카메라 종횡비와 화면 종횡비가 다르면
 *   가장자리에 약간의 crop 오차가 발생할 수 있다 (≈5~10%). 시연 정확도로는 충분하며,
 *   더 정확한 매핑이 필요하면 PreviewView 의 outputTransform 행렬을 인자로 받도록 확장.
 *
 * 색상 / 라벨:
 *   class 0 (red_pedestrian)  → 빨간 박스 + "빨간불" 라벨
 *   class 1 (green_pedestrian) → 초록 박스 + "초록불" 라벨
 *   (신뢰도 % 는 표시하지 않음 — 시연 시 시각적 노이즈 회피)
 *
 * 사용:
 *   val overlay = BoundingBoxOverlay(context).apply {
 *       layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
 *   }
 *   cameraPreviewContainer.addView(overlay)   // PreviewView 위에 한 겹
 *   ...
 *   overlay.setDetections(detections)         // onTrafficLightDetected 콜백에서 호출 (UI 스레드)
 *   overlay.clear()                           // 또는 빈 리스트
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var detections: List<TrafficLightDetection> = emptyList()

    private val density: Float = resources.displayMetrics.density

    // 2026-06-06 시각 강화 미세 조정 — 선 두께 10 → 6dp, 라벨 폰트 22 → 18sp.
    // 10dp/22sp 가 너무 두꺼워서 작은 신호등 박스를 거의 가렸음. 6dp/18sp 가 균형.
    private val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF3333")
        strokeWidth = 6f * density
    }

    private val greenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33CC33")
        strokeWidth = 6f * density
    }

    private val redLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF3333")
    }

    private val greenLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33CC33")
    }

    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 18f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        // 터치 가로채지 않음 — 밑의 rootLayout long-press 가 정상 작동해야 함.
        isClickable = false
        isFocusable = false
        // TalkBack 무시 — 시각장애인 사용자에게 의미 없음.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 투명 배경 — PreviewView 영상이 보여야 함.
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 새 검출 결과를 받아 그린다. 빈 리스트를 넘기면 박스가 사라진다.
     * 반드시 UI 스레드에서 호출 (CameraX analyzer 콜백은 백그라운드 스레드라
     * 호출 직전에 `runOnUiThread` 로 진입해야 함 — MainActivity 가 이미 보장).
     */
    fun setDetections(newDetections: List<TrafficLightDetection>) {
        detections = newDetections
        invalidate()
    }

    /** 명시적으로 박스를 지운다. NAVIGATING 종료 / zone EXIT 시 호출. */
    fun clear() {
        if (detections.isEmpty()) return
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (d in detections) {
            val left = (d.bbox.left * w).coerceIn(0f, w)
            val top = (d.bbox.top * h).coerceIn(0f, h)
            val right = (d.bbox.right * w).coerceIn(0f, w)
            val bottom = (d.bbox.bottom * h).coerceIn(0f, h)
            if (right - left < 1f || bottom - top < 1f) continue

            val isRed = d.classId == 0
            val stroke = if (isRed) redStroke else greenStroke
            val labelBg = if (isRed) redLabelBg else greenLabelBg

            // 박스
            canvas.drawRect(left, top, right, bottom, stroke)

            // 라벨 — 박스 좌상단 위에 배치 (위쪽 공간 없으면 박스 안 위쪽에 표시)
            val label = if (isRed) "빨간불" else "초록불"
            val textW = labelText.measureText(label)
            val textH = labelText.descent() - labelText.ascent()
            val padX = 6f * density
            val padY = 3f * density

            val bgW = textW + padX * 2
            val bgH = textH + padY * 2
            val bgLeft = left
            val bgTop = if (top - bgH >= 0f) top - bgH else top
            val bgRight = bgLeft + bgW
            val bgBottom = bgTop + bgH

            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBg)
            canvas.drawText(
                label,
                bgLeft + padX,
                bgBottom - padY - labelText.descent(),
                labelText,
            )
        }
    }
}
