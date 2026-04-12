package com.example.obstacleavoidance

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * ============================================================
 * 바운딩 박스 + 거리 정보 오버레이 뷰 (최적화 버전)
 * ============================================================
 * - 142라인 에러 해결: onDraw 내부의 변수 재할당 및 객체 생성 제거
 * - 성능 최적화: Paint 객체를 멤버 변수로 선언하여 메모리 효율 증대
 * ============================================================
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<ObstacleInfo> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // ── 색상 정의 ──
    private val colorDanger = Color.RED          // 2m 이내
    private val colorWarning = Color.YELLOW      // 5m 이내
    private val colorSafe = Color.GREEN          // 5m 초과

    // ── 바운딩 박스 Paint ──
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // ── 라벨 배경 Paint ──
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ── 라벨 텍스트 Paint ──
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // ── 거리 텍스트 Paint ──
    private val distancePaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // ── 거리 배경 Paint (onDraw 밖으로 이동하여 에러 해결) ──
    private val distBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0) // 반투명 검정
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 좌표 및 텍스트 계산용 객체 재사용
    private val textBounds = Rect()
    private val distBounds = Rect()

    // =========================================================
    //  결과 업데이트
    // =========================================================

    fun setResults(obstacles: List<ObstacleInfo>, imgWidth: Int, imgHeight: Int) {
        this.results = obstacles
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        postInvalidate()  // UI 스레드에서 다시 그리기
    }

    fun clearResults() {
        this.results = emptyList()
        postInvalidate()
    }

    // =========================================================
    //  그리기
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        // 이미지 좌표 → 뷰 좌표 변환 비율
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (obstacle in results) {
            // 좌표 변환
            val left = obstacle.x1 * scaleX
            val top = obstacle.y1 * scaleY
            val right = obstacle.x2 * scaleX
            val bottom = obstacle.y2 * scaleY

            // 위험도에 따른 색상 설정
            val color = when (obstacle.getDangerLevel()) {
                2 -> colorDanger     // 위험 (빨강)
                1 -> colorWarning    // 주의 (노랑)
                else -> colorSafe    // 안전 (초록)
            }

            boxPaint.color = color
            labelBgPaint.color = color

            // 1. 바운딩 박스 그리기
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // 2. 라벨 텍스트 준비
            val labelKorean = LabelTranslator.toKorean(obstacle.label)
            val confidenceText = String.format("%.0f%%", obstacle.confidence * 100)
            val labelText = "$labelKorean $confidenceText"
            val distText = obstacle.getDistanceText()

            // 텍스트 크기 계산
            labelPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val labelHeight = textBounds.height() + 20f
            val labelWidth = textBounds.width() + 20f

            // 라벨 배경 그리기 (바운딩 박스 상단)
            canvas.drawRect(
                left, top - labelHeight - 10,
                left + labelWidth, top,
                labelBgPaint
            )

            // 라벨 텍스트 그리기
            canvas.drawText(
                labelText,
                left + 10, top - 15,
                labelPaint
            )

            // 3. 거리 텍스트 그리기 (바운딩 박스 내부 중앙)
            if (obstacle.distanceMeters > 0) {
                distancePaint.getTextBounds(distText, 0, distText.length, distBounds)

                val cx = (left + right) / 2
                val cy = (top + bottom) / 2

                // 거리 정보 배경
                canvas.drawRect(
                    cx - distBounds.width() / 2 - 10,
                    cy - distBounds.height() / 2 - 10,
                    cx + distBounds.width() / 2 + 10,
                    cy + distBounds.height() / 2 + 10,
                    distBgPaint
                )

                // 거리 정보 텍스트
                canvas.drawText(
                    distText,
                    cx - distBounds.width() / 2f,
                    cy + distBounds.height() / 2f,
                    distancePaint
                )
            }
        }
    }
}