package ps.mgtik.hotspot.ui.components.bars

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.navigation.LocalDrawerHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * MgtikTopAppBar - شريط العنوان الموحد لكل الشاشات.
 * يوحّد: نمط العنوان (titleMedium عريض)، ألوان الحاوية (تمتزج مع الخلفية وتتحول إلى
 * surfaceContainer عند التمرير)، ولون الأيقونات. لا تمرّر fontWeight/style على نص العنوان؛
 * أما العنوان الفرعي فيستعمل bodySmall بلون onSurfaceVariant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MgtikTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = {
            ProvideTextStyle(
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            ) { title() }
        },
        modifier = modifier,
        navigationIcon = {
            // القائمة الجانبية: تحلّ محل الرجوع في شاشات الشريط السفلي (وجهات جذرية)، وتظهر في أي شاشة بلا زر رجوع
            val host = LocalDrawerHost.current
            if (host != null && (host.isRoot || navigationIcon == null)) {
                IconButton(onClick = host.open) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.drawer_open))
                }
            } else navigationIcon?.invoke()
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
