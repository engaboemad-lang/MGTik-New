package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.OwnerFilter
import ps.mgtik.hotspot.ui.viewmodel.OwnerSort
import ps.mgtik.hotspot.ui.viewmodel.OwnersUiState

/**
 * الشاشة 12: دليل الملاك والموزعين المالي (§4.18) — ملخص إجمالي، تصفية تشغيلية، وصفوف تُظهر الرصيد والمستحق والحالة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S12_OwnerDirectoryScreen(
    state: OwnersUiState,
    onSearchQueryChanged: (String) -> Unit,
    onFilterChanged: (OwnerFilter) -> Unit,
    onSortChanged: (OwnerSort) -> Unit = {},
    onSelectOwner: (OwnerAccount) -> Unit,
    onOpenCreateOwner: () -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currency = LocalCurrencySymbol.current

    LaunchedEffect(state.successMessageResId, state.errorMessageResId) {
        val message = state.successMessageResId ?: state.errorMessageResId
        if (message != null) {
            snackbarHostState.showSnackbar(context.getString(message))
            onMessageShown()
        }
    }

    // ملخص الدليل على الملاك الفعّالين/الموقوفين (المؤرشفون خارج الأرقام)
    val live = state.ownersSummary.filter { it.status != OwnerStatus.ARCHIVED }
    val totalCredit = live.sumOf { maxOf(it.availableCreditMinor, 0L) }
    val totalDues = live.sumOf { state.dueByOwner[it.ownerId] ?: 0L }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.owner_directory_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenCreateOwner,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.owner_form_add_title))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                OwnerStatTile(stringResource(R.string.owner_summary_owners), "${live.size}", Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                OwnerStatTile(stringResource(R.string.owner_summary_credit_total), "${formatMoney(totalCredit)} $currency", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                OwnerStatTile(
                    stringResource(R.string.owner_summary_dues_total), "${formatMoney(totalDues)} $currency", Modifier.weight(1f),
                    if (totalDues > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = { Text(stringResource(R.string.owner_search_placeholder)) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.owner_sort_title))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    OwnerSort.values().forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (s) {
                                            OwnerSort.NAME -> R.string.owner_sort_name
                                            OwnerSort.DUES_DESC -> R.string.owner_sort_dues
                                            OwnerSort.CREDIT_DESC -> R.string.owner_sort_credit
                                        }
                                    ),
                                    fontWeight = if (state.sort == s) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = { if (state.sort == s) Icon(Icons.Default.Check, null) },
                            onClick = { sortMenu = false; onSortChanged(s) }
                        )
                    }
                }
            }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                items(OwnerFilter.values().toList()) { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { onFilterChanged(f) },
                        label = {
                            Text(
                                stringResource(
                                    when (f) {
                                        OwnerFilter.ALL -> R.string.owner_filter_all
                                        OwnerFilter.WITH_DUES -> R.string.owner_filter_dues
                                        OwnerFilter.WITH_CREDIT -> R.string.owner_filter_credit
                                        OwnerFilter.SUSPENDED -> R.string.owner_filter_suspended
                                        OwnerFilter.ARCHIVED -> R.string.owner_filter_archived
                                    }
                                )
                            )
                        }
                    )
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillGrid(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.filteredOwners.isEmpty()) {
                Box(modifier = Modifier.fillGrid(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PeopleOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(Dimens.SpaceMD))
                        Text(
                            text = when {
                                state.searchQuery.isNotEmpty() -> stringResource(R.string.owner_search_no_results)
                                state.filter != OwnerFilter.ALL -> stringResource(R.string.owner_empty_filtered)
                                else -> stringResource(R.string.owner_empty_all)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.filteredOwners, key = { it.ownerId }) { owner ->
                        val due = state.dueByOwner[owner.ownerId] ?: 0L
                        Card(
                            onClick = { onSelectOwner(owner) },
                            shape = RoundedCornerShape(Dimens.RadiusLG),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(Dimens.SpaceLG)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OwnerAvatar(owner.name)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = owner.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = owner.phone,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    OwnerStatusBadge(owner.status)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    val isNegative = owner.availableCreditMinor < 0
                                    Text(
                                        text = "${formatMoney(owner.availableCreditMinor)} $currency",
                                        fontWeight = FontWeight.Black,
                                        color = if (isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(R.string.owner_label_available_credit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (due > 0) {
                                        Text(
                                            text = "${stringResource(R.string.owner_label_due)} ${formatMoney(due)} $currency",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.fillGrid(): Modifier = fillMaxWidth().height(200.dp)
