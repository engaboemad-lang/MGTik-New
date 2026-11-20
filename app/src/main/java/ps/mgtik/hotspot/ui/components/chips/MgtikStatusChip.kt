package ps.mgtik.hotspot.ui.components.chips

import androidx.compose.foundation.layout.padding
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.ui.theme.MgtikTheme

/** دلالة لون الحالة: نجاح/تحذير/خطأ/معلومة/محايد. تُستعمل لكل حالات الطلبات والفواتير والدفعات. */
enum class StatusTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

/**
 * MgtikStatusChip - شارة حالة موحدة. تستعمل أزواج (container / onContainer) المصممة للتباين
 * بدل نص ملوّن على خلفية شفافة (كان تباين الأصفر/الأخضر على الفاتح دون 4.5:1).
 */
@Composable
fun MgtikStatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    val ext = MgtikTheme.extendedColors
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        StatusTone.SUCCESS -> ext.successContainer to ext.onSuccessContainer
        StatusTone.WARNING -> ext.warningContainer to ext.onWarningContainer
        StatusTone.ERROR -> cs.errorContainer to cs.onErrorContainer
        StatusTone.INFO -> ext.infoContainer to ext.onInfoContainer
        StatusTone.NEUTRAL -> cs.surfaceContainerHigh to cs.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(Dimens.RadiusSM)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = 3.dp)
        )
    }
}
