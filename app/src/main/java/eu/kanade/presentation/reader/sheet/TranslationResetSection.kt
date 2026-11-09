package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.designsystem.SectionHeading
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Item-level data wipe section of the reader translation sheet:
 * single unified delete action with cascading removal of downloaded page,
 * translation, cleaned, and colored images.
 */
@Composable
internal fun TranslationResetSection(
    isPageShared: Boolean = false,
    onDeletePageData: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_reset))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TranslationActionRow(
            icon = Icons.Outlined.DeleteOutline,
            title = stringResource(ATMR.strings.action_delete_page_data),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { showConfirmDialog = true },
            danger = true,
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(ATMR.strings.reader_delete_page_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (isPageShared) ATMR.strings.reader_delete_page_confirm_message_shared
                        else ATMR.strings.reader_delete_page_confirm_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onDeletePageData()
                    },
                ) {
                    Text(
                        stringResource(MR.strings.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
