"""Introspect the installed pinned API surface before compiling (run first)."""
import inspect

import ai_edge_litert
from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.vendors.qualcomm import target as qualcomm

print("ai_edge_litert version:", getattr(ai_edge_litert, "__version__", "?"))
print("aot_compile sig:", inspect.signature(aot_lib.aot_compile))
print("Target sig:", inspect.signature(qualcomm.Target))
members = [m for m in dir(qualcomm.SocModel) if not m.startswith("_")]
print("SocModel members:", members)
print("SM8650 present:", hasattr(qualcomm.SocModel, "SM8650"))
tgt = qualcomm.Target(soc_model=qualcomm.SocModel.SM8650)
print("Target repr:", tgt)
print("Target attrs:", [m for m in dir(tgt) if not m.startswith("_")])
try:
    import ai_edge_litert_sdk_qualcomm as q
    print("sdk libs:", q.path_to_sdk_libs())
except Exception as e:  # noqa: BLE001
    print("sdk import failed:", e)
