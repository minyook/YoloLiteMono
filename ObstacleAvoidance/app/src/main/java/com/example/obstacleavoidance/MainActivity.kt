package com.example.obstacleavoidance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ObstacleAvoidance"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    private lateinit var objectDetector: ObjectDetector
    private lateinit var depthEstimator: DepthEstimator

    private var isDetecting = false
    private var isProcessingFrame = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── 음성 제어를 위한 변수 (수정됨: 에러 해결) ──
    private var lastSpokenTime = 0L
    private val MIN_SPEAK_INTERVAL = 2500L // 일반 안내 간격 2.5초

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        overlayView = findViewById(R.id.overlayView)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // AI 모델 초기화
        objectDetector = ObjectDetector(this)
        depthEstimator = DepthEstimator(this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                toggleDetection()
            }
            true
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.KOREAN)
            tts.setSpeechRate(1.3f)
            isTtsReady = true
            speak("앱이 준비되었습니다. 화면을 터치하세요.", TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun toggleDetection() {
        isDetecting = !isDetecting
        if (isDetecting) {
            statusText.text = "🔍 탐지 중..."
            statusText.setBackgroundColor(Color.parseColor("#8000AA00"))
            speak("탐지를 시작합니다", TextToSpeech.QUEUE_FLUSH)
        } else {
            statusText.text = "⏸ 정지됨"
            statusText.setBackgroundColor(Color.parseColor("#80AA0000"))
            overlayView.clearResults()
            speak("탐지를 정지합니다", TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun speak(message: String, queueMode: Int) {
        if (!isTtsReady) return
        tts.speak(message, queueMode, null, "obstacle_${System.currentTimeMillis()}")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isDetecting || isProcessingFrame) {
            imageProxy.close()
            return
        }
        isProcessingFrame = true

        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap == null) {
            imageProxy.close()
            isProcessingFrame = false
            return
        }

        scope.launch(Dispatchers.Default) {
            try {
                // YOLO와 Depth 추론 실행 (bitmap.recycle()은 finally에서 수행)
                val detections = objectDetector.detect(bitmap)
                val depthMap = depthEstimator.estimateDepth(bitmap)

                if (detections.isNotEmpty() && depthMap != null) {
                    val results = combineResults(detections, depthMap, bitmap)

                    withContext(Dispatchers.Main) {
                        overlayView.setResults(results, bitmap.width, bitmap.height)
                        announceDanger(results)
                    }
                } else {
                    withContext(Dispatchers.Main) { overlayView.clearResults() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "프레임 처리 중 오류: ${e.message}")
            } finally {
                // 비트맵 해제 및 프레임 종료 처리
                bitmap.recycle()
                isProcessingFrame = false
                imageProxy.close()
            }
        }
    }

    private fun combineResults(detections: List<DetectionResult>, depthMap: FloatArray, bitmap: Bitmap): List<ObstacleInfo> {
        return detections.map { det ->
            val dist = depthEstimator.getDistanceForBbox(depthMap, det.x1, det.y1, det.x2, det.y2, bitmap.width, bitmap.height)
            ObstacleInfo(det.label, det.confidence, det.x1, det.y1, det.x2, det.y2, dist)
        }
    }

    // ── 가장 위험한(가까운) 객체 하나만 단계별로 안내 ──
    private fun announceDanger(obstacles: List<ObstacleInfo>) {
        if (obstacles.isEmpty() || !isTtsReady) return

        // 1. 유효한 거리가 측정된 객체 중 가장 가까운 것 찾기
        val nearest = obstacles.filter { it.distanceMeters > 0 }.minByOrNull { it.distanceMeters } ?: return

        val distance = nearest.distanceMeters
        val now = System.currentTimeMillis()

        // 2. 거리별 위험 단계 분류 (보정된 수치 적용)
        val (dangerLevel, queueMode) = when {
            distance < 1.0f -> "위험" to TextToSpeech.QUEUE_FLUSH    // 1m 이내: 즉시 중단 후 경고
            distance < 2.5f -> "경고" to TextToSpeech.QUEUE_ADD      // 2.5m 이내: 순차 안내
            else -> "주의" to TextToSpeech.QUEUE_ADD                // 그 외
        }

        // 3. 메시지 생성
        val labelKorean = LabelTranslator.toKorean(nearest.label)
        val message = "${labelKorean}, ${String.format("%.1f", distance)}미터 앞, ${dangerLevel} 단계입니다."

        // 4. 출력 제어 로직
        if (dangerLevel == "위험") {
            speak(message, queueMode)
            lastSpokenTime = now
        } else if (now - lastSpokenTime > MIN_SPEAK_INTERVAL) {
            speak(message, queueMode)
            lastSpokenTime = now
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) { null }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts.stop()
        tts.shutdown()
        objectDetector.close()
        depthEstimator.close()
    }
}