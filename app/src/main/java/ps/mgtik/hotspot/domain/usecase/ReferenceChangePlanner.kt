package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.ReferenceChangeAction
import ps.mgtik.hotspot.domain.model.ReferenceChangeBlock

/**
 * قرار تغيير المرجع التقني (نقي): يستعمله المعاينة (عرض الأثر) والتنفيذ معاً فلا يختلفان.
 *  - المرجع الجديد موجود على الراوتر ⇒ ربط ببروفايل قائم (القديم يبقى على الراوتر كما هو).
 *  - غير موجود لكن القديم موجود ⇒ إعادة تسمية بروفايل الراوتر.
 *  - لا الجديد ولا القديم على الراوتر ⇒ مرفوض: لا يُربط بمرجع غير موجود.
 */
object ReferenceChangePlanner {
    data class Plan(val action: ReferenceChangeAction, val block: ReferenceChangeBlock? = null)

    fun plan(
        oldName: String,
        newName: String,
        oldOnRouter: Boolean,
        newOnRouter: Boolean,
        usedByOtherProfile: Boolean
    ): Plan = when {
        !ProfileInputValidator.isRouterNameValid(newName) || newName.equals("default", ignoreCase = true) ->
            Plan(ReferenceChangeAction.BLOCKED, ReferenceChangeBlock.INVALID_NAME)
        newName == oldName -> Plan(ReferenceChangeAction.BLOCKED, ReferenceChangeBlock.SAME_NAME)
        usedByOtherProfile -> Plan(ReferenceChangeAction.BLOCKED, ReferenceChangeBlock.USED_BY_OTHER_PROFILE)
        newOnRouter -> Plan(ReferenceChangeAction.REPOINT_TO_EXISTING)
        oldOnRouter -> Plan(ReferenceChangeAction.RENAME_ROUTER_PROFILE)
        else -> Plan(ReferenceChangeAction.BLOCKED, ReferenceChangeBlock.NEW_NOT_ON_ROUTER)
    }
}
