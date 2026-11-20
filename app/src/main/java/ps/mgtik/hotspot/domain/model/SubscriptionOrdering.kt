package ps.mgtik.hotspot.domain.model

/**
 * ترتيب الاشتراكات من الأحدث إلى الأقدم حسب تاريخ الإنشاء الفعلي ([Subscription.createdAt]، ISO مرتَّب نصياً).
 * ما بلا تاريخ إنشاء يُوضع في النهاية، وعند التساوي يُحسم بالمعرّف ليكون الترتيب ثابتاً بين التحديثات.
 */
fun List<Subscription>.newestFirst(): List<Subscription> =
    sortedWith(
        compareBy<Subscription> { it.createdAt.isBlank() }
            .thenByDescending { it.createdAt }
            .thenBy { it.id }
    )
