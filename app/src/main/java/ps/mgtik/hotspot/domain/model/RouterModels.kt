package ps.mgtik.hotspot.domain.model

/**
 * ActiveSession - جلسة نشطة كما تُخزَّن في مجموعة active_sessions (CloudActiveSessionDataSource).
 */
data class ActiveSession(
    val mikrotikId: String = "",
    val user: String = "",
    val address: String = "",
    val macAddress: String = "",
    val uptime: String = "",
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L
)

/**
 * RouterInterface - واجهة شبكة على الراوتر (RouterRepositoryImpl.getInterfaces).
 */
data class RouterInterface(
    val name: String = "",
    val type: String = "",
    val isRunning: Boolean = false,
    val disabled: Boolean = false,
    val macAddress: String = "",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
)
