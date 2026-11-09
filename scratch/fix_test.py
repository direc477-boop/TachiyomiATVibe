path = "app/src/test/java/eu/kanade/translation/engines/upscaling/LiteRTQuickSRNetUpscalingEngineTest.kt"
with open(path, "r", encoding="utf-8") as f:
    c = f.read()
c = c.replace(r"\n            artifact", "\n            artifact")
with open(path, "w", encoding="utf-8") as f:
    f.write(c)
print("Fixed literal slash n")
