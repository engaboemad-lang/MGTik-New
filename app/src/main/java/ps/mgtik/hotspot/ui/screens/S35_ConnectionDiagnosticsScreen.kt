package ps.mgtik.hotspot.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.BuildConfig
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.stability.CrashReporting
import ps.mgtik.hotspot.ui.components.router.ConnectionStatusLines
import ps.mgtik.hotspot.ui.util.RouterErrorTexts
import ps.mgtik.hotspot.ui.viewmodel.RouterStatusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S35: تشخيص الاتصال — الحالة الحالية (نفس مصدر الشريط والإشعار) وآخر 50 حدثاً مع تصدير.
 * السجل لا يحمل كلمات مرور ولا بيانات حساسة (وقت/حالة/رمز سبب/مدة/نتيجة محاولة).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S35_ConnectionDiagnosticsScreen(viewModel: RouterStatusViewModel, onBack: () -> Unit) {
    val status by viewModel.status.collectAsState()
    val log by viewModel.log.collectAsState()
    val context = LocalContext.current
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ENGLISH)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionStatusLines(status = status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::retry, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.router_action_retry_connection))
                }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, viewModel.exportLog())
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    enabled = log.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.conn_diag_export)) }
            }
            if (BuildConfig.DEBUG) {
                OutlinedButton(onClick = { CrashReporting.testCrash() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conn_diag_test_crash))
                }
            }
            if (log.isEmpty()) {
                Text(stringResource(R.string.conn_diag_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(log.asReversed()) { e ->
                        val reason = e.reasonCode?.let { code ->
                            if (code.startsWith("ERR_")) stringResource(RouterErrorTexts.reasonRes(code)) else code
                        }
                        Column {
                            Text(
                                "${fmt.format(Date(e.atMillis))} · ${e.state.name}" +
                                    (e.durationMs?.let { " · ${it / 1000}s" } ?: "") +
                                    (e.reconnectResult?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            if (reason != null) {
                                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
