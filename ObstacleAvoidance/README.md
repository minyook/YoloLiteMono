# 🦯 시각장애인을 위한 장애물 우회 시스템

## 프로젝트 개요
YOLO11 객체 탐지 + LiteMono 깊이 추정을 결합하여 시각장애인에게 
주변 장애물의 종류와 거리를 실시간 음성으로 안내하는 Android 앱입니다.

## 기술 스택
| 구분 | 기술 |
|------|------|
| 플랫폼 | Android (Kotlin) |
| IDE | Android Studio |
| 카메라 | CameraX |
| 객체 탐지 | YOLO11 (ONNX) |
| 깊이 추정 | LiteMono (ONNX) |
| AI 추론 | ONNX Runtime Mobile |
| 음성 안내 | Android TTS |

## 시스템 아키텍처
```
카메라 프레임 (CameraX)
       │
       ▼
  [ImageProxy → Bitmap 변환]
       │
       ├──────────────────────┐
       ▼                      ▼
  YOLO11 객체 탐지      LiteMono 깊이 추정
  (라벨, 좌표, 신뢰도)    (Depth Map)
       │                      │
       └──────────┬───────────┘
                  ▼
         [결과 결합: 객체 + 거리]
                  │
                  ▼
         TTS 음성 안내
  "3미터 앞에 의자가 있습니다"
```

## 프로젝트 구조
```
ObstacleAvoidance/
├── app/
│   ├── build.gradle.kts              # 의존성 설정
│   └── src/main/
│       ├── AndroidManifest.xml        # 앱 매니페스트
│       ├── assets/
│       │   ├── yolo11.onnx           # YOLO11 모델 (직접 변환 필요)
│       │   └── lite_mono.onnx        # LiteMono 모델 (직접 변환 필요)
│       ├── java/com/example/obstacleavoidance/
│       │   ├── MainActivity.kt        # 메인 액티비티 (카메라+UI+TTS)
│       │   ├── ObjectDetector.kt      # YOLO11 객체 탐지 클래스
│       │   ├── DepthEstimator.kt      # LiteMono 깊이 추정 클래스
│       │   ├── OverlayView.kt         # 바운딩 박스 오버레이 뷰
│       │   ├── DataModels.kt          # 데이터 클래스 정의
│       │   └── LabelTranslator.kt     # 영→한 라벨 번역
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/styles.xml
├── model_conversion/
│   ├── convert_yolo11.py             # YOLO11 ONNX 변환
│   └── convert_litemono.py           # LiteMono ONNX 변환
└── README.md
```

## 모델 준비

### 1. YOLO11 ONNX 변환
```bash
pip install ultralytics
python model_conversion/convert_yolo11.py
# → yolo11n.onnx 생성 → app/src/main/assets/yolo11.onnx 로 복사
```

### 2. LiteMono ONNX 변환
```bash
git clone https://github.com/noahzn/Lite-Mono.git
# 모델 가중치 다운로드 (lite-mono 640x192)
python model_conversion/convert_litemono.py \
    --encoder_path Lite-Mono/models/encoder.pth \
    --decoder_path Lite-Mono/models/depth.pth
# → lite_mono.onnx 생성 → app/src/main/assets/lite_mono.onnx 로 복사
```

## 앱 사용 방법
1. 앱 실행 → "앱이 준비되었습니다" 음성 안내
2. 화면 아무 곳이나 터치 → "탐지를 시작합니다" + 실시간 인식 시작
3. 장애물 감지 시 → "3미터 앞에 의자가 있습니다" 음성 안내
4. 다시 화면 터치 → "탐지를 정지합니다" + 인식 중지

## 빌드 및 실행
1. Android Studio에서 프로젝트 열기
2. assets 폴더에 ONNX 모델 파일 배치
3. USB 디바이스 연결 후 Run
4. 최소 Android API 26 (Android 8.0) 이상

## 거리 캘리브레이션
DepthEstimator.kt의 `DEPTH_SCALE_FACTOR`를 실제 환경에 맞게 조정:
1. 알려진 거리의 물체를 촬영
2. 로그에서 disparity 값 확인  
3. `DEPTH_SCALE_FACTOR = 실제거리 × disparity` 로 계산
