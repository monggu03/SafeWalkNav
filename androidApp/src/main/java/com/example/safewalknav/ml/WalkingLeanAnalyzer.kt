package com.example.safewalknav.ml

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * OpenCV 기반 보행 쏠림(Lean) 분석기
 * * CameraX의 ImageAnalysis 유스케이스로부터 프레임을 받아
 * YUV 영상 변환 -> ROI 추출 -> 캐니 에지 검출 -> 선 성분 분석을 통해
 * 정면 중심축 기준 좌우 보행 편차(leanDeviation)를 계산하여 콜백으로 전달합니다.
 */
class WalkingLeanAnalyzer(
    private val onLeanAnalyzed: (Float) -> Unit // 계산된 쏠림 편차(Float)를 전달할 콜백 함수
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            // 1. ImageProxy 프레임을 OpenCV 그레이스케일 Mat 객체로 변환
            val matYuv = imageToMat(image)
            val matGray = Mat()

            // YUV_420_888 포맷의 첫 번째 채널(0번)이 Y(Brightness, 그레이스케일) 채널입니다.
            Core.extractChannel(matYuv, matGray, 0)

            // 디바이스 세로 모드 촬영 대응: 시계 방향으로 90도 회전 처리
            val matRotated = Mat()
            Core.rotate(matGray, matRotated, Core.ROTATE_90_CLOCKWISE)

            val width = matRotated.width()
            val height = matRotated.height()

            // 2. ROI (관심 영역) 설정 - 보행자가 바라보는 바닥면 위주로 분석 (하단 50% 영역 지정)
            val roiRect = Rect(0, (height * 0.5).toInt(), width, (height * 0.5).toInt())
            val matRoi = Mat(matRotated, roiRect)

            // 3. 노이즈 제거를 위한 가우시안 블러 및 캐니 에지(Canny Edge) 검출
            val matBlurred = Mat()
            Imgproc.GaussianBlur(matRoi, matBlurred, Size(5.0, 5.0), 0.0)

            val matEdges = Mat()
            Imgproc.Canny(matBlurred, matEdges, 50.0, 150.0)

            // 4. 확률적 허프 선 변환 (Hough Lines P) 알고리즘으로 직선 성분 추출
            val lines = Mat()
            Imgproc.HoughLinesP(
                matEdges, lines,
                1.0, Math.PI / 180.0,
                50, 40.0, 20.0
            )

            var leftAnglesSum = 0f
            var leftLinesCount = 0
            var rightAnglesSum = 0f
            var rightLinesCount = 0

            // 5. 검출된 가이드라인 직선들의 기울기 각도 분석
            for (i in 0 until lines.rows()) {
                val line = lines.get(i, 0) ?: continue
                val x1 = line[0]
                val y1 = line[1]
                val x2 = line[2]
                val y2 = line[3]

                // 아크탄젠트를 이용한 기울기 각도 계산 (라디안 -> 디그리 수평 기준 변환)
                val angle = Math.toDegrees(atan2((y2 - y1), (x2 - x1))).toFloat()

                // 완전 수평에 가까운 무의미한 노이즈 선을 제거하고 좌/우측 가이드라인 분류
                if (angle in 20.0f..75.0f) {
                    rightAnglesSum += angle
                    rightLinesCount++
                } else if (angle in -75.0f..-20.0f) {
                    leftAnglesSum += angle
                    leftLinesCount++
                }
            }

            // 6. 좌우 균형도를 측정하여 정면 기준 편차(leanDeviation) 산출
            if (leftLinesCount > 0 && rightLinesCount > 0) {
                val avgLeft = leftAnglesSum / leftLinesCount
                val avgRight = rightAnglesSum / rightLinesCount

                // 좌우 대칭성을 기반으로 한 쏠림 편차(leanDeviation) 식 도출
                val leanDeviation = (abs(avgLeft) - abs(avgRight))

                // 결과값을 MainActivity의 콜백 람다식으로 안전하게 전달
                onLeanAnalyzed(leanDeviation)
            }

            // [중요] C++ Native 메모리 누수(OOM) 방지를 위한 OpenCV 오토 릴리즈
            matYuv.release()
            matGray.release()
            matRotated.release()
            matRoi.release()
            matBlurred.release()
            matEdges.release()
            lines.release()

        } catch (e: Exception) {
            Log.e("WalkingLeanAnalyzer", "오류 발생: ${e.message}", e)
        } finally {
            // CameraX 프레임 분석 소유권을 반환하여 다음 프레임이 들어올 수 있도록 반드시 close 호출
            image.close()
        }
    }

    /**
     * CameraX ImageProxy 구조를 OpenCV Mat 데이터 구조로 포맷팅 변환하는 헬퍼 함수
     */
    private fun imageToMat(image: ImageProxy): Mat {
        val nv21 = yuv420ToNv21(image)
        val mat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        mat.put(0, 0, nv21)
        return mat
    }

    /**
     * 안드로이드 CameraX YUV_420_888 형식을 OpenCV가 다룰 수 있는 NV21 바이트 배열로 복사 연산하는 과정
     */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}