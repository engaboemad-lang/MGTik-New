package ps.mgtik.hotspot.domain.model

/** حقل واحد تغيّرت قيمته محلياً وفي السحابة (يُعرض في حوار حل التعارض). */
data class SettingsConflictField(
    val labelAr: String,
    val localValue: String,
    val cloudValue: String
)

/** تعارض في إعدادات وحدة (module) بين النسخة المحلية والسحابية. */
data class SettingsConflict(
    val moduleNameAr: String,
    val changedFields: List<SettingsConflictField>
)
