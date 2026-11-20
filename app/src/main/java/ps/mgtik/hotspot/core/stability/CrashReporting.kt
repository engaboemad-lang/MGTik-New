package ps.mgtik.hotspot.core.stability

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * تقارير الأعطال (Crashlytics) دون تعطيل المعالج الافتراضي للنظام.
 * Crashlytics يثبّت معالجه عند تهيئته (قبل Application.onCreate)، ونحن نلفّه بمعالج يضيف سياقاً غير حساس
 * ثم يسلّم الاستثناء دائماً للمعالج السابق (Crashlytics ثم النظام): لا يُبتلع أي استثناء ولا يُمنع إغلاق العملية.
 */
object CrashReporting {

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // اسم الخيط ونوع الاستثناء فقط: لا رسائل الاستثناء (قد تحمل بيانات مستخدم أو كلمات مرور)
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("crash_thread", thread.name)
                    log("uncaught:" + error.javaClass.name)
                }
            } catch (_: Throwable) {
                // فشل التقرير لا يجوز أن يمنع تسليم الاستثناء للنظام
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    /** سياق غير حساس يُرفق بأي بلاغ لاحق. */
    fun setKey(key: String, value: String) {
        try { FirebaseCrashlytics.getInstance().setCustomKey(key, value) } catch (_: Throwable) { }
    }

    fun log(message: String) {
        try { FirebaseCrashlytics.getInstance().log(message) } catch (_: Throwable) { }
    }

    /** للاختبار في نسخة Debug فقط: انهيار متعمَّد ليظهر في لوحة Crashlytics. */
    fun testCrash(): Nothing = throw RuntimeException("MGTik test crash (debug only)")
}
