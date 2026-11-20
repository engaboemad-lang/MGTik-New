package ps.mgtik.hotspot.ui.drawers

import androidx.compose.ui.res.stringResource
import ps.mgtik.hotspot.R
import androidx.compose.foundation.layout.*
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.model.NotificationCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsDrawerSheet(
    notifications: List<MgtikNotification>,
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    val systemList = notifications.filter { it.category == NotificationCategory.SYSTEM }
    val billingList = notifications.filter { it.category == NotificationCategory.BILLING }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.85f),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(Dimens.SpaceLG)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.notif_drawer_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceMD))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.notif_drawer_system_fmt, systemList.size)) },
                    icon = { Icon(Icons.Default.Router, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.notif_drawer_billing_fmt, billingList.size)) },
                    icon = { Icon(Icons.Default.Receipt, contentDescription = null) }
                )
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceLG))

            val currentList = if (selectedTab == 0) systemList else billingList

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
            ) {
                items(currentList) { item ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(Dimens.SpaceMD)) {
                            Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(Dimens.SpaceXS))
                            Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
