package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.LiveConfirmation
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.LiveSnapshot

/** قواعد نقية (بلا Android) للجلسات الحية: تحليل مدة RouterOS، والبحث والترتيب والتصفية في القائمة. */
object LiveSessionRules {

    enum class SortBy { UPTIME, TRAFFIC, NAME }

    /**
     * يحوّل مدة RouterOS (مثل "1w2d3h4m5s" أو "00:10:05" أو "5s") إلى ثوانٍ؛ null إن لم تُقرأ (لا نفترض صفراً).
     */
    fun parseUptimeSeconds(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.contains(':')) { // hh:mm:ss أو mm:ss
            val parts = s.split(':').map { it.toLongOrNull() ?: return null }
            return when (parts.size) {
                3 -> parts[0] * 3_600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> null
            }
        }
        val unit = mapOf('w' to 604_800L, 'd' to 86_400L, 'h' to 3_600L, 'm' to 60L, 's' to 1L)
        var total = 0L
        var num = StringBuilder()
        var consumed = false
        for (c in s) {
            when {
                c.isDigit() -> num.append(c)
                unit.containsKey(c) && num.isNotEmpty() -> { total += num.toString().toLong() * unit.getValue(c); num = StringBuilder(); consumed = true }
                else -> return null
            }
        }
        return if (num.isEmpty() && consumed) total else null
    }

    /** بحث بالاسم أو IP أو MAC (احتواء، بلا حساسية لحالة الأحرف). النص الفارغ = بلا بحث. */
    fun matchesQuery(s: LiveSession, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return s.username.contains(q, true) || s.address.contains(q, true) || s.macAddress.contains(q, true)
    }

    /** profile null = الكل. الباقة المجهولة لا تطابق أي باقة محددة. */
    fun apply(
        sessions: List<LiveSession>,
        query: String = "",
        profile: String? = null,
        sortBy: SortBy = SortBy.UPTIME
    ): List<LiveSession> {
        val filtered = sessions.filter {
            matchesQuery(it, query) && (profile == null || it.profile == profile)
        }
        return when (sortBy) {
            SortBy.UPTIME -> filtered.sortedWith(compareByDescending<LiveSession> { it.uptimeSeconds ?: -1L }.thenBy { it.username.lowercase() })
            SortBy.TRAFFIC -> filtered.sortedWith(compareByDescending<LiveSession> { it.totalBytes }.thenBy { it.username.lowercase() })
            SortBy.NAME -> filtered.sortedBy { it.username.lowercase() }
        }
    }

    /** صف NAS مبسّط للمطابقة (من /ip/hotspot/active): المستخدم والـ MAC والعنوان. */
    data class NasRow(val user: String, val mac: String, val address: String)

    /**
     * يطابق جلسات User Manager الحية مع ما يعرفه NAS (تأكيد فقط، لا مصدر): المستخدم أولاً (دون حساسية للأحرف) ثم MAC إن توفر
     * على الطرفين، وإلا العنوان إن توفر، وإلا يكفي المستخدم. [nas] = null يعني تعذّر قراءة NAS: لا حكم بالعلوق (UNVERIFIED).
     * كل صف NAS يؤكد جلسة واحدة فقط (لا تأكيد مزدوج لجلستين بالمستخدم نفسه).
     */
    fun classify(umLive: List<LiveSession>, nas: List<NasRow>?): LiveSnapshot {
        if (nas == null) return LiveSnapshot(umLive.map { it.copy(confirmation = LiveConfirmation.UNVERIFIED) })
        val free = nas.toMutableList()
        val ok = ArrayList<LiveSession>()
        val stale = ArrayList<LiveSession>()
        for (s in umLive) {
            val idx = free.indexOfFirst { r ->
                r.user.equals(s.username, ignoreCase = true) && when {
                    s.macAddress.isNotBlank() && r.mac.isNotBlank() -> r.mac.equals(s.macAddress, ignoreCase = true)
                    s.address.isNotBlank() && r.address.isNotBlank() -> r.address == s.address
                    else -> true
                }
            }
            if (idx >= 0) { free.removeAt(idx); ok += s.copy(confirmation = LiveConfirmation.CONFIRMED) }
            else stale += s.copy(confirmation = LiveConfirmation.UNVERIFIED)
        }
        return LiveSnapshot(ok, stale)
    }
}
