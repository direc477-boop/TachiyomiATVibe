"""Diagnose the installed tflite package layout."""
import os
import pkgutil

import tflite

print("file:", tflite.__file__)
print("path:", list(tflite.__path__))
mods = [m.name for m in pkgutil.iter_modules(tflite.__path__)]
print("modules:", mods[:80])
try:
    from tflite import Builtins  # noqa: F401
    print("has Builtins")
except Exception as e:  # noqa: BLE001
    print("no Builtins:", e)
for name in ("model", "OperatorCode", "BuiltinOperator", "Conv2DOptions"):
    try:
        __import__(f"tflite.{name}")
        print(f"import tflite.{name}: OK")
    except Exception as e:  # noqa: BLE001
        print(f"import tflite.{name}: FAIL {type(e).__name__}: {e}")
print("DIAG_DONE")
