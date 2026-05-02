package com.example.safewalknav.ml

import androidx.camera.core.ImageAnalysis
import org.opencv.core.Mat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * 의존성 리졸브 검증용 임시 파일.
 *
 * PR-1 에서 추가한 다음 의존성이 KMM androidApp 모듈에서 정상 import 되는지
 * 컴파일 시간에 확인. 실제 호출은 안 함.
 *   - CameraX (androidx.camera.*)
 *   - OpenCV (org.opencv.*)
 *   - TensorFlow Lite + GPU delegate (org.tensorflow.lite.*)
 *
 * 다음 단계(PR-2: 점자블록 검출, PR-3: 신호등 추론) 완료 후 이 파일은 제거 예정.
 *
 * 주: gpu-delegate-plugin 의존성은 lite-gpu:2.14 와 API 호환성 충돌이 있어 제거.
 *      대신 GpuDelegate 를 직접 생성하고 try/catch 로 미지원 디바이스 처리.
 */
internal object MLCameraProbe {
    /**
     * TFLite GPU delegate 옵션 생성 (실제 호출 안 함).
     * 실 사용 시점에는 GpuDelegate 생성을 try/catch 로 감싸서 미지원 디바이스 fallback.
     */
    fun newInterpreterOptions(): Interpreter.Options =
        Interpreter.Options().apply {
            addDelegate(GpuDelegate())
            setNumThreads(4)
        }

    /** OpenCV Mat 객체 생성 가능 여부 (의존성 리졸브 검증) */
    fun newMat(): Mat = Mat()

    /** CameraX ImageAnalysis 빌더 접근 가능 여부 */
    fun newImageAnalysisBuilder(): ImageAnalysis.Builder = ImageAnalysis.Builder()
}
