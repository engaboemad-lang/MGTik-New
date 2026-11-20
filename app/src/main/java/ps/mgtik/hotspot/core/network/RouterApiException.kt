package ps.mgtik.hotspot.core.network

import ps.mgtik.hotspot.core.result.ApiError

/**
 * RouterApiException - يحمل ApiError مصنَّفاً من داخل كتلة الطلب ليصل كما هو إلى AppResult.Error
 * (مثل رفض المصادقة 401) دون أن يُعاد تصنيفه كخطأ غير متوقع.
 */
class RouterApiException(val error: ApiError) : Exception(error.message)
