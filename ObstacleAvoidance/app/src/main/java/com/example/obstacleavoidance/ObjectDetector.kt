package com.example.obstacleavoidance

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.Collections

/**
 * ============================================================
 * YOLO11 ONNX 추론 구현 (디버깅 로그 및 Threshold 수정 버전)
 * ============================================================
 */
class ObjectDetector(private val context: Context) {

    companion object {
        private const val TAG = "YOLO" // 로그 태그를 YOLO로 통일

        // ── 모델 설정 ──
        const val MODEL_FILE = "yolo11.onnx"
        const val INPUT_SIZE = 640

        // 탐지 확률 임계값: 테스트를 위해 0.2f로 낮춤 (기존 0.45f)
        const val CONFIDENCE_THRESHOLD = 0.2f
        const val IOU_THRESHOLD = 0.2f
        const val MAX_DETECTIONS = 20
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private val labels = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        initModel()
    }

    private fun initModel() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_FILE).readBytes()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addNnapi()
                    Log.d(TAG, "NNAPI 가속 활성화 성공")
                } catch (e: Exception) {
                    Log.d(TAG, "NNAPI 미지원, CPU 모드 실행")
                }
            }
            ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
            Log.d(TAG, "YOLO11 모델 로드 완료: $MODEL_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "YOLO11 모델 초기화 실패: ${e.message}", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        // [로그 1] 입력 확인
        Log.d(TAG, "1. 탐지 시작 - 입력 비트맵: ${bitmap.width}x${bitmap.height}")

        val session = ortSession ?: run {
            Log.e(TAG, "세션이 초기화되지 않음")
            return emptyList()
        }
        val env = ortEnvironment ?: return emptyList()

        try {
            val startTime = System.currentTimeMillis()

            // Step 1: 전처리
            val (inputTensor, scale, padX, padY) = preprocessImage(bitmap, env)

            // Step 2: 추론 실행
            val inputName = session.inputNames.first()
            Log.d(TAG, "2. 모델 추론 실행 중...")
            val results = session.run(mapOf(inputName to inputTensor))

            // Step 3: 출력 수신
            Log.d(TAG, "3. 모델 응답 받음")
            val outputTensor = results[0] as OnnxTensor
            val outputData = outputTensor.floatBuffer.array()

            // Step 4: 후처리 (좌표 복원 및 NMS)
            val detections = postProcess(
                outputData, scale, padX, padY,
                bitmap.width, bitmap.height
            )

            val elapsed = System.currentTimeMillis() - startTime
            // [로그 4] 최종 결과 확인
            Log.d(TAG, "4. 최종 탐지된 물체 개수: ${detections.size} (시간: ${elapsed}ms)")

            inputTensor.close()
            results.close()

            return detections

        } catch (e: Exception) {
            Log.e(TAG, "객체 탐지 중 에러 발생: ${e.message}", e)
            return emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap, env: OrtEnvironment): PreprocessResult {
        val scale = minOf(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        val padX = (INPUT_SIZE - newWidth) / 2f
        val padY = (INPUT_SIZE - newHeight) / 2f

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val paddedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawColor(android.graphics.Color.GRAY)
        canvas.drawBitmap(resizedBitmap, padX, padY, null)

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        paddedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val inputData = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            val pixel = pixels[i]
            inputData[i] = ((pixel shr 16) and 0xFF) / 255.0f
            inputData[i + INPUT_SIZE * INPUT_SIZE] = ((pixel shr 8) and 0xFF) / 255.0f
            inputData[i + 2 * INPUT_SIZE * INPUT_SIZE] = (pixel and 0xFF) / 255.0f
        }

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)

        resizedBitmap.recycle()
        paddedBitmap.recycle()

        return PreprocessResult(tensor, scale, padX, padY)
    }

    private fun postProcess(
        outputData: FloatArray,
        scale: Float, padX: Float, padY: Float,
        origWidth: Int, origHeight: Int
    ): List<DetectionResult> {
        val numClasses = labels.size
        val numDetections = 8400
        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until numDetections) {
            // YOLO11 Output: [1, 84, 8400]
            var maxConf = 0f
            var maxIdx = -1

            for (c in 0 until numClasses) {
                val conf = outputData[(4 + c) * numDetections + i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = c
                }
            }

            if (maxConf >= CONFIDENCE_THRESHOLD) {
                val cx = outputData[0 * numDetections + i]
                val cy = outputData[1 * numDetections + i]
                val w = outputData[2 * numDetections + i]
                val h = outputData[3 * numDetections + i]

                val x1 = ((cx - w / 2f) - padX) / scale
                val y1 = ((cy - h / 2f) - padY) / scale
                val x2 = ((cx + w / 2f) - padX) / scale
                val y2 = ((cy + h / 2f) - padY) / scale

                candidates.add(
                    DetectionResult(
                        label = labels[maxIdx],
                        classId = maxIdx,
                        confidence = maxConf,
                        x1 = x1.coerceIn(0f, origWidth.toFloat()),
                        y1 = y1.coerceIn(0f, origHeight.toFloat()),
                        x2 = x2.coerceIn(0f, origWidth.toFloat()),
                        y2 = y2.coerceIn(0f, origHeight.toFloat())
                    )
                )
            }
        }
        return nms(candidates)
    }

    private fun nms(boxes: List<DetectionResult>): List<DetectionResult> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { box -> calculateIoU(best, box) > IOU_THRESHOLD }
        }
        return selected.take(MAX_DETECTIONS)
    }

    private fun calculateIoU(a: DetectionResult, b: DetectionResult): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)
        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val unionArea = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    data class PreprocessResult(val tensor: OnnxTensor, val scale: Float, val padX: Float, val padY: Float)

    fun close() {
        ortSession?.close()
        ortEnvironment?.close()
    }
}