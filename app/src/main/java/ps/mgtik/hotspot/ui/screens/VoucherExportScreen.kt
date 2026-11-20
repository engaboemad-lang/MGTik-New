package ps.mgtik.hotspot.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.export.ExportFormat
import ps.mgtik.hotspot.domain.export.LayoutPlanner
import ps.mgtik.hotspot.domain.model.PageSize
import ps.mgtik.hotspot.domain.model.PrintOrientation
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.ShareFiles
import ps.mgtik.hotspot.ui.viewmodel.ExportAction
import ps.mgtik.hotspot.ui.viewmodel.ExportEvent
import ps.mgtik.hotspot.ui.viewmodel.ExportViewModel
import java.io.File

/**
 * VoucherExportScreen - تصدير الدفعة كاملة إلى PDF (للطباعة) أو TXT (للإرسال/الاستيراد).
 * يختار المستخدم القالب والورق والأعمدة، والمعاينة تُرسم بمحرك PDF نفسه، ثم مشاركة أو حفظ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherExportScreen(viewModel: ExportViewModel, batchId: String, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(batchId) { viewModel.load(batchId) }

    LaunchedEffect(state.messageRes) {
        state.messageRes?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            viewModel.consumeMessage()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.format.mime)) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) viewModel.onSaved(ShareFiles.copyTo(context, file, uri))
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ExportEvent.FileReady) {
                when (event.action) {
                    ExportAction.SHARE -> ShareFiles.share(context, event.file, event.format.mime)
                    ExportAction.SAVE -> { pendingSave = event.file; saveLauncher.launch(event.file.name) }
                }
            }
        }
    }

    val batchNumber = state.batch?.batchNumber.orEmpty()
    val txtHeader = stringResource(R.string.export_txt_header, batchNumber, state.batch?.profileName.orEmpty(), state.voucherCount)
    val txtColumns = stringResource(R.string.export_txt_columns)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.export_title))
                        if (state.batch != null) Text(stringResource(R.string.export_subtitle, batchNumber, state.voucherCount), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.export_loading), modifier = Modifier.padding(top = Dimens.SpaceMD))
                }
            }
            state.loadErrorRes != null -> Box(Modifier.fillMaxSize().padding(padding).padding(Dimens.SpaceXXL), contentAlignment = Alignment.Center) {
                Text(stringResource(state.loadErrorRes!!), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
            ) {
                Section(stringResource(R.string.export_section_format)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        FilterChip(selected = state.format == ExportFormat.PDF, onClick = { viewModel.setFormat(ExportFormat.PDF) }, label = { Text(stringResource(R.string.export_format_pdf)) })
                        FilterChip(selected = state.format == ExportFormat.TXT, onClick = { viewModel.setFormat(ExportFormat.TXT) }, label = { Text(stringResource(R.string.export_format_txt)) })
                    }
                }

                if (state.format == ExportFormat.PDF) {
                    Section(stringResource(R.string.export_section_template)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            FilterChip(selected = state.selectedTemplateId == null, onClick = { viewModel.setTemplate(null) }, label = { Text(stringResource(R.string.export_template_builtin)) })
                            state.templates.forEach { t ->
                                FilterChip(selected = state.selectedTemplateId == t.templateId, onClick = { viewModel.setTemplate(t.templateId) }, label = { Text(t.title, maxLines = 1) })
                            }
                        }
                    }

                    Section(stringResource(R.string.export_section_page)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            FilterChip(selected = state.settings.page == PageSize.A4, onClick = { viewModel.setPage(PageSize.A4) }, label = { Text(stringResource(R.string.export_page_a4)) })
                            FilterChip(selected = state.settings.page == PageSize.A5, onClick = { viewModel.setPage(PageSize.A5) }, label = { Text(stringResource(R.string.export_page_a5)) })
                            FilterChip(selected = state.settings.orientation == PrintOrientation.PORTRAIT, onClick = { viewModel.setOrientation(PrintOrientation.PORTRAIT) }, label = { Text(stringResource(R.string.export_orientation_portrait)) })
                            FilterChip(selected = state.settings.orientation == PrintOrientation.LANDSCAPE, onClick = { viewModel.setOrientation(PrintOrientation.LANDSCAPE) }, label = { Text(stringResource(R.string.export_orientation_landscape)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.setColumns(state.settings.columns - 1) }, enabled = state.settings.columns > 1) { Icon(Icons.Default.Remove, null) }
                            Text(stringResource(R.string.export_columns, state.settings.columns), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { viewModel.setColumns(state.settings.columns + 1) }, enabled = state.settings.columns < LayoutPlanner.MAX_COLUMNS) { Icon(Icons.Default.Add, null) }
                        }
                    }

                    Section(stringResource(R.string.export_preview_title)) {
                        val layoutError = state.layoutErrorRes
                        val preview = state.preview
                        if (layoutError != null) {
                            Text(stringResource(layoutError), color = MaterialTheme.colorScheme.error)
                        } else if (preview != null) {
                            Card(shape = RoundedCornerShape(Dimens.RadiusMD), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                                Image(bitmap = preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                // الأسهم بحسب الاتجاه الفعلي للواجهة (AutoMirrored) فلا تنعكس في RTL
                                IconButton(onClick = viewModel::previousPage, enabled = state.previewPage > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.export_preview_prev)) }
                                Text(stringResource(R.string.export_preview_page, state.previewPage + 1, state.pageCount), style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = viewModel::nextPage, enabled = state.previewPage < state.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.export_preview_next)) }
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    }
                } else {
                    Text(stringResource(R.string.export_txt_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (state.isExporting) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        Text(stringResource(R.string.export_in_progress, state.progress), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }

                val canExport = !state.isExporting && state.voucherCount > 0 && (state.format == ExportFormat.TXT || (state.layoutErrorRes == null && state.preview != null))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.export(ExportAction.SHARE, txtHeader, txtColumns) }, enabled = canExport, modifier = Modifier.weight(1f).height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                        Text(stringResource(R.string.export_btn_share), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { viewModel.export(ExportAction.SAVE, txtHeader, txtColumns) }, enabled = canExport, modifier = Modifier.weight(1f).height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                        Text(stringResource(R.string.export_btn_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
