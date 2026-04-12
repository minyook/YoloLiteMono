package com.example.obstacleavoidance

/**
 * ============================================================
 *  COCO 클래스 라벨 → 한국어 번역
 *  시각장애인에게 TTS로 알려줄 때 한국어 사용
 * ============================================================
 */
object LabelTranslator {

    private val translationMap = mapOf(
        "person" to "사람",
        "bicycle" to "자전거",
        "car" to "자동차",
        "motorcycle" to "오토바이",
        "airplane" to "비행기",
        "bus" to "버스",
        "train" to "기차",
        "truck" to "트럭",
        "boat" to "보트",
        "traffic light" to "신호등",
        "fire hydrant" to "소화전",
        "stop sign" to "정지 표지판",
        "parking meter" to "주차 미터기",
        "bench" to "벤치",
        "bird" to "새",
        "cat" to "고양이",
        "dog" to "강아지",
        "horse" to "말",
        "sheep" to "양",
        "cow" to "소",
        "elephant" to "코끼리",
        "bear" to "곰",
        "zebra" to "얼룩말",
        "giraffe" to "기린",
        "backpack" to "배낭",
        "umbrella" to "우산",
        "handbag" to "핸드백",
        "tie" to "넥타이",
        "suitcase" to "여행가방",
        "frisbee" to "프리스비",
        "skis" to "스키",
        "snowboard" to "스노보드",
        "sports ball" to "공",
        "kite" to "연",
        "baseball bat" to "야구방망이",
        "baseball glove" to "야구글러브",
        "skateboard" to "스케이트보드",
        "surfboard" to "서핑보드",
        "tennis racket" to "테니스 라켓",
        "bottle" to "병",
        "wine glass" to "와인잔",
        "cup" to "컵",
        "fork" to "포크",
        "knife" to "칼",
        "spoon" to "숟가락",
        "bowl" to "그릇",
        "banana" to "바나나",
        "apple" to "사과",
        "sandwich" to "샌드위치",
        "orange" to "오렌지",
        "broccoli" to "브로콜리",
        "carrot" to "당근",
        "hot dog" to "핫도그",
        "pizza" to "피자",
        "donut" to "도넛",
        "cake" to "케이크",
        "chair" to "의자",
        "couch" to "소파",
        "potted plant" to "화분",
        "bed" to "침대",
        "dining table" to "식탁",
        "toilet" to "변기",
        "tv" to "텔레비전",
        "laptop" to "노트북",
        "mouse" to "마우스",
        "remote" to "리모컨",
        "keyboard" to "키보드",
        "cell phone" to "휴대폰",
        "microwave" to "전자레인지",
        "oven" to "오븐",
        "toaster" to "토스터",
        "sink" to "싱크대",
        "refrigerator" to "냉장고",
        "book" to "책",
        "clock" to "시계",
        "vase" to "꽃병",
        "scissors" to "가위",
        "teddy bear" to "인형",
        "hair drier" to "드라이기",
        "toothbrush" to "칫솔"
    )

    /**
     * 영어 라벨을 한국어로 변환
     */
    fun toKorean(label: String): String {
        return translationMap[label.lowercase()] ?: label
    }
}
