package ps.mgtik.hotspot.ui.util

import androidx.compose.runtime.compositionLocalOf

/**
 * رمز العملة الموحَّد لكل الشاشات: يُضبط مرة واحدة في MainScreen من إعدادات الشركة (Firestore)،
 * فيتغير في كل التطبيق فور حفظه دون إعادة تشغيل. القيمة الافتراضية تُستعمل قبل وصول الإعداد.
 */
val LocalCurrencySymbol = compositionLocalOf { "₪" }
