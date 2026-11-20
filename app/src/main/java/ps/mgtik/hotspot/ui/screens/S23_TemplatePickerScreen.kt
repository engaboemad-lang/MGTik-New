package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.RuntimeTemplate
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.MgtikUiState
import ps.mgtik.hotspot.ui.viewmodel.MgtikViewModel

/**
 * S23_TemplatePickerScreen - مستعرض ومعاين القوالب (§23).
 * تم التدقيق هندسياً:
 * 1. الربط المباشر مع محرك المزامنة السحابي.
 * 2. دعم معاينة مواصفات الورق والاتجاه (Orientation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S23_TemplatePickerScreen(
    viewModel: MgtikViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.template_gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncTemplates() }, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.template_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.SpaceLG),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLG)
        ) {
            Text(
                text = stringResource(R.string.template_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.templates.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ViewQuilt, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(Dimens.SpaceMD))
                        Text(stringResource(R.string.template_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { viewModel.syncTemplates() }, enabled = !state.isLoading, modifier = Modifier.padding(top = Dimens.SpaceLG)) {
                            Text(stringResource(R.string.template_sync_now))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.templates, key = { it.templateId }) { template ->
                        TemplateCard(template, isDefault = state.defaultTemplateId == template.templateId, onSetDefault = { viewModel.setDefaultTemplate(template.templateId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: RuntimeTemplate, isDefault: Boolean, onSetDefault: () -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceMD)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(bottom = Dimens.SpaceSM),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(text = template.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(text = stringResource(R.string.template_paper_fmt, template.paperHint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(R.string.template_version_fmt, template.version.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(Dimens.SpaceSM))
            
            Button(
                onClick = onSetDefault,
                enabled = !isDefault,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(Dimens.RadiusSM)
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(if (isDefault) R.string.template_is_default else R.string.template_adopt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
