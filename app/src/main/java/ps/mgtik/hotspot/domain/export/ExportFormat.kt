package ps.mgtik.hotspot.domain.export

import java.net.URLEncoder

/** صيغ تصدير الدفعة المعتمدة: PDF للطباعة وTXT للإرسال/الاستيراد (لا حراري). */
enum class ExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    TXT("txt", "text/plain")
}

/** محتوى رمز QR للكرت: رابط الدخول مع بيانات الكرت إن وُجد رابط، وإلا الكود وحده. */
object VoucherQr {
    fun content(loginUrl: String, username: String, password: String): String {
        val base = loginUrl.trim()
        if (base.isEmpty()) return username
        val sep = if (base.contains('?')) '&' else '?'
        return base + sep + "username=" + enc(username) + "&password=" + enc(password)
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
