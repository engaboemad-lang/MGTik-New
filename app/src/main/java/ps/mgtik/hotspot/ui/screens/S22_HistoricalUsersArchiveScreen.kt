package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.layout.*
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.ArchiveType
import ps.mgtik.hotspot.domain.model.ArchivedUserSnapshot
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.formatIsoDateTime
import ps.mgtik.hotspot.ui.viewmodel.UsersViewModel

/**
 * S22_HistoricalUsersArchiveScreen - أرشيف المستخدمين السابقين (§22).
 * تم التطهير البرمجي:
 * 1. Resource Bound: ربط كافة النصوص بـ strings.xml.
 * 2. Theme Compliance: استخدام ألوان النظام و Dimens الموحدة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S22_HistoricalUsersArchiveScreen(
    viewModel: UsersViewModel,
    owners: List<OwnerAccount> = emptyList(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    // كل فتح للشاشة يقرأ الأرشيف من أول صفحة: يلتقط ما أُرشف منذ آخر زيارة (من هذا الجهاز أو غيره)
    LaunchedEffect(Unit) { viewModel.loadArchivedUsers() }

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ArchiveSort.NEWEST) }
    var detailsTarget by remember { mutableStateOf<ArchivedUserSnapshot?>(null) }
    val ownerNames = remember(owners) { owners.associate { it.ownerId to it.name } }

    // اسم المالك: اللقطة وقت الأرشفة أولاً (تبقى صحيحة لو أُرشف المالك/غُيّر اسمه)، ثم الحالي، ثم المعرّف
    fun ownerLabel(u: ArchivedUserSnapshot): String? {
        val id = u.administrativeSnapshot["ownerId"].orEmpty()
        return u.administrativeSnapshot["ownerName"]?.takeIf { it.isNotBlank() }
            ?: ownerNames[id]?.takeIf { it.isNotBlank() }
            ?: id.takeIf { it.isNotBlank() }
    }

    val visibleUsers = remember(state.archivedUsers, query, sort, ownerNames) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) state.archivedUsers
        else state.archivedUsers.filter {
            it.routerUsername.contains(q, ignoreCase = true) ||
                it.reason.contains(q, ignoreCase = true) ||
                it.originalRecordUuid.contains(q, ignoreCase = true) ||
                ownerLabel(it)?.contains(q, ignoreCase = true) == true
        }
        when (sort) {
            ArchiveSort.NEWEST -> filtered.sortedByDescending { it.archivedAt }
            ArchiveSort.OLDEST -> filtered.sortedBy { it.archivedAt }
            ArchiveSort.NAME -> filtered.sortedBy { it.routerUsername.lowercase() }
        }
    }

    // البحث لا يعمل على سيرفر (Firestore بلا بحث جزئي): أثناء البحث نكمل جلب الصفحات حتى يغطي كل الأرشيف
    LaunchedEffect(query, state.archivedUsers.size, state.hasMoreArchived, state.isLoading, state.isLoadingMore, state.archiveErrorResId) {
        if (query.isNotBlank() && state.hasMoreArchived && !state.isLoading && !state.isLoadingMore && state.archiveErrorResId == null) {
            viewModel.loadMoreArchivedUsers()
        }
    }

    detailsTarget?.let { target ->
        ArchivedUserDetailsDialog(user = target, ownerName = ownerLabel(target), onDismiss = { detailsTarget = null })
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.archive_users_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.archive_users_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
            ) {
                ArchiveSort.values().forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        ArchiveSort.NEWEST -> R.string.archive_users_sort_newest
                                        ArchiveSort.OLDEST -> R.string.archive_users_sort_oldest
                                        ArchiveSort.NAME -> R.string.archive_users_sort_name
                                    }
                                )
                            )
                        }
                    )
                }
            }

            val errorResId = state.archiveErrorResId
            if (state.isLoading && state.archivedUsers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorResId != null && state.archivedUsers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                        Text(stringResource(errorResId), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { viewModel.loadArchivedUsers() }) {
                            Text(stringResource(R.string.archive_users_retry))
                        }
                    }
                }
            } else if (visibleUsers.isEmpty() && !state.hasMoreArchived) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(Dimens.SpaceMD))
                        Text(
                            stringResource(if (query.isBlank()) R.string.archive_users_empty else R.string.archive_users_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                // تحميل تدريجي: عند الاقتراب من نهاية القائمة المحمَّلة تُجلب الصفحة التالية
                val nearEnd by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= info.totalItemsCount - 3
                    }
                }
                LaunchedEffect(nearEnd, state.hasMoreArchived, state.archivedUsers.size) {
                    if (nearEnd && state.hasMoreArchived) viewModel.loadMoreArchivedUsers()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    items(visibleUsers, key = { it.archiveId }) { user ->
                        ArchivedUserCard(user, ownerName = ownerLabel(user), onClick = { detailsTarget = user })
                    }
                    if (state.isLoadingMore || state.hasMoreArchived || errorResId != null) {
                        item(key = "footer") {
                            Box(modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceMD), contentAlignment = Alignment.Center) {
                                when {
                                    state.isLoadingMore -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    errorResId != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(stringResource(errorResId), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { viewModel.loadMoreArchivedUsers() }) {
                                            Text(stringResource(R.string.archive_users_retry))
                                        }
                                    }
                                    else -> TextButton(onClick = { viewModel.loadMoreArchivedUsers() }) {
                                        Text(stringResource(R.string.archive_users_load_more))
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

private enum class ArchiveSort { NEWEST, OLDEST, NAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedUserCard(user: ArchivedUserSnapshot, ownerName: String? = null, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = user.routerUsername,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = formatIsoDateTime(user.archivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!ownerName.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.archive_users_label_owner, ownerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.archive_users_label_reason, user.reason),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(archiveTypeLabel(user.archiveType)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.archive_users_label_record_id, user.originalRecordUuid.take(8)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(Dimens.RadiusXS)
                ) {
                    Text(
                        text = stringResource(R.string.archive_users_status_archived),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = Dimens.SpaceXXS)
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedUserDetailsDialog(user: ArchivedUserSnapshot, ownerName: String?, onDismiss: () -> Unit) {
    @Composable
    fun Field(label: String, value: String) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_users_details_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
            ) {
                Text(user.routerUsername, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (!ownerName.isNullOrBlank()) Field(stringResource(R.string.user_detail_owner), ownerName)
                Field(stringResource(R.string.archive_users_detail_reason), user.reason)
                Field(stringResource(R.string.archive_type_label), stringResource(archiveTypeLabel(user.archiveType)))
                Field(stringResource(R.string.archive_users_detail_archived_at), formatIsoDateTime(user.archivedAt))
                Field(stringResource(R.string.archive_users_detail_record), user.originalRecordUuid)
                user.administrativeSnapshot["status"]?.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.user_detail_status), it) }
                user.administrativeSnapshot["origin"]?.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.user_detail_origin), it) }
                user.administrativeSnapshot["batchId"]?.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.user_detail_batch), it) }
                user.administrativeSnapshot["createdAt"]?.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.user_detail_created), formatIsoDateTime(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

private fun archiveTypeLabel(type: ArchiveType): Int = when (type) {
    ArchiveType.DISABLED -> R.string.archive_type_disabled
    ArchiveType.MISSING -> R.string.archive_type_missing
}
