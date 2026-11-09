import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter

ROOT = Path('/scratch/mcv2_576x832')
DATA = Path('/data')
MODELS = {
    'fp32': ROOT / 'mcv2_576x832_b6_int32.tflite',
    'synthetic_w8a8': ROOT / 'mcv2_576x832_w8a8.tflite',
    'earlier_representative_w8a8': ROOT / 'mcv2_576x832_w8a8_rep.tflite',
    'rawkuma_w8a8': DATA / 'mcv2_w8a8_rawkuma.tflite',
    'mixed_w8a8': DATA / 'mcv2_w8a8_mixed.tflite',
}


def run_model(path, tensors):
    model = Interpreter(model_path=str(path), num_threads=2)
    model.allocate_tensors()
    input_index = model.get_input_details()[0]['index']
    output_index = model.get_output_details()[0]['index']
    for index, tensor in enumerate(tensors):
        model.set_tensor(input_index, tensor)
        model.invoke()
        yield index, model.get_tensor(output_index).astype(np.float32)


def main():
    dataset = sys.argv[2] if len(sys.argv) > 2 else 'evaluation.npz'
    tensors = np.load(DATA / dataset)['inputs']
    print(f'Evaluating {len(tensors)} held-out pages on host CPU', flush=True)
    reference = [out for _, out in run_model(MODELS['fp32'], tensors)]
    results = {}
    for label, path in MODELS.items():
        if label == 'fp32':
            continue
        if len(sys.argv) > 1 and sys.argv[1] != 'all' and label != sys.argv[1]:
            continue
        entries = []
        for index, out in run_model(path, tensors):
            delta = out.astype(np.float64) - reference[index].astype(np.float64)
            mse = float(np.mean(delta ** 2))
            entries.append({
                'index': index,
                'psnr_db': -10 * math.log10(mse) if mse else None,
                'mean_absolute_error': float(np.mean(np.abs(delta))),
                'mean_signed_rgb_error': np.mean(delta, axis=(0, 1, 2)).tolist(),
                'max_absolute_error': float(np.max(np.abs(delta))),
            })
            print(f'{label} page {index + 1}: {entries[-1]["psnr_db"]:.2f} dB', flush=True)
        results[label] = {
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'mean_psnr_db': float(np.mean([e['psnr_db'] for e in entries])),
            'pages': entries,
        }
    name = f'evaluation_{sys.argv[1]}_{Path(dataset).stem}.json' if len(sys.argv) > 1 else 'evaluation_report.json'
    (DATA / name).write_text(json.dumps(results, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
