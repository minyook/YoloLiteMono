import argparse
import torch
import torch.nn as nn
import sys
import os
import onnx  # 버전 수정을 위해 필요

# Lite-Mono 레포지토리 경로를 추가
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'Lite-Mono'))

def convert_litemono_to_onnx(args):
    try:
        from networks.depth_encoder import LiteMono
        from networks.depth_decoder import DepthDecoder
    except ImportError:
        print("❌ 에러: Lite-Mono 폴더를 찾을 수 없습니다. 경로를 확인하세요.")
        return

    device = torch.device("cpu")

    # ── Step 1: 인코더 로드 ──
    print("[1/5] 깊이 인코더 로드 중...")
    encoder = LiteMono(model=args.model, height=args.height, width=args.width)
    encoder_dict = torch.load(args.encoder_path, map_location=device)
    encoder.load_state_dict({k: v for k, v in encoder_dict.items() if k in encoder.state_dict()}, strict=False)
    encoder.eval()

    # ── Step 2: 디코더 로드 ──
    print("[2/5] 깊이 디코더 로드 중...")
    decoder = DepthDecoder(encoder.num_ch_enc, scales=range(3))
    decoder_dict = torch.load(args.decoder_path, map_location=device)
    decoder.load_state_dict(decoder_dict, strict=False)
    decoder.eval()

    # ── Step 3: 통합 모델 생성 ──
    class LiteMonoFull(nn.Module):
        def __init__(self, enc, dec):
            super().__init__()
            self.encoder = enc
            self.decoder = dec
        def forward(self, x):
            features = self.encoder(x)
            outputs = self.decoder(features)
            return outputs[("disp", 0)]

    full_model = LiteMonoFull(encoder, decoder)
    full_model.eval()

    # ── Step 4: ONNX 변환 및 IR Version 강제 수정 ──
    print("[4/5] ONNX 변환 및 버전 최적화 중...")
    dummy_input = torch.randn(1, 3, args.height, args.width)
    output_path = args.output if args.output else "lite_mono.onnx"

    # 1. 먼저 파일로 내보내기
    torch.onnx.export(
        full_model,
        dummy_input,
        output_path,
        export_params=True,
        opset_version=15,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        keep_initializers_as_inputs=True
    )

    # 2. 저장된 모델을 다시 로드하여 IR Version 강제 수정 (핵심 해결책)
    model_proto = onnx.load(output_path)
    model_proto.ir_version = 8  # 안드로이드 지원 버전으로 강제 변경
    onnx.save(model_proto, output_path)
    print(f"  ✅ IR Version을 8로 하향 조정 완료")

    # ── Step 5: 검증 ──
    print("[5/5] ONNX 모델 검증 중...")
    onnx.checker.check_model(onnx_model := onnx.load(output_path))

    import onnxruntime as ort
    session = ort.InferenceSession(output_path)
    print(f"\n✨ 변환 및 호환성 작업 성공!")
    print(f"→ 파일: {output_path} (IR v{onnx.load(output_path).ir_version})")
    print(f"이 파일을 assets 폴더에 덮어쓰고 Clean Project 하세요.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--encoder_path", type=str, required=True)
    parser.add_argument("--decoder_path", type=str, required=True)
    parser.add_argument("--model", type=str, default="lite-mono")
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=192)
    parser.add_argument("--output", type=str, default="lite_mono.onnx")
    args = parser.parse_args()
    convert_litemono_to_onnx(args)