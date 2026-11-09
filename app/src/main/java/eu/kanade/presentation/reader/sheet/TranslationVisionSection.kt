package eu.kanade.presentation.reader.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.components.designsystem.SwitchCard
import eu.kanade.presentation.translation.colorizationModelOptions
import eu.kanade.presentation.translation.colorizationQualityOptions
import eu.kanade.presentation.translation.neuralInpaintOptions
import eu.kanade.presentation.translation.ocrModelOptions
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.ColorizationQuality
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Vision and inpainting sections of the reader translation sheet: the OCR
 * engine selector, the FAST/BALANCE/QUALITY inpainting tiles and the neural
 * model override used whenever the mode is not FAST, plus the colorization
 * gate and model.
 */
@Composable
internal fun TranslationVisionSection(
    state: TranslationSettingsState,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onColorizationEnabledChange: (Boolean) -> Unit,
    onAutoColorizeChange: (Boolean) -> Unit,
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onColorizationQualityChange: (ColorizationQuality) -> Unit,
    onUpscalingEnabledChange: (Boolean) -> Unit,
    onColorizeChapterClick: () -> Unit,
    onColorizePageClick: () -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_vision))

    val ocrOptions = ocrModelOptions()

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
    ) {
        SearchableConfigSelectorBox(
            selectedLabel = ocrOptions.firstOrNull { it.value == state.ocrModel }?.label
                ?: state.ocrModel.name,
            options = ocrOptions,
            onSelect = onOcrModelChange,
            placeholder = stringResource(ATMR.strings.pref_ocr_model),
        )
    }

    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))

    val currentInpaint = state.inpaintingMode.ifEmpty { "QUALITY" }
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionTile(
                selected = currentInpaint.equals("FAST", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("FAST") },
                title = "FAST",
                modifier = Modifier.weight(1f),
            )
            SelectionTile(
                selected = currentInpaint.equals("BALANCE", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("BALANCE") },
                title = "BALANCE",
                modifier = Modifier.weight(1f),
            )
            SelectionTile(
                selected = currentInpaint.equals("QUALITY", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("QUALITY") },
                title = "QUALITY",
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            visible = !currentInpaint.equals("FAST", ignoreCase = true),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val neuralOptions = neuralInpaintOptions()
            SearchableConfigSelectorBox(
                selectedLabel = neuralOptions.firstOrNull { it.value == state.inpaintingNeuralModel }?.label
                    ?: state.inpaintingNeuralModel.name,
                options = neuralOptions,
                onSelect = onTranslationInpaintingNeuralModelChange,
                placeholder = "Select Neural Model",
            )
        }
    }

    SectionHeading(title = stringResource(ATMR.strings.pref_colorization_title))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.pref_colorization_enabled),
            subtitle = stringResource(ATMR.strings.pref_colorization_enabled_summary),
            checked = state.colorizationEnabled,
            onCheckedChange = onColorizationEnabledChange,
        )

        AnimatedVisibility(
            visible = state.colorizationEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val colorizationOptions = colorizationModelOptions()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchCard(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(ATMR.strings.pref_auto_colorize),
                    subtitle = stringResource(ATMR.strings.pref_auto_colorize_summary),
                    checked = state.autoColorize,
                    onCheckedChange = onAutoColorizeChange,
                )
                SearchableConfigSelectorBox(
                    selectedLabel = colorizationOptions.firstOrNull { it.value == state.colorizationModel }?.label
                        ?: state.colorizationModel.name,
                    options = colorizationOptions,
                    onSelect = onColorizationModelChange,
                    placeholder = stringResource(ATMR.strings.pref_colorization_model),
                )
                if (state.colorizationModel == ColorizationModel.MANGA_COLORIZATION_V2_INT8) {
                    val qualityOptions = colorizationQualityOptions()
                    Text(text = stringResource(ATMR.strings.pref_colorization_quality))
                    SearchableConfigSelectorBox(
                        selectedLabel = qualityOptions.firstOrNull { it.value == state.colorizationQuality }?.label
                            ?: state.colorizationQuality.name,
                        options = qualityOptions,
                        onSelect = onColorizationQualityChange,
                        placeholder = stringResource(ATMR.strings.pref_colorization_quality),
                    )
                }
            }
        }

        // Page-scope and chapter-scope actions, always visible so the entry
        // points and their preconditions stay discoverable even with
        // colorization switched off.
        TranslationActionRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.reader_colorize_page),
            subtitle = stringResource(ATMR.strings.reader_colorize_page_summary),
            onClick = onColorizePageClick,
        )
        TranslationActionRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.reader_colorize_chapter),
            subtitle = stringResource(ATMR.strings.reader_colorize_summary),
            onClick = onColorizeChapterClick,
        )
    }

    SectionHeading(title = stringResource(ATMR.strings.pref_upscaling_title))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(ATMR.strings.pref_upscaling_enabled),
            subtitle = stringResource(ATMR.strings.pref_upscaling_enabled_summary),
            checked = state.upscalingEnabled,
            onCheckedChange = onUpscalingEnabledChange,
        )
    }
}
