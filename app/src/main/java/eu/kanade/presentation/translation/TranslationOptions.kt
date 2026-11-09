package eu.kanade.presentation.translation

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.ColorizationQuality
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Option lists shared by the reader translation sheet and the batch dialog. */

internal val standardEngineOptions: List<SelectorOption<StandardEngine>> =
    StandardTranslatorKind.entries.map { kind ->
        SelectorOption(
            value = StandardEngine.valueOf(kind.name),
            label = kind.label,
        )
    }

internal val aiProviderOptions: List<SelectorOption<AiEngine>> =
    AiTranslatorKind.entries.map { kind ->
        SelectorOption(
            value = kind.engine,
            label = kind.label,
        )
    }

@Composable
internal fun ocrModelOptions(): List<SelectorOption<OcrModel>> = listOf(
    SelectorOption(
        value = OcrModel.PADDLEOCR_V6_SMALL,
        label = "PaddleOCR v6",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_paddle_summary),
    ),
    SelectorOption(
        value = OcrModel.MANGAOCR,
        label = "Manga-OCR",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_mangaocr_summary),
    ),
    SelectorOption(
        value = OcrModel.MLKIT,
        label = "Google MLKit",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_mlkit_summary),
    ),
)

@Composable
internal fun neuralInpaintOptions(): List<SelectorOption<NeuralInpaintModel>> = listOf(
    SelectorOption(NeuralInpaintModel.LAMA_LITERT_GPU, "Manga LaMa", "LiteRT GPU Accelerated"),
    SelectorOption(NeuralInpaintModel.AOT_GAN, "AOT-GAN", "512px High Speed"),
)

/** Selectable colorization models — single source shared by reader sheet and settings screens. */
internal val colorizationModelValues: List<ColorizationModel> = listOf(
    ColorizationModel.MANGA_COLORIZATION_V2,
    ColorizationModel.MANGA_COLORIZATION_V2_INT8,
    ColorizationModel.MANGA_COLORIZATION_V2_NPU,
    ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8,
    ColorizationModel.SANA_V2,
    ColorizationModel.MANGA_LIGHT_NPU_SM8650,
    ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU,
)

@Composable
internal fun colorizationModelOptions(): List<SelectorOption<ColorizationModel>> =
    colorizationModelValues.map { value ->
        SelectorOption(value = value, label = stringResource(colorizationModelLabelRes(value)))
    }

private fun colorizationModelLabelRes(model: ColorizationModel): StringResource = when (model) {
    ColorizationModel.MANGA_COLORIZATION_V2 -> ATMR.strings.pref_colorization_model_mcv2
    ColorizationModel.MANGA_COLORIZATION_V2_INT8 -> ATMR.strings.pref_colorization_model_mcv2_int8
    ColorizationModel.MANGA_COLORIZATION_V2_NPU -> ATMR.strings.pref_colorization_model_mcv2_npu
    ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8 -> ATMR.strings.pref_colorization_model_mcv2_npu_w8a8
    ColorizationModel.SANA_V2 -> ATMR.strings.pref_colorization_model_sana
    ColorizationModel.MANGA_LIGHT_NPU_SM8650 -> ATMR.strings.pref_colorization_model_manga_light_npu
    ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU -> ATMR.strings.pref_colorization_model_manga_light_hybrid
}

@Composable
internal fun colorizationQualityOptions(): List<SelectorOption<ColorizationQuality>> = listOf(
    SelectorOption(ColorizationQuality.FAST, stringResource(ATMR.strings.pref_colorization_quality_fast)),
    SelectorOption(ColorizationQuality.BALANCED, stringResource(ATMR.strings.pref_colorization_quality_balanced)),
    SelectorOption(ColorizationQuality.HIGH, stringResource(ATMR.strings.pref_colorization_quality_high)),
)
