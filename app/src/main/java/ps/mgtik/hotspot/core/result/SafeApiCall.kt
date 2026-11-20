package ps.mgtik.hotspot.core.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مغلف استدعاءات الـ API والعمليات السحابية
 * يمنع تسرب Exceptions النقل والشبكة إلى طبقة Presentation
 */
suspend inline fun <T> safeApiCall(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    crossinline apiCall: suspend () -> T
): AppResult<T> {
    return withContext(dispatcher) {
        try {
            AppResult.Success(apiCall())
        } catch (e: CancellationException) {
            throw e // إلغاء الكوروتين (مثل إيقاف Worker) لا يُحوَّل إلى خطأ عمل
        } catch (e: Exception) {
            AppResult.Error(ApiError.mapThrowable(e))
        }
    }
}
