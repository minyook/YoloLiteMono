package com.example.obstacleavoidance

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer

/**
 * ============================================================
 * LiteMono 연동 및 거리 측정 클래스
 * ============================================================
 * - LiteMono ONNX 모델로 깊이 맵(Depth Map) 생성
 * - YOLO 탐지 박스 영역의 깊이 값을 추출하여 거리 계산
 * - 시차(Disparity)를 미터 단위의 절대 거리로 변환
 * ============================================================
 */
class DepthEstimator(private val context: Context) {

    companion object {
        private const val TAG = "DepthEstimator"

        // ── 모델 설정 ──
        const val MODEL_FILE = "lite_mono.onnx"
        const val DEPTH_WIDTH = 640       // 모델 입력 너비
        const val DEPTH_HEIGHT = 192      // 모델 입력 높이

        // ── 깊이 → 거리 변환 파라미터 (보정됨) ──
        // 1m 미만을 8~9m로 인식하던 문제를 해결하기 위해 5.4f에서 0.7f로 하향 조정
        // 실제 거리와 차이가 나면 이 값을 미세 조정하세요 (예: 0.5f ~ 0.9f)
        private const val DEPTH_SCALE_FACTOR = 0.7f
        private const val MIN_DEPTH = 0.1f             // 최소 인식 거리 (미터)
        private const val MAX_DEPTH = 20.0f            // 최대 인식 거리 (미터)
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // ImageNet 데이터셋의 평균 및 표준편차 (전처리용)
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    init {
        initModel()
    }

    // =========================================================
    //  모델 초기화 (NNAPI 가속 포함)
    // =========================================================
    private fun initModel() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_FILE).readBytes()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addNnapi() // NPU 가속 시도
                } catch (e: Exception) {
                    Log.d(TAG, "NNAPI 가속을 사용할 수 없어 CPU 모드로 실행합니다.")
                }
            }

            ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
            Log.d(TAG, "LiteMono 모델 초기화 성공")

        } catch (e: Exception) {
            Log.e(TAG, "LiteMono 모델 초기화 실패: ${e.message}")
        }
    }

    // =========================================================
    //  깊이 추정 메인 함수
    // =========================================================
    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        // 비트맵 유효성 검사 (Recycled 에러 방지)
        if (bitmap.isRecycled) {
            Log.e(TAG, "이미 해제된 비트맵으로 깊이 추정을 시도했습니다.")
            return null
        }

        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        try {
            val startTime = System.currentTimeMillis()

            // 1. 전처리 (Resize & Normalization)
            val inputTensor = preprocessForDepth(bitmap, env) ?: return null

            // 2. 모델 추론
            val inputName = session.inputNames.first()
            val results = session.run(mapOf(inputName to inputTensor))

            // 3. 결과 추출 및 변환
            val outputTensor = results[0] as OnnxTensor
            val rawDisparity = outputTensor.floatBuffer.array()
            val depthMap = convertToMeters(rawDisparity)

            inputTensor.close()
            results.close()

            Log.d(TAG, "깊이 추론 완료: ${System.currentTimeMillis() - startTime}ms")
            return depthMap

        } catch (e: Exception) {
            Log.e(TAG, "깊이 추정 도중 오류 발생: ${e.message}")
            return null
        }
    }

    // =========================================================
    //  이미지 전처리 로직
    // =========================================================
    private fun preprocessForDepth(bitmap: Bitmap, env: OrtEnvironment): OnnxTensor? {
        if (bitmap.isRecycled) return null

        // 640x192 크기로 리사이징
        val resized = Bitmap.createScaledBitmap(bitmap, DEPTH_WIDTH, DEPTH_HEIGHT, true)
        val pixels = IntArray(DEPTH_WIDTH * DEPTH_HEIGHT)
        resized.getPixels(pixels, 0, DEPTH_WIDTH, 0, 0, DEPTH_WIDTH, DEPTH_HEIGHT)

        val inputData = FloatArray(3 * DEPTH_WIDTH * DEPTH_HEIGHT)
        val channelSize = DEPTH_WIDTH * DEPTH_HEIGHT

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // R, G, B 채널별 ImageNet 정규화
            inputData[i] = (((pixel shr 16) and 0xFF) / 255.0f - mean[0]) / std[0]
            inputData[i + channelSize] = (((pixel shr 8) and 0xFF) / 255.0f - mean[1]) / std[1]
            inputData[i + 2 * channelSize] = ((pixel and 0xFF) / 255.0f - mean[2]) / std[2]
        }

        val shape = longArrayOf(1, 3, DEPTH_HEIGHT.toLong(), DEPTH_WIDTH.toLong())
        resized.recycle() // 임시 리사이즈 비트맵 즉시 해제

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)
    }

    // =========================================================
    //  시차(Disparity) -> 실제 거리(Meter) 변환
    // =========================================================
    private fun convertToMeters(rawDisparity: FloatArray): FloatArray {
        val depthMap = FloatArray(rawDisparity.size)
        for (i in rawDisparity.indices) {
            val disp = rawDisparity[i]
            // 거리는 시차에 반비례함 (depth = scale / disparity)
            val depth = if (disp > 0.001f) DEPTH_SCALE_FACTOR / disp else MAX_DEPTH
            depthMap[i] = depth.coerceIn(MIN_DEPTH, MAX_DEPTH)
        }
        return depthMap
    }

    // =========================================================
    //  특정 Bounding Box 영역의 중심 거리 추출
    // =========================================================
    fun getDistanceForBbox(
        depthMap: FloatArray,
        x1: Float, y1: Float, x2: Float, y2: Float,
        imgWidth: Int, imgHeight: Int
    ): Float {
        // 좌표 스케일링 (원본 이미지 -> 640x192 깊이맵)
        val scaleX = DEPTH_WIDTH.toFloat() / imgWidth
        val scaleY = DEPTH_HEIGHT.toFloat() / imgHeight

        val dx1 = (x1 * scaleX).toInt().coerceIn(0, DEPTH_WIDTH - 1)
        val dy1 = (y1 * scaleY).toInt().coerceIn(0, DEPTH_HEIGHT - 1)
        val dx2 = (x2 * scaleX).toInt().coerceIn(0, DEPTH_WIDTH - 1)
        val dy2 = (y2 * scaleY).toInt().coerceIn(0, DEPTH_HEIGHT - 1)

        // 박스 중앙의 20% 영역만 샘플링하여 평균값 계산 (배경 노이즈 제거)
        val centerRatio = 0.2f
        val boxWidth = dx2 - dx1
        val boxHeight = dy2 - dy1

        val startX = (dx1 + boxWidth * (0.5f - centerRatio / 2)).toInt().coerceIn(0, DEPTH_WIDTH - 1)
        val startY = (dy1 + boxHeight * (0.5f - centerRatio / 2)).toInt().coerceIn(0, DEPTH_HEIGHT - 1)
        val endX = (dx1 + boxWidth * (0.5f + centerRatio / 2)).toInt().coerceIn(0, DEPTH_WIDTH - 1)
        val endY = (dy1 + boxHeight * (0.5f + centerRatio / 2)).toInt().coerceIn(0, DEPTH_HEIGHT - 1)

        val depths = mutableListOf<Float>()
        for (y in startY..endY) {
            for (x in startX..endX) {
                val index = y * DEPTH_WIDTH + x
                if (index in depthMap.indices) {
                    depths.add(depthMap[index])
                }
            }
        }

        if (depths.isEmpty()) return -1f

        // 중앙값(Median)을 사용하여 튀는 값 방지
        depths.sort()
        return depths[depths.size / 2]
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "리소스 해제 오류: ${e.message}")
        }
    }
}