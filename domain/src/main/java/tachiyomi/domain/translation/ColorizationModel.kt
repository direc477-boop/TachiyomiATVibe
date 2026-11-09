package tachiyomi.domain.translation

/** V2 = GPU/CPU, V2_INT8 = ONNX INT8, V2_NPU = experimental SM8650 NPU (no fallback),
 *  V2_NPU_W8A8 = W8A8 NPU (synthetic-only calibration, quality unvalidated), SANA_V2 = diffusion,
 *  MANGA_LIGHT_NPU_SM8650 = Manga Light generator-only 512×512 SM8650 NPU (no fallback). */
enum class ColorizationModel(val prefValue: String) {
    MANGA_COLORIZATION_V2("MANGA_COLORIZATION_V2"),
    MANGA_COLORIZATION_V2_INT8("MANGA_COLORIZATION_V2_INT8"),
    MANGA_COLORIZATION_V2_NPU("MANGA_COLORIZATION_V2_NPU"),
    MANGA_COLORIZATION_V2_NPU_W8A8("MANGA_COLORIZATION_V2_NPU_W8A8"),
    SANA_V2("SANA_V2"),
    MANGA_LIGHT_NPU_SM8650("MANGA_LIGHT_NPU_SM8650"),
    MANGA_LIGHT_SAM_NPU_GPU("MANGA_LIGHT_SAM_NPU_GPU"),
    ;

    companion object {
        val DEFAULT = MANGA_COLORIZATION_V2

        fun fromPrefOrNull(value: String?): ColorizationModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}

enum class ColorizationQuality(
    val targetShortSidePx: Int,
    val maxLongSidePx: Int,
) {
    FAST(384, 1152),
    BALANCED(512, 1536),
    HIGH(640, 1920),
    ;

    companion object {
        val DEFAULT = BALANCED
    }
}
