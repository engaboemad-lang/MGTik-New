package ps.mgtik.hotspot.core.result

/**
 * نتيجة العمليات القياسية المعتمدة في تطبيق Android (Foundation Architecture)
 * Presentation لا يرى Retrofit أو OkHttp أو Raw Exceptions، بل يتعامل حصرياً مع AppResult
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>
    data class Error(val error: ApiError) : AppResult<Nothing>
    data object Loading : AppResult<Nothing>

    fun isSuccess(): Boolean = this is Success
    fun isError(): Boolean = this is Error
    fun isLoading(): Boolean = this is Loading

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> {
    return when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Error -> AppResult.Error(error)
        is AppResult.Loading -> AppResult.Loading
    }
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (ApiError) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(error)
    return this
}
