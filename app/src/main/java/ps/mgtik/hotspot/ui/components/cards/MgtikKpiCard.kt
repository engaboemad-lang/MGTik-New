package ps.mgtik.hotspot.ui.components.cards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.ui.theme.Dimens

/**
 * MgtikKpiCard - مكون مشترك لعرض مؤشرات الأداء الرئيسية (KPIs).
 * تم توحيده لضمان اتساق التصميم عبر كافة الشاشات (S3, S7, S25).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MgtikKpiCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    /** اختياري: عند تمريره تصبح البطاقة قابلة للضغط (انتقال)؛ بدونه تبقى عرضاً فقط كما كانت. */
    onClick: (() -> Unit)? = null,
    /** سطر حالة اختياري تحت القيمة (مثل «مباشر · وقت التحديث»)؛ بدونه تبقى البطاقة كما كانت. */
    supportingText: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val shape = RoundedCornerShape(Dimens.RadiusLG)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val content: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(Dimens.SpaceLG)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = color.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.SpaceSM))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = color
            )
            supportingText?.let {
                Spacer(modifier = Modifier.height(Dimens.SpaceXS))
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = supportingColor)
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, content = content)
    else Card(modifier = modifier, shape = shape, colors = colors, content = content)
}
