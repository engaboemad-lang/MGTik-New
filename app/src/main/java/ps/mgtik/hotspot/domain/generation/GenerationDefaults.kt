package ps.mgtik.hotspot.domain.generation

/**
 * GenerationDefaults - إعدادات التوليد القابلة لإعادة الاستعمال (محلية للجهاز، لا تمسّ الباقة ولا السحابة).
 *
 * تُحفظ آخر إعدادات استُعملت **لكل باقة** (تُطبَّق عند اختيارها)، وآخر إعدادات عامة كاحتياط لباقة لم تُستعمل بعد، ثم القيم المصنعية.
 * لا تشمل الوكيل ولا العدد ولا التعليق (قرارات كل عملية، ولا بيع بلا وكيل صريح).
 * الصيغة النصية `prefix;len;format;mode;pwType;pwLen` آمنة لأن البادئة محصورة بـ [A-Za-z0-9_-]؛ نص تالف أو لم يعد صالحاً
 * وفق [GenerationRules] ⇒ null (يُتجاهل بدل أن يُطبَّق صامتاً).
 */
data class GenerationDefaults(
    val prefix: String = "",
    val codeLength: Int = 6,
    val format: String = CodeFormat.NUMERIC_ONLY.name,
    val mode: String = GenerationMode.RANDOM.name,
    val passwordType: String = PasswordType.NUMERIC_ONLY.name,
    val passwordLength: Int = 6
) {
    fun encode(): String = listOf(prefix, codeLength, format, mode, passwordType, passwordLength).joinToString(SEP)

    companion object {
        private const val SEP = ";"
        val FACTORY = GenerationDefaults()

        fun decode(raw: String?): GenerationDefaults? {
            val p = raw?.split(SEP) ?: return null
            if (p.size != 6) return null
            val d = GenerationDefaults(
                prefix = p[0],
                codeLength = p[1].toIntOrNull() ?: return null,
                format = p[2], mode = p[3], passwordType = p[4],
                passwordLength = p[5].toIntOrNull() ?: return null
            )
            // الفحص بكمية 1 يكفي لصلاحية الأنواع والأطوال والتوافق (تسلسلي ⇒ أرقام فقط)، والكمية الحقيقية تُفحص عند البدء
            val errors = GenerationRules.validate(
                "p", "o", 1, d.format, d.prefix, d.codeLength, d.mode, d.passwordType,
                if (PasswordType.parse(d.passwordType) == PasswordType.SAME_AS_USERNAME) 0 else d.passwordLength
            )
            return d.takeIf { errors.isEmpty() }
        }
    }
}
