import inspect

from litert_quantizer import quantizer as lq

print("init:", inspect.signature(lq.Quantizer.__init__))
print("calib:", inspect.signature(lq.Quantizer.calibrate))
print("quant:", inspect.signature(lq.Quantizer.quantize))
print("export:", inspect.signature(lq.Quantizer.export_model))
print("load_recipe:", inspect.signature(lq.Quantizer.load_quantization_recipe))
