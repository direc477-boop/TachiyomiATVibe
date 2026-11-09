import re

tm_path = r"app/src/main/java/eu/kanade/translation/TranslationManager.kt"
with open(tm_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add imports
import_needle = "import eu.kanade.translation.engines.coloring.UnprovisionedColorizationEngine\n"
new_imports = (
    "import eu.kanade.translation.engines.coloring.UnprovisionedColorizationEngine\n"
    "import eu.kanade.translation.engines.upscaling.PageUpscalingEngine\n"
    "import eu.kanade.translation.engines.upscaling.UpscalingModelCatalog\n"
)
assert import_needle in content, "import needle not found in TranslationManager.kt"
content = content.replace(import_needle, new_imports, 1)

# 2. Add upscalingEngine property
prop_needle = """    internal var colorizationEngine: PageColorizationEngine? =
        if (testConstruction == null) {
            buildColorizationEngine(currentColorizationSelection())
                .also { processor.colorizationEngine = it }
        } else {
            null
        }"""

new_prop = """    internal var colorizationEngine: PageColorizationEngine? =
        if (testConstruction == null) {
            buildColorizationEngine(currentColorizationSelection())
                .also { processor.colorizationEngine = it }
        } else {
            null
        }

    /**
     * Optional page upscaling engine (QuickSRNetLarge). Attached to the processor;
     * availability is gated upstream by [UpscalingModelCatalog].
     */
    internal var upscalingEngine: PageUpscalingEngine? =
        if (testConstruction == null) {
            UpscalingModelCatalog.createEngine(context)
                .also { processor.upscalingEngine = it }
        } else {
            null
        }"""
assert prop_needle in content, "prop needle not found in TranslationManager.kt"
content = content.replace(prop_needle, new_prop, 1)

with open(tm_path, "w", encoding="utf-8") as f:
    f.write(content)

print("TranslationManager.kt updated successfully")
