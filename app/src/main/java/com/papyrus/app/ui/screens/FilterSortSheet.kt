package com.papyrus.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.FormatGroup
import com.papyrus.app.data.LibraryFilter
import com.papyrus.app.data.LibrarySort

/**
 * Home's sort and filter sheet: every sort order, every file-type group and the starred filter in one
 * place. The count of matching files sits inside it, so a filter never hides rows without saying so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSortSheet(
    sort: LibrarySort,
    filter: LibraryFilter,
    groups: List<FormatGroup>,
    showStarred: Boolean,
    matchCount: Int,
    onSort: (LibrarySort) -> Unit,
    onToggleGroup: (FormatGroup) -> Unit,
    onToggleStarred: () -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The content is taller than a phone, so a half-open sheet would only show the title.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The sheet does not scroll its content, so the column does.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_sort_filter_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (matchCount == 0) {
                    stringResource(R.string.home_filter_none)
                } else {
                    pluralStringResource(R.plurals.home_filter_count, matchCount, matchCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(stringResource(R.string.home_sort_section))
                Spacer(Modifier.weight(1f))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (option in LibrarySort.entries) {
                    ChoiceChip(
                        label = stringResource(option.labelRes()),
                        selected = option == sort,
                        onClick = { onSort(option) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(stringResource(R.string.home_filter_section))
                Spacer(Modifier.weight(1f))
                if (filter.isActive) {
                    TextButton(onClick = onClearFilters, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(stringResource(R.string.home_filter_clear))
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (group in groups) {
                    ChoiceChip(
                        label = stringResource(group.labelRes()),
                        selected = group in filter.groups,
                        onClick = { onToggleGroup(group) },
                    )
                }
                if (showStarred) {
                    ChoiceChip(
                        label = stringResource(R.string.home_filter_starred),
                        selected = filter.starredOnly,
                        onClick = onToggleStarred,
                    )
                }
            }
        }
    }
}

/**
 * The button beside the search pill that opens the sheet. The badge counts active filters so the button reads
 * as one thing: the order and how many filters are narrowing the list.
 */
@Composable
fun SortFilterButton(
    sort: LibrarySort,
    activeCount: Int,
    onClick: () -> Unit,
) {
    val orderLabel = stringResource(sort.labelRes())
    val badgeLabel = if (activeCount > 0) {
        pluralStringResource(R.plurals.home_filter_active, activeCount, activeCount)
    } else {
        null
    }
    val description = if (badgeLabel != null) {
        stringResource(R.string.home_sort_cd_with_filters, orderLabel, badgeLabel)
    } else {
        stringResource(R.string.home_sort_cd, orderLabel)
    }
    BadgedBox(
        badge = {
            if (badgeLabel != null) {
                Badge(modifier = Modifier.clearAndSetSemantics {}) {
                    Text(activeCount.toString())
                }
            }
        },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A section heading inside the sheet; the same label style the info sheet gives a field's name. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Selected chips are blue only; width stays constant. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = null,
    )
}

@StringRes
internal fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.LAST_OPENED -> R.string.home_sort_last_opened
    LibrarySort.NAME_ASC -> R.string.home_sort_name_asc
    LibrarySort.NAME_DESC -> R.string.home_sort_name_desc
    LibrarySort.DATE_ADDED -> R.string.home_sort_date_added
    LibrarySort.SIZE -> R.string.home_sort_size
}

@StringRes
internal fun FormatGroup.labelRes(): Int = when (this) {
    FormatGroup.PDF -> R.string.home_filter_pdf
    FormatGroup.WORD -> R.string.home_filter_word
    FormatGroup.ODT -> R.string.home_filter_odt
    FormatGroup.EPUB -> R.string.home_filter_epub
    FormatGroup.MARKDOWN -> R.string.home_filter_markdown
    FormatGroup.TEXT -> R.string.home_filter_text
    FormatGroup.CSV -> R.string.home_filter_csv
    FormatGroup.TSV -> R.string.home_filter_tsv
    FormatGroup.CODE -> R.string.home_filter_code
}
