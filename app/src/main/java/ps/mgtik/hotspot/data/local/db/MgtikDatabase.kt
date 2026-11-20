package ps.mgtik.hotspot.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ps.mgtik.hotspot.data.local.dao.*
import ps.mgtik.hotspot.data.local.entity.*

/**
 * MgtikDatabase - قاعدة البيانات المحلية (Room): كاش وضع عدم الاتصال + مصدر حقيقة الساغا والطابور.
 *
 * سياسة الإصدارات (المشروع في طور التطوير بلا بيانات تشغيلية ولا أجهزة قديمة):
 *  - الإصدار 13 أضاف own_settings إلى cached_profiles (الإعدادات التشغيلية الخاصة بالبروفايل مقابل الموروثة من تصنيفه). الكاش يُعاد بناؤه من السحابة.
 *  - الإصدار 12 رفع إصدار فقط (بلا تغيير مخطط) لإعادة بناء الكاش المحلي من الصفر بعد مسح البيانات القديمة.
 *  - الإصدار 11 أضاف system_class إلى local_users (التصنيف النظامي NORMAL/SUBSCRIPTION؛ القيمة الافتراضية NORMAL). الكاش يُعاد بناؤه من السحابة.
 *  - الإصدار 10 سجّل جدولي discovery_records (كاش سجلات الاكتشاف) وarchived_users (نسخة الأرشيف) وأضاف archive_type إلى الأخير.
 *  - الإصدار 9 أضاف أعمدة خيارات التوليد إلى generation_runs (mode / password_type / password_length / comment / server).
 *  - الإصدار 8 (أُضيفت أعمدة قيود الاستخدام: cached_profiles / local_users / generation_runs) فوق الإصدار 7 = إعادة بدء نظيفة. أي قاعدة بإصدار أقدم تُعاد إنشاؤها ([fallbackToDestructiveMigration]).
 *    حُذفت سلسلة الترحيل القديمة (5→6) عمداً لأنه لا قاعدة بإصدار 5 يجب الحفاظ عليها.
 *  - تغيير مخطط أي جدول يتطلب رفع الإصدار. المخطط يُصدَّر إلى app/schemas (room.schemaLocation) لمراجعته في Git.
 *  - **قبل أول إصدار يصل لمستخدمين حقيقيين**: أزل fallbackToDestructiveMigration() واكتب Migration صريحة من 7 فصاعداً
 *    (جداول generation_runs وstaged_vouchers وoffline_commands تحمل بيانات غير قابلة للاسترجاع من السحابة).
 */
@Database(
    entities = [
        ProfileEntity::class,
        VoucherBatchEntity::class,
        InvoiceEntity::class,
        SubscriptionEntity::class,
        LocalUserEntity::class,
        ExecutionEntity::class,
        ExecutionStepEntity::class,
        OfflineCommandEntity::class,
        TemplateEntity::class,
        AuditLogEntity::class,
        GenerationRunEntity::class,
        StagedVoucherEntity::class,
        DiscoveryEntity::class,
        ArchivedUserEntity::class
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(DataConverters::class)
abstract class MgtikDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun voucherBatchDao(): VoucherBatchDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun localUserDao(): LocalUserDao
    abstract fun executionDao(): ExecutionDao
    abstract fun offlineCommandDao(): OfflineCommandDao
    abstract fun templateDao(): TemplateDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun generationDao(): GenerationDao
    abstract fun discoveryDao(): DiscoveryDao
    abstract fun archivedUserDao(): ArchivedUserDao

    companion object {
        const val DATABASE_NAME = "mgtik_local_cache.db"

        @Volatile
        private var INSTANCE: MgtikDatabase? = null

        fun getInstance(context: Context): MgtikDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MgtikDatabase::class.java,
                    DATABASE_NAME
                )
                    // طور التطوير فقط (انظر سياسة الإصدارات أعلاه)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
