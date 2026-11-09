"""MCV2 quantized artifact I/O dump (for integration facts; no inference).
Usage: python -I mcv2_io_probe.py MODEL_PATH
"""
import sys

from ai_edge_litert.interpreter import Interpreter

interp = Interpreter(model_path=sys.argv[1])
interp.allocate_tensors()
print("signatures:", interp.get_signature_list(), flush=True)
for label, tds in (("input", interp.get_input_details()), ("output", interp.get_output_details())):
    for td in tds:
        print(f"{label}: name={td['name']} shape={td['shape']} dtype={td['dtype']} index={td['index']} quant={td.get('quantization')}", flush=True)
print("IO_PROBE_DONE", flush=True)
