package tachiyomi.domain.translation

/** MANGA_LIGHT_SAM_NPU_GPU = Manga Light hybrid (SAM NPU chunks + GPU-FP32 generator),
 *  MANGA_COLORIZATION_V2_NPU_W8A8 = MCV2 SM8650 NPU W8A8 (cross-series calibrated). */
enum class ColorizationModel(val prefValue: String) {
    MANGA_COLORIZATION_V2_NPU_W8A8("MANGA_COLORIZATION_V2_NPU_W8A8"),
    MANGA_LIGHT_SAM_NPU_GPU("MANGA_LIGHT_SAM_NPU_GPU"),
    ;

    companion object {
        val DEFAULT = MANGA_LIGHT_SAM_NPU_GPU

        fun fromPrefOrNull(value: String?): ColorizationModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
