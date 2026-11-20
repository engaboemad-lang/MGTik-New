package ps.mgtik.hotspot.domain.usecase

import java.util.regex.Pattern

/**
 * AuthValidator - المسؤول عن قواعد العمل للتحقق من سلامة مدخلات المصادقة (§4.1).
 * تم عزله في طبقة الـ Domain لضمان:
 * 1. إمكانية إجراء اختبارات الوحدة (Unit Testing) للمنطق بعيداً عن أندرويد SDK.
 * 2. توحيد شروط القوة لكلمة المرور وصيغة البريد الإلكتروني عبر كافة المنصات.
 */
object AuthValidator {
    
    // نمط RFC 5322 للتحقق من البريد الإلكتروني بدون الاعتماد على android.util.Patterns
    private val EMAIL_PATTERN = Pattern.compile(
        "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * التحقق من صحة صيغة البريد الإلكتروني.
     */
    fun isEmailValid(email: String): Boolean {
        return email.isNotBlank() && EMAIL_PATTERN.matcher(email).matches()
    }

    /**
     * التحقق من قوة كلمة المرور (6 أحرف على الأقل حسب سياسة Firebase الافتراضية).
     */
    fun isPasswordValid(password: String): Boolean {
        return password.length >= 6
    }
}
