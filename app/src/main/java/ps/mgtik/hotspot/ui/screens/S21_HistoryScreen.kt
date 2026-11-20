package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.layout.*
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.HistoricalBatch
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.ShareFiles
import ps.mgtik.hotspot.ui.viewmodel.HistoryEvent
import ps.mgtik.hotspot.ui.viewmodel.HistoryViewModel
import java.io.File

/**
 * S21_HistoryScreen - سجل الأرشيف والدفعات التاريخية (§21).
 * تم التطهير البرمجي:
 * 1. Resource Bound: ربط كافة النصوص بـ strings.xml.
 * 2. Theme Compliance: استخدام ألوان النظام و Dimens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S21_HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val messageResId = state.successMessageResId ?: state.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    // تسليم ملف CSV للمستخدم فعلاً (كان «نجاح التصدير» يظهر دون أي ملف)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is HistoryEvent.ExportReady) {
                val ok = runCatching {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "history_${System.currentTimeMillis()}.csv")
                    // BOM كي يقرأ Excel العربية بترميز UTF-8 صحيح
                    file.writeText("\uFEFF" + event.csv, Charsets.UTF_8)
                    ShareFiles.share(context, file, "text/csv")
                }.isSuccess
                viewModel.onExportFileResult(ok)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportToCsv() }, enabled = !state.isExporting) {
                        if (state.isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.action_export_csv))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.batches.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    items(state.batches, key = { it.batchId }) { batch ->
                        HistoricalBatchCard(batch)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricalBatchCard(batch: HistoricalBatch) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.history_label_batch, batch.batchId), 
                    fontWeight = FontWeight.Black, 
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(Dimens.RadiusXS)
                ) {
                    Text(
                        text = batch.status.ifBlank { stringResource(R.string.history_status_unknown) },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = Dimens.SpaceXXS),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Text(
                text = stringResource(R.string.history_label_owner, batch.ownerName), 
                fontWeight = FontWeight.Bold, 
                style = MaterialTheme.typography.bodyMedium
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(text = stringResource(R.string.history_label_count, batch.cardsCount), style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = stringResource(R.string.history_label_date, batch.generatedAt), 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${formatMoney(batch.totalAmountMinor)} ${LocalCurrencySymbol.current}",
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
