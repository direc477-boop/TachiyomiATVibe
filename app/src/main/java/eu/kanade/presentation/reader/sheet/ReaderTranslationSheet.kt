package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.components.designsystem.SliderCard
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Full-screen semi-transparent glassmorphism "mod menu" overlay for translation,
 * display, and data management with live reader preview.
 */
@Composable
fun ReaderTranslationSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onFloaterEnabledChange: (Boolean) -> Unit,
    onFloaterIdleFadeChange: (Boolean) -> Unit,
    onShowTranslatedTextChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onResetFloaterPosition: () -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onColorizationEnabledChange: (Boolean) -> Unit,
    onAutoColorizeChange: (Boolean) -> Unit = {},
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onUpscalingEnabledChange: (Boolean) -> Unit = {},
    onColorizeChapterClick: () -> Unit,
    onColorizePageClick: () -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    isPageShared: Boolean = false,
    onDeletePageData: () -> Unit = {},
    onResetTranslationData: () -> Unit = {},
    onResetOcrData: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val readerPreferences: ReaderPreferences = remember { Injekt.get() }
    val customBrightness by readerPreferences.customBrightness().collectAsState()
    val customBrightnessValue by readerPreferences.customBrightnessValue().collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(ATMR.strings.reader_tab_translation),
        stringResource(ATMR.strings.reader_tab_display),
        stringResource(ATMR.strings.reader_tab_data),
    )

    Dialog(
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onDismissRequest()
                        onShowMenus()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier
                    .widthIn(max = 580.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xF018181B),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                shadowElevation = 20.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    // Sticky Header with Title and Apply/Close actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DesignTokens.AccentPrimary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Translate,
                                    contentDescription = null,
                                    tint = DesignTokens.AccentPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(ATMR.strings.reader_translation_sheet_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    text = "Live preview • Mod menu",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    onDismissRequest()
                                    onShowMenus()
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DesignTokens.AccentPrimary,
                                    contentColor = DesignTokens.TextOnAccent,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Apply", style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(
                                onClick = {
                                    onDismissRequest()
                                    onShowMenus()
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Sticky Top Segmented Tabs
                    SegmentedPillBar(
                        tabs = tabs,
                        selectedIndex = selectedTabIndex,
                        onTabSelected = { selectedTabIndex = it },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tab Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (selectedTabIndex) {
                            0 -> {
                                TranslationLanguageSection(
                                    state = state,
                                    onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                                    onTranslateToLanguageChange = onTranslateToLanguageChange,
                                )
                                TranslationEngineSection(
                                    state = state,
                                    onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                                    onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                                    onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
                                    onTranslationAiEngineChange = onTranslationAiEngineChange,
                                    onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                                    onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                                    onTranslationAiModelChange = onTranslationAiModelChange,
                                    onFetchAiModels = onFetchAiModels,
                                )
                                TranslationOcrInpaintSection(
                                    state = state,
                                    onOcrModelChange = onOcrModelChange,
                                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                                    onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                                )
                                TranslationColorizationSection(
                                    state = state,
                                    onColorizationEnabledChange = onColorizationEnabledChange,
                                    onColorizationModelChange = onColorizationModelChange,
                                    onUpscalingEnabledChange = onUpscalingEnabledChange,
                                    onColorizeChapterClick = onColorizeChapterClick,
                                    onColorizePageClick = onColorizePageClick,
                                )
                            }
                            1 -> {
                                TranslationActivationSection(
                                    state = state,
                                    onTranslationEnabledChange = onTranslationEnabledChange,
                                    onAutoTranslateChange = onAutoTranslateChange,
                                    onFloaterEnabledChange = onFloaterEnabledChange,
                                    onFloaterIdleFadeChange = onFloaterIdleFadeChange,
                                    onShowTranslatedTextChange = onShowTranslatedTextChange,
                                    onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                                    onResetFloaterPosition = onResetFloaterPosition,
                                )
                                SectionHeading(title = stringResource(ATMR.strings.reader_section_display_adjustment))
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .fillMaxWidth(),
                                ) {
                                    SliderCard(
                                        icon = Icons.Outlined.BrightnessMedium,
                                        label = stringResource(ATMR.strings.reader_brightness_level),
                                        value = if (customBrightness) customBrightnessValue.toFloat() else 0f,
                                        valueRange = -75f..100f,
                                        valueDisplay = "${if (customBrightness) customBrightnessValue else 0}%",
                                        onValueChange = {
                                            if (!customBrightness) {
                                                readerPreferences.customBrightness().set(true)
                                            }
                                            readerPreferences.customBrightnessValue().set(it.toInt())
                                        },
                                    )
                                }
                            }
                            2 -> {
                                TranslationResetSection(
                                    isPageShared = isPageShared,
                                    onDeletePageData = onDeletePageData,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
