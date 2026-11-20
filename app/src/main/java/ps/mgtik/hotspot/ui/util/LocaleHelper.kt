package ps.mgtik.hotspot.ui.util

import android.content.Context
import android.content.res.Configuration
import ps.mgtik.hotspot.data.local.prefs.AppLanguage
import ps.mgtik.hotspot.data.local.prefs.AppPreferences
import java.util.Locale

/** يطبّق لغة التطبيق المختارة على Context الـ Activity (يُستدعى من attachBaseContext). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val locale = when (AppPreferences.readLanguage(base)) {
            AppLanguage.SYSTEM -> return base
            AppLanguage.AR -> Locale("ar")
            // مجلد الموارد values-en-rUS يتطلب منطقة US ليُطابَق
            AppLanguage.EN -> Locale("en", "US")
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
