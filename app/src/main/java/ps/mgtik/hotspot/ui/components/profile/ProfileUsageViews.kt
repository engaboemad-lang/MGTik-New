package ps.mgtik.hotspot.ui.components.profile

import androidx.compose.foundation.layout.Arrangement
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.util.formatMinor
import ps.mgtik.hotspot.domain.model.CategoryHistoryAction
import ps.mgtik.hotspot.domain.model.CategoryHistoryEntry
import ps.mgtik.hotspot.domain.model.FieldChange
import ps.mgtik.hotspot.domain.model.SaleContext

/**
 * سطر ملخص الاستخدام: الأرقام فعلية من الخادم ([usage] = null مع [failed] ⇒ تعذّر العدّ، ومع [loading] ⇒ جارٍ؛ لا أصفار بديلة).
 */
@Composable
fun UsageSummaryText(
    usage: ps.mgtik.hotspot.domain.model.ProfileUsage?,
    loading: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier
) {
    val text = when {
        usage != null -> stringResource(R.string.usage_summary_fmt, usage.normalUsers, usage.subscriptions, usage.batches)
        failed -> stringResource(R.string.usage_failed)
        loading -> stringResource(R.string.usage_loading)
        else -> stringResource(R.string.usage_loading)
    }
    Text(
        text, modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = if (usage == null && failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun fieldLabelRes(field: String): Int = when (field) {
    "retail_price_minor" -> R.string.cathist_field_retail
    "wholesale_price_minor" -> R.string.cathist_field_wholesale
    "currency" -> R.string.cathist_field_currency
    "status" -> R.string.cathist_field_status
    "owner_price_minor" -> R.string.cathist_field_owner_price
    "active" -> R.string.cathist_field_active
    else -> R.string.cathist_field_other
}

private fun isMoney(field: String) = field == "retail_price_minor" || field == "wholesale_price_minor" || field == "owner_price_minor"

private fun actionRes(a: CategoryHistoryAction): Int = when (a) {
    CategoryHistoryAction.CATEGORY_CREATED -> R.string.cathist_action_created
    CategoryHistoryAction.CATEGORY_UPDATED -> R.string.cathist_action_updated
    CategoryHistoryAction.STATUS_CHANGED -> R.string.cathist_action_status
    CategoryHistoryAction.OWNER_PRICE_SET -> R.string.cathist_action_owner_set
    CategoryHistoryAction.OWNER_PRICE_ACTIVE_CHANGED -> R.string.cathist_action_owner_active
    CategoryHistoryAction.OWNER_PRICE_DELETED -> R.string.cathist_action_owner_deleted
}

@Composable
private fun valueText(c: FieldChange, v: String?): String {
    if (v == null) return stringResource(R.string.cathist_unset)
    return when {
        isMoney(c.field) -> v.toLongOrNull()?.let { formatMinor(it) } ?: v
        else -> v
    }
}

/** قسم سجل التعديلات التجارية للفئة: تاريخ كل تعديل، نوعه، والقيمة السابقة والجديدة لكل حقل. للقراءة فقط. */
@Composable
fun CategoryHistorySection(entries: List<CategoryHistoryEntry>, ownerNames: Map<String, String>) {
    Text(stringResource(R.string.cathist_title), fontWeight = FontWeight.Bold)
    if (entries.isEmpty()) {
        Text(stringResource(R.string.cathist_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { e ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXXS)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(actionRes(e.action)), fontWeight = FontWeight.Medium)
                    Text(
                        e.at.replace('T', ' ').take(16).ifBlank { stringResource(R.string.cathist_pending) },
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                e.ownerId?.takeIf { it.isNotBlank() }?.let { id ->
                    val ctx = e.context?.let { stringResource(if (it == SaleContext.WHOLESALE) R.string.pricing_ctx_wholesale else R.string.pricing_ctx_retail) }
                    Text(
                        stringResource(R.string.cathist_owner_fmt, ownerNames[id] ?: id) + (ctx?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                e.changes.forEach { c ->
                    Text(
                        stringResource(R.string.cathist_change_fmt, stringResource(fieldLabelRes(c.field)), valueText(c, c.oldValue), valueText(c, c.newValue)),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
                    )
                }
            }
        }
    }
}

/** نص مصدر السعر المحفوظ على اشتراك/دفعة (يُخزَّن بأحرف صغيرة أو كبيرة بحسب المسار)؛ null = لا لقطة تسعير (سجل سابق للتسعير المركزي). */
fun priceSourceLabelRes(stored: String): Int? = when (stored.trim().uppercase()) {
    "OWNER_SPECIAL" -> R.string.profile_detail_src_owner
    "LEGACY_OWNER_SPECIAL" -> R.string.profile_detail_src_legacy_owner
    "WHOLESALE" -> R.string.profile_detail_src_wholesale
    "RETAIL" -> R.string.profile_detail_src_retail
    "LEGACY_PROFILE" -> R.string.profile_detail_src_legacy_profile
    else -> null
}
