package ps.mgtik.hotspot.ui.components.um

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.UmComponentType
import ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel

/**
 * §8: المكونات التشغيلية الفعلية للباقة كما يقرؤها الراوتر (لا Room ولا Firestore): الـ Profile المرتبط، Limitations، الباقات
 * والمستخدمون الذين يشاركون الـ Profile، وحالة المطابقة مع آخر لقطة معروفة. أزرار الانتقال تفتح شاشتي التعديل المستقلتين.
 */
@Composable
fun UmPackageComponentsSection(
    routerProfileName: String,
    viewModel: UmComponentsViewModel,
    onOpenUmProfile: (String) -> Unit,
    onOpenUmLimitation: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(routerProfileName) { viewModel.refresh() }
    val overview = state.overview
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.um_detail_section), style = MaterialTheme.typography.titleMedium)
            when {
                overview == null && state.loading -> CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                overview == null -> Text(
                    stringResource(state.loadErrorRes ?: R.string.um_err_generic, state.loadErrorArg ?: ""),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
                else -> {
                    val p = overview.graph.profile(routerProfileName)
                    Text(stringResource(R.string.um_detail_profile_state), style = MaterialTheme.typography.labelLarge)
                    if (p == null) {
                        Text(stringResource(R.string.um_detail_missing), color = MaterialTheme.colorScheme.error)
                    } else {
                        TextButton(onClick = { onOpenUmProfile(p.name) }, contentPadding = PaddingValues(0.dp)) {
                            Text("${p.name}  ›  ${stringResource(R.string.um_detail_linked)}")
                        }
                        val usage = overview.profileUsage(p.name)
                        Text(stringResource(R.string.um_detail_shared_packages, usage.packages.size), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.um_detail_users, usage.users.size), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.um_section_limitations), style = MaterialTheme.typography.labelLarge)
                        if (p.limitations.isEmpty()) Text(stringResource(R.string.um_none), style = MaterialTheme.typography.bodySmall)
                        p.limitations.forEach { l ->
                            TextButton(onClick = { onOpenUmLimitation(l) }, contentPadding = PaddingValues(0.dp)) { Text("$l  ›") }
                        }
                        driftLabel(overview.drift(UmComponentType.PROFILE, p.name))?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
