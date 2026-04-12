package com.example.obstacleavoidance

/**
 * ============================================================
 *  데이터 모델 정의
 * ============================================================
 */

/**
 * YOLO11 객체 탐지 결과
 */
data class DetectionResult(
    val label: String,       // 클래스 라벨 (예: "person", "chair")
    val classId: Int,        // 클래스 ID
    val confidence: Float,   // 신뢰도 (0~1)
    val x1: Float,          // Bounding Box 좌상단 x
    val y1: Float,          // Bounding Box 좌상단 y
    val x2: Float,          // Bounding Box 우하단 x
    val y2: Float           // Bounding Box 우하단 y
)

/**
 * 장애물 정보 (객체탐지 + 깊이추정 결합 결과)
 */
data class ObstacleInfo(
    val label: String,           // 객체 이름
    val confidence: Float,       // 탐지 신뢰도
    val x1: Float,              // Bounding Box 좌표
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val distanceMeters: Float   // 추정 거리 (미터), -1이면 미측정
) {
    /**
     * 거리를 문자열로 변환
     */
    fun getDistanceText(): String {
        return if (distanceMeters > 0) {
            String.format("%.1fm", distanceMeters)
        } else {
            "?"
        }
    }

    /**
     * 위험도 판정 (0: 안전, 1: 주의, 2: 위험)
     */
    fun getDangerLevel(): Int {
        return when {
            distanceMeters <= 0 -> 0
            distanceMeters <= 2.0f -> 2   // 2m 이내: 위험
            distanceMeters <= 5.0f -> 1   // 5m 이내: 주의
            else -> 0                      // 5m 초과: 안전
        }
    }
}
