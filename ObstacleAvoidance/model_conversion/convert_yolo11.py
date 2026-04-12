"""
============================================================
 YOLO11 → ONNX 변환 스크립트
============================================================
 이 스크립트를 PC(Python 환경)에서 실행하여
 YOLO11 모델을 ONNX 형식으로 변환합니다.
 
 사전 준비:
   pip install ultralytics onnx onnxruntime
 
 사용법:
   python convert_yolo11.py
 
 출력 파일을 안드로이드 프로젝트의
 app/src/main/assets/yolo11.onnx 에 복사하세요.
============================================================
"""

from ultralytics import YOLO

def convert_yolo11_to_onnx():
    # ── 모델 선택 ──
    # yolo11n.pt : Nano (가장 빠름, 모바일 추천)
    # yolo11s.pt : Small (정확도↑, 속도↓)
    # yolo11m.pt : Medium
    model_name = "yolo11n.pt"
    
    print(f"[1/3] YOLO11 모델 로드 중: {model_name}")
    model = YOLO(model_name)
    
    print("[2/3] ONNX 변환 중...")
    # 변환 옵션:
    # - imgsz: 입력 이미지 크기 (640x640)
    # - opset: ONNX opset 버전 (13 이상 권장)
    # - simplify: 그래프 최적화
    # - dynamic: 동적 배치 비활성화 (모바일용)
    model.export(
        format="onnx",
        imgsz=640,
        opset=13,
        simplify=True,
        dynamic=False,
        half=False,       # FP32 유지 (호환성)
    )
    
    print(f"[3/3] 변환 완료!")
    print(f"  → 출력 파일: yolo11n.onnx")
    print(f"  → 이 파일을 app/src/main/assets/yolo11.onnx 에 복사하세요")

if __name__ == "__main__":
    convert_yolo11_to_onnx()
