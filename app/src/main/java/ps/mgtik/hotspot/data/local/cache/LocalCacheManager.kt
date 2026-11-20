package ps.mgtik.hotspot.data.local.cache

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ps.mgtik.hotspot.data.local.db.MgtikDatabase
import ps.mgtik.hotspot.data.local.entity.*
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalCacheManager - واجهة موحدة للوصول إلى بيانات الكاش المحلي (§10).
 *
 * القراءة صارمة: صف كاش بقيمة enum ناقصة/مجهولة يُستبعد من القائمة ويُسجَّل SYNC_ERROR (مرة لكل صف)،
 * ولا يُحوَّل إلى حالة "صحيحة" ولا يُسقط بقية القائمة.
 */
@Singleton
class LocalCacheManager @Inject constructor(
    private val db: MgtikDatabase,
    private val reporter: SyncErrorReporter
) {

    private inline fun <E, D : Any> List<E>.mapValid(
        table: String,
        id: (E) -> String,
        convert: (E) -> D?
    ): List<D> = mapNotNull { row ->
        convert(row) ?: run {
            reporter.rejected(table, id(row), "unreadable cached row (missing/unknown enum value)")
            null
        }
    }

    // Profiles Cache
    fun getCachedProfilesFlow(): Flow<List<Profile>> {
        return db.profileDao().getAllProfilesFlow().map { entities ->
            entities.mapValid("cached_profiles", { it.id }) { it.toDomainOrNull() }
        }
    }

    suspend fun saveProfiles(profiles: List<Profile>) {
        db.profileDao().insertProfiles(profiles.map { ProfileEntity.fromDomain(it) })
    }

    /**
     * يحفظ لقطة سحابية ويقلّم الكاش ضمن نطاق ما جُلب فعلياً فقط. لا يُحذف شيء إلا إذا تحققت كلها:
     *  1. [serverConfirmed]: اللقطة مؤكَّدة من الخادم (ليست من كاش الـ SDK، وقد تكون فارغة/ناقصة).
     *  2. الاستعلام بلا فلتر فئة: فلتر الفئة لقطة جزئية، وباقة نُقلت إلى فئة أخرى ما زالت موجودة.
     *  3. [presentIds] غير فارغة: القائمة الفارغة ليست إشارة لمسح الكاش (حذف الباقة الأخيرة تتولاه removeProfile).
     * والنطاق: حالات الاستعلام نفسه. مع includeArchived=false لا تُحذف المؤرشفة أبداً.
     * و[presentIds] تشمل المستندات المرفوضة (غير المقروءة) كي لا تُحذف نسخها المحلية.
     */
    suspend fun syncProfiles(
        profiles: List<Profile>,
        presentIds: Set<String>,
        includeArchived: Boolean,
        categoryId: String?,
        serverConfirmed: Boolean
    ) {
        saveProfiles(profiles)
        if (!serverConfirmed || categoryId != null || presentIds.isEmpty()) return

        val inScope = if (includeArchived) {
            setOf(ProfileStatus.ACTIVE, ProfileStatus.ARCHIVED, ProfileStatus.MISSING, ProfileStatus.DISABLED)
        } else {
            setOf(ProfileStatus.ACTIVE, ProfileStatus.MISSING, ProfileStatus.DISABLED)
        }
        db.withTransaction {
            val stale = db.profileDao().getAllProfiles()
                .filter { row ->
                    // صف بحالة غير مقروءة لا يُحذف (لا نعرف إن كان ضمن النطاق)
                    val st = row.status.toEnumOrNull<ProfileStatus>()
                    st != null && st in inScope && row.id !in presentIds
                }
                .map { it.id }
            stale.chunked(500).forEach { db.profileDao().deleteByIds(it) }
        }
    }

    suspend fun removeProfile(profileId: String) {
        db.profileDao().deleteById(profileId)
    }

    // Voucher Batches Cache (مكتوب حالياً ولا يُقرأ؛ القرار بشأن دورة حياته مؤجَّل)
    fun getCachedBatchesFlow(): Flow<List<VoucherBatch>> {
        return db.voucherBatchDao().getAllBatchesFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun saveBatches(batches: List<VoucherBatch>) {
        db.voucherBatchDao().insertBatches(batches.map { VoucherBatchEntity.fromDomain(it) })
    }

    // Managed Users Cache
    fun getManagedUsersFlow(ownerId: String? = null, status: UserRecordStatus? = null): Flow<List<LocalUserRecord>> {
        // الحالة مخزَّنة باسم الـ enum (LocalUserEntity.fromDomain)
        return db.localUserDao().getUsersFilteredFlow(ownerId, status?.name)
            .map { entities -> entities.mapValid("local_users", { it.recordUuid }) { it.toDomainOrNull() } }
    }

    suspend fun removeManagedUser(uuid: String) {
        db.localUserDao().deleteByUuid(uuid)
    }

    suspend fun getUserByUuid(uuid: String): LocalUserRecord? {
        return db.localUserDao().getUserByUuid(uuid)?.toDomainOrNull()
    }

    suspend fun saveManagedUsers(users: List<LocalUserRecord>) {
        db.localUserDao().insertUsers(users.map { LocalUserEntity.fromDomain(it) })
    }

    /**
     * يحفظ لقطة سحابية ويقلّم الصفوف التي اختفت من السحابة (أُرشف/حُذف من جهاز آخر)؛ كان الكاش يحتفظ بها للأبد
     * فتبقى ظاهرة في S16. لا يُحذف شيء إلا إذا تحققت كلها:
     *  1. [serverConfirmed]: لقطة الخادم لا كاش الـ SDK (قد تكون فارغة دون اتصال).
     *  2. [fullScope]: الاستعلام بلا فلتر مالك/حالة؛ اللقطة المفلترة جزئية.
     *  3. [presentIds] غير فارغة: القائمة الفارغة ليست إشارة لمسح الكاش.
     */
    suspend fun syncManagedUsers(
        users: List<LocalUserRecord>,
        presentIds: Set<String>,
        fullScope: Boolean,
        serverConfirmed: Boolean
    ) {
        db.withTransaction {
            saveManagedUsers(users)
            if (!serverConfirmed || !fullScope || presentIds.isEmpty()) return@withTransaction
            val stale = db.localUserDao().getAllUuids().filter { it !in presentIds }
            stale.chunked(500).forEach { db.localUserDao().deleteByUuids(it) }
        }
    }

    // Discovery Cache (سجلات الاكتشاف المفتوحة فقط)
    fun getOpenDiscoveryFlow(type: DiscoveryType): Flow<List<DiscoveryRecord>> =
        db.discoveryDao().getOpenDiscoveriesByTypeFlow(type.name.lowercase())
            .map { rows -> rows.mapValid("discovery_records", { it.discoveryId }) { it.toDomainOrNull() } }

    /**
     * يحفظ لقطة سحابية لسجلات الاكتشاف المفتوحة. اللقطة المؤكَّدة من الخادم تقلّم ما لم يعد مفتوحاً (حُلّ/اختفى)؛
     * لقطة كاش الـ SDK قد تكون ناقصة فلا تقلّم شيئاً (نفس شروط syncManagedUsers).
     */
    suspend fun syncDiscovery(records: List<DiscoveryRecord>, serverConfirmed: Boolean) {
        db.withTransaction {
            db.discoveryDao().insertDiscoveries(records.map { DiscoveryEntity.fromDomain(it) })
            if (!serverConfirmed) return@withTransaction
            val present = records.mapTo(HashSet()) { it.discoveryId }
            val stale = db.discoveryDao().getAllIds().filter { it !in present }
            stale.chunked(500).forEach { db.discoveryDao().deleteByIds(it) }
        }
    }

    /** إغلاق محلي فوري لسجل اكتشاف (أُرشف/حُذف/تبنّي) دون انتظار لقطة السحابة. */
    suspend fun removeDiscovery(discoveryId: String) = db.discoveryDao().deleteById(discoveryId)

    // Archived Users Cache
    suspend fun saveArchivedUsers(items: List<ArchivedUserSnapshot>) {
        db.archivedUserDao().insertAll(items.map { ArchivedUserEntity.fromDomain(it) })
    }

    suspend fun getLatestArchivedUsers(limit: Int): List<ArchivedUserSnapshot> =
        db.archivedUserDao().getLatest(limit).map { it.toDomain() }

    // Invoices Cache
    fun getCachedInvoicesFlow(ownerId: String? = null, status: InvoiceStatus? = null): Flow<List<Invoice>> {
        // الحالة مخزَّنة باسم الـ enum (InvoiceEntity.fromDomain)
        return db.invoiceDao().getFilteredInvoicesFlow(ownerId, status?.name).map { entities ->
            entities.mapValid("cached_invoices", { it.id }) { it.toDomainOrNull() }
        }
    }

    suspend fun saveInvoices(invoices: List<Invoice>) {
        db.invoiceDao().insertInvoices(invoices.map { InvoiceEntity.fromDomain(it) })
    }

    // Subscriptions Cache (مكتوب حالياً ولا يُقرأ؛ القرار بشأن دورة حياته مؤجَّل)
    fun getCachedSubscriptionsFlow(): Flow<List<Subscription>> {
        return db.subscriptionDao().getAllSubscriptionsFlow().map { entities ->
            entities.mapValid("cached_subscriptions", { it.id }) { it.toDomainOrNull() }
        }
    }

    suspend fun saveSubscriptions(subs: List<Subscription>) {
        db.subscriptionDao().insertSubscriptions(subs.map { SubscriptionEntity.fromDomain(it) })
    }

    // Execution Cache
    fun observeExecution(executionId: String): Flow<Execution?> =
        db.executionDao().observeExecutionWithSteps(executionId).map { row ->
            row?.execution?.toDomainOrNull(row.steps.sortedBy { it.localStepId }.map { it.toDomain() })
                ?: run {
                    if (row != null) reporter.rejected("executions", executionId, "unreadable cached row (unknown flow type)")
                    null
                }
        }

    suspend fun saveExecution(execution: Execution) {
        val entity = ExecutionEntity.fromDomain(execution)
        val steps = execution.steps.map { ExecutionStepEntity.fromDomain(execution.executionId, it) }
        db.executionDao().insertExecutionWithSteps(entity, steps)
    }

    /**
     * يحذف صفوف الكاش بتنسيق تاريخ قديم (Date.toString أو ISO متغير العرض) من الجداول التي تُرتَّب نصياً
     * بـ created_at. هذه كاشات يعاد ملؤها من السحابة بالتنسيق الجديد، فلا ترحيل بيانات (Migration)،
     * والصفوف بالتنسيق الجديد لا تُمس. آمنة للاستدعاء في أي وقت وأكثر من مرة.
     * @return عدد الصفوف المحذوفة
     */
    suspend fun purgeLegacyDateRows(): Int =
        db.withTransaction { db.invoiceDao().deleteLegacyDateRows() + db.localUserDao().deleteLegacyDateRows() }

    /** يحذف صفوف الاشتراكات التجريبية القديمة (SUB-01..03) إن وُجدت في الكاش. آمنة للتكرار. */
    suspend fun purgeDemoSubscriptions(): Int = db.subscriptionDao().deleteDemoRows()

    /**
     * كاشات بيانات المستخدم/الحساب فقط، المخصصة لتنظيف ما بعد تسجيل الخروج (غير مربوطة بعد، انظر
     * docs/LOCAL_STORAGE_DECISIONS.md §5). لا تمس عمداً:
     *  - audit_logs: سجل محلي فقط، لا نسخة له في السحابة؛
     *  - templates: بيانات محلية تُزامَن عبر SyncEngine؛
     *  - offline_commands: كتابات لم تُرسَل بعد، ومسحها فقد صامت لعمليات المستخدم؛
     *  - generation_runs وstaged_vouchers: ساغات قد تكون جارية، وتحمل كلمات سر الكروت للتصدير.
     */
    suspend fun clearUserCaches() {
        db.withTransaction {
            db.profileDao().clearAll()
            db.voucherBatchDao().clearAllBatches()
            db.invoiceDao().clearAll()
            db.subscriptionDao().clearAll()
            db.localUserDao().clearAll()
            db.discoveryDao().clearAll()
            db.archivedUserDao().clearAll()
            db.executionDao().clearAll()
        }
    }

    /**
     * تحذير: غير مناسبة لتسجيل الخروج. تمسح audit_logs (سجل محلي فقط) وتغفل جداول أخرى.
     * الاستدعاء الوحيد المقصود اليوم هو لا شيء؛ استخدم [clearUserCaches] للخروج.
     */
    suspend fun clearAll() {
        db.profileDao().clearAll()
        db.voucherBatchDao().clearAllBatches()
        db.invoiceDao().clearAll()
        db.subscriptionDao().clearAll()
        db.localUserDao().clearAll()
        db.discoveryDao().clearAll()
        db.archivedUserDao().clearAll()
        db.executionDao().clearAll()
        db.auditLogDao().clearAll()
    }
}
