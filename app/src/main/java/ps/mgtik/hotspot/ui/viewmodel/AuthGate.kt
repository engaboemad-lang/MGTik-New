package ps.mgtik.hotspot.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import ps.mgtik.hotspot.domain.model.UserSession
import ps.mgtik.hotspot.domain.usecase.GetSessionStateUseCase

/**
 * تشغيل مراقبة سحابية فقط أثناء جلسة مصادَق عليها.
 *
 * المشكلة التي يحلها: كل الـ ViewModels تُنشأ عند إقلاع Activity وكانت تبدأ مراقبات Firestore في init قبل
 * تسجيل الدخول؛ فيرفضها الخادم (صلاحيات) فيموت التدفق نهائياً (`.catch {}` لا يعيد الاشتراك) وتبقى القوائم
 * فارغة بعد الدخول. هنا يُبنى التدفق من جديد مع كل دخول، ويُلغى عند الخروج، ويُنفَّذ [onSignedOut] لتفريغ
 * بيانات المستخدم السابق من الحالة (لا تظهر لمستخدم تالٍ).
 */
fun <T> GetSessionStateUseCase.gate(onSignedOut: () -> Unit = {}, source: () -> Flow<T>): Flow<T> =
    invoke().whenAuthenticated(onSignedOut, source)

/** منطق البوابة على تدفق جلسات خام (قابل للاختبار دون واجهة مستودع المصادقة). */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<UserSession>.whenAuthenticated(onSignedOut: () -> Unit = {}, source: () -> Flow<T>): Flow<T> =
    this
        .map { it.isAuthenticated }
        .distinctUntilChanged()
        .flatMapLatest { authenticated ->
            if (authenticated) {
                // خطأ مؤقت (شبكة/صلاحيات/فهرس) كان يُنهي المراقبة نهائياً؛ نعيد الاشتراك بمهلة متزايدة (حتى 30 ثانية)
                source().retryWhen { cause, attempt ->
                    if (cause is CancellationException) false
                    else { delay(minOf(2_000L * (attempt + 1), 30_000L)); true }
                }
            } else {
                onSignedOut()
                emptyFlow()
            }
        }
