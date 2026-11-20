package ps.mgtik.hotspot.domain.export

import ps.mgtik.hotspot.domain.model.BackgroundType
import ps.mgtik.hotspot.domain.model.ElementType
import ps.mgtik.hotspot.domain.model.ShapeKind
import ps.mgtik.hotspot.domain.model.TemplateBackground
import ps.mgtik.hotspot.domain.model.TemplateCanvas
import ps.mgtik.hotspot.domain.model.TemplateDefinition
import ps.mgtik.hotspot.domain.model.TemplateElement
import ps.mgtik.hotspot.domain.model.TextAlign

/**
 * DefaultVoucherTemplate - قالب مضمَّن بالكود (نفس مخطط TemplateDefinition البعيد) يضمن وجود قالب صالح للتصدير
 * دون اتصال أو قبل أول مزامنة. ليس نظام قوالب ثانياً: هو تعريف من النوع نفسه يمرّ عبر المحرك نفسه.
 *
 * اللوحة 300×170، تصميم عربي (RTL) عصري: شريط رأس بلون الهوية فيه اسم الباقة (يمين) وترقيم الكرت س/ن (يسار)،
 * ثم رمز QR يسار الجسم وحقلا اسم المستخدم وكلمة المرور في مربعين مدوّرين يمين (نص عربي محاذٍ لليمين، والأكواد
 * بخط عريض متوسطة المحاذاة لوضوح القراءة)، والسعر في التذييل. الإطار بحدّ رفيع بدل ظلّ لأن الطباعة لا تدعم الظل.
 */
object DefaultVoucherTemplate {
    const val ID = "builtin_default"

    val definition: TemplateDefinition = TemplateDefinition(
        canvas = TemplateCanvas(
            width = 300.0,
            height = 170.0,
            background = TemplateBackground(type = BackgroundType.COLOR, color = "#FFFFFF")
        ),
        elements = listOf(
            TemplateElement(id = "frame", type = ElementType.SHAPE, x = 0.0, y = 0.0, width = 300.0, height = 170.0, zIndex = 0, color = "#E2E8F0", borderRadius = 12.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "inner", type = ElementType.SHAPE, x = 1.0, y = 1.0, width = 298.0, height = 168.0, zIndex = 1, color = "#FFFFFF", borderRadius = 11.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "head", type = ElementType.SHAPE, x = 1.0, y = 1.0, width = 298.0, height = 40.0, zIndex = 2, color = "#1E3A8A", borderRadius = 11.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "head_sq", type = ElementType.SHAPE, x = 1.0, y = 22.0, width = 298.0, height = 19.0, zIndex = 2, color = "#1E3A8A", borderRadius = 0.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "profile", type = ElementType.DYNAMIC, variable = "profile_ar", x = 100.0, y = 9.0, width = 186.0, height = 24.0, zIndex = 3, fontSize = 15.0, fontWeight = "700", color = "#FFFFFF", align = TextAlign.RIGHT),
            TemplateElement(id = "seq_pill", type = ElementType.SHAPE, x = 14.0, y = 12.0, width = 64.0, height = 18.0, zIndex = 3, opacity = 0.18, color = "#FFFFFF", borderRadius = 9.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "seq", type = ElementType.TEXT, text = "{{batch_sequence}} / {{batch_total_users}}", x = 14.0, y = 12.0, width = 64.0, height = 18.0, zIndex = 4, fontSize = 9.0, fontWeight = "700", color = "#FFFFFF", align = TextAlign.CENTER),
            TemplateElement(id = "qr", type = ElementType.DYNAMIC, variable = "qr", x = 16.0, y = 52.0, width = 88.0, height = 88.0, zIndex = 3),
            TemplateElement(id = "ulabel", type = ElementType.TEXT, text = "اسم المستخدم", x = 112.0, y = 48.0, width = 174.0, height = 14.0, zIndex = 3, fontSize = 9.0, color = "#64748B", align = TextAlign.RIGHT),
            TemplateElement(id = "ubox", type = ElementType.SHAPE, x = 112.0, y = 62.0, width = 174.0, height = 30.0, zIndex = 2, color = "#EFF6FF", borderRadius = 8.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "username", type = ElementType.DYNAMIC, variable = "username", x = 116.0, y = 62.0, width = 166.0, height = 30.0, zIndex = 3, fontSize = 19.0, fontWeight = "700", color = "#1E3A8A", align = TextAlign.CENTER),
            TemplateElement(id = "plabel", type = ElementType.TEXT, text = "كلمة المرور", x = 112.0, y = 98.0, width = 174.0, height = 14.0, zIndex = 3, fontSize = 9.0, color = "#64748B", align = TextAlign.RIGHT),
            TemplateElement(id = "pbox", type = ElementType.SHAPE, x = 112.0, y = 112.0, width = 174.0, height = 30.0, zIndex = 2, color = "#F1F5F9", borderRadius = 8.0, shapeKind = ShapeKind.RECT),
            TemplateElement(id = "password", type = ElementType.DYNAMIC, variable = "password", x = 116.0, y = 112.0, width = 166.0, height = 30.0, zIndex = 3, fontSize = 19.0, fontWeight = "700", color = "#0F172A", align = TextAlign.CENTER),
            TemplateElement(id = "price", type = ElementType.DYNAMIC, variable = "price_formatted", x = 112.0, y = 148.0, width = 174.0, height = 16.0, zIndex = 3, fontSize = 11.0, fontWeight = "700", color = "#475569", align = TextAlign.RIGHT)
        )
    )
}
