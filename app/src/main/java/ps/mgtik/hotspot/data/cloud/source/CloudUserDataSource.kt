package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.core.result.RuleViolation
import ps.mgtik.hotspot.domain.usecase.SingleUserRules
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.usecase.UserEditRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudUserDataSource - تنفيذ العمليات السحابية للمستخدمين.
 * تم التحديث: إضافة Safe Mapping للـ Enums لمنع الانهيار عند قراءة بيانات خاطئة (§4.12).
 */
@Singleton
class CloudUserDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val reporter: SyncErrorReporter
) {
    private val usersCollection = firestore.collection("local_users")
    private val discoveryCollection = firestore.collection("discovery_records")
    private val archiveCollection = firestore.collection("archived_users")
    private val ownersCollection = firestore.collection("owners")

    companion object {
        /** وسم تعليق الراوتر للمستخدم الفردي (MGTik:USER): ليس دفعة ولا سجل دفعة، فقط وسم يميّز المنشأ. */
        const val SINGLE_USER_TAG = "USER"
    }

    /**
     * لقطة مستخدمين مدارين. [presentIds] تشمل المستندات المرفوضة (غير المقروءة) و[fromCache] تميّز لقطة كاش الـ SDK
     * (قد تكون فارغة/ناقصة) عن لقطة الخادم المؤكَّدة؛ بهما فقط يجوز تقليم الكاش المحلي (انظر LocalCacheManager.syncManagedUsers).
     */
    data class UsersSnapshot(
        val users: List<LocalUserRecord>,
        val presentIds: Set<String>,
        val fromCache: Boolean
    )

    fun getManagedUsersSnapshots(ownerId: String?, status: UserRecordStatus?): Flow<UsersSnapshot> {
        var query: Query = usersCollection
        ownerId?.let { query = query.whereEqualTo("owner_id", it) }
        status?.let { query = query.whereIn("status", listOf(it.name.lowercase(), it.name)) }

        // MetadataChanges.INCLUDE: وإلا لن تصل لقطة الخادم حين تطابق الكاش فلا نعرف أنها مؤكَّدة
        return query.snapshots(MetadataChanges.INCLUDE).map { snapshot ->
            UsersSnapshot(
                users = snapshot.documents.mapNotNull { doc -> mapToLocalUserRecord(doc) },
                presentIds = snapshot.documents.mapTo(HashSet()) { it.id },
                fromCache = snapshot.metadata.isFromCache
            )
        }
    }

    suspend fun getManagedUsersList(): List<LocalUserRecord> {
        val snapshot = usersCollection.get().await()
        return snapshot.documents.mapNotNull { doc -> mapToLocalUserRecord(doc) }
    }

    /**
     * null (مع SYNC_ERROR) إن كانت status أو origin أو sync_status ناقصة/مجهولة: لا تحويل إلى
     * ACTIVE/GENERATED/SYNCED، فمستخدم بحالة تالفة لا يظهر كمستخدم نشط ولا يُسقط بقية القائمة.
     */
    private fun mapToLocalUserRecord(doc: com.google.firebase.firestore.DocumentSnapshot): LocalUserRecord? {
        val statusRaw = doc.getString("status")
        val originRaw = doc.getString("origin")
        val syncRaw = doc.getString("sync_status")
        val status = statusRaw.toEnumOrNull<UserRecordStatus>()
        val origin = originRaw.toEnumOrNull<UserOrigin>()
        val syncStatus = syncRaw.toEnumOrNull<UserSyncStatus>()
        // غياب الحقل = NORMAL (سجلات سبقت التصنيف: كلها توليد/تبنٍّ)؛ قيمة موجودة غير معروفة = مرفوضة لا تُخمَّن
        val classRaw = doc.getString("system_class")
        val systemClass = if (classRaw == null) SystemClass.NORMAL else SystemClass.fromWire(classRaw)
        if (status == null || origin == null || syncStatus == null || systemClass == null) {
            val bad = buildList {
                if (status == null) add("status='${statusRaw.orEmpty()}'")
                if (origin == null) add("origin='${originRaw.orEmpty()}'")
                if (syncStatus == null) add("sync_status='${syncRaw.orEmpty()}'")
                if (systemClass == null) add("system_class='${classRaw.orEmpty()}'")
            }.joinToString()
            reporter.rejected("local_users", doc.id, "missing or unknown $bad")
            return null
        }

        return LocalUserRecord(
            recordUuid = doc.id,
            routerUsername = doc.getString("router_username") ?: "",
            ownerId = doc.getString("owner_id") ?: "",
            status = status,
            batchId = doc.getString("batch_id"),
            origin = origin,
            syncStatus = syncStatus,
            lastAction = doc.getString("last_action"),
            lastActionResult = doc.getString("last_action_result"),
            lastActionAt = doc.getTimestamp("last_action_at")?.toDate()?.toSortableIso(),
            createdAt = doc.getTimestamp("created_at")?.toDate()?.toSortableIso() ?: "",
            updatedAt = doc.getTimestamp("updated_at")?.toDate()?.toSortableIso() ?: "",
            limitUptimeSeconds = doc.getLong("limit_uptime_seconds"),
            limitTransferBytes = doc.getLong("limit_transfer_bytes"),
            systemClass = systemClass
        )
    }

    /**
     * حالة المستخدم المدار بعد تفعيل/تعطيل فعلي على الراوتر: تُحدَّث status (التي تقرؤها الواجهة لعرض زر التفعيل/التعطيل)
     * مع sync_status وأثر العملية؛ تحديث sync_status وحده كان يترك الحالة كما هي.
     */
    suspend fun updateUserStatus(
        recordUuid: String,
        status: UserRecordStatus,
        syncStatus: UserSyncStatus,
        action: String
    ): AppResult<Unit> = safeApiCall {
        usersCollection.document(recordUuid).update(
            mapOf(
                "status" to status.name.lowercase(),
                "sync_status" to syncStatus.name.lowercase(),
                "last_action" to action,
                "last_action_at" to FieldValue.serverTimestamp(),
                "updated_at" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun createLocalRecord(record: LocalUserRecord): AppResult<Unit> = safeApiCall {
        val data = hashMapOf(
            "router_username" to record.routerUsername,
            "owner_id" to record.ownerId,
            "status" to record.status.name.lowercase(),
            "origin" to record.origin.name.lowercase(),
            "sync_status" to record.syncStatus.name.lowercase(),
            "limit_uptime_seconds" to record.limitUptimeSeconds,
            "limit_transfer_bytes" to record.limitTransferBytes,
            "system_class" to record.systemClass.wire,
            "created_at" to FieldValue.serverTimestamp(),
            "updated_at" to FieldValue.serverTimestamp()
        )
        usersCollection.document(record.recordUuid).set(data).await()
    }

    // ───────────── إنشاء مستخدم عادي فردي (S16) ─────────────

    /** المالكون كما هم في السحابة، لحسم مرجع «admin» (وثيقة بحالة تالفة تُهمَل ويُبلَّغ عنها كغيرها من القراءات). */
    suspend fun readOwnersForResolution(): AppResult<List<OwnerAccount>> = safeApiCall {
        ownersCollection.get().await().documents.mapNotNull { doc ->
            val rawStatus = doc.getString("status")
            val status = rawStatus.toEnumOrNull<OwnerStatus>()
            if (status == null) {
                reporter.rejected("owners", doc.id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
                null
            } else OwnerAccount(ownerId = doc.id, name = doc.getString("name").orEmpty(), status = status)
        }
    }

    /** اسم الدخول محجوز سحابياً: سجل مُدار (أي نوع) أو وثيقة أرشيف (الأرشفة لا تحذف الحساب من الراوتر). */
    suspend fun isUsernameReserved(routerUsername: String): AppResult<Boolean> = safeApiCall {
        usersCollection.whereEqualTo("router_username", routerUsername).limit(1).get().await().documents.isNotEmpty() ||
            archiveCollection.whereEqualTo("routerUsername", routerUsername).limit(1).get().await().documents.isNotEmpty()
    }

    /**
     * ينشئ سجل المستخدم العادي (sync_status=pending) بمعرّف = مفتاح العملية، ومعاملة واحدة تقرأ الباقة وفئتها:
     * الباقة يجب أن تتبع فئة مصنّفة NORMAL صراحةً (الحكم هنا لا في الواجهة). إعادة المحاولة بالمفتاح نفسه لا تكرّر السجل.
     * تُحفظ بيانات إعادة الإنشاء على الراوتر (كلمة السر/البروفايل/السيرفر) في وثيقة السجل نفسها لأن لا دفعة تحملها؛
     * مخرج المعاملة true = سجل جديد، false = كان موجوداً من محاولة سابقة.
     */
    suspend fun createNormalRecord(record: LocalUserRecord, source: VoucherSource, ownerName: String, settings: ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings = ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings()): AppResult<Boolean> = safeApiCall {
        val ref = usersCollection.document(record.recordUuid)
        firestore.runTransaction { tx ->
            if (tx.get(ref).exists()) return@runTransaction false
            val profileRef = firestore.collection("profiles").document(source.profileId)
            val profileSnap = tx.get(profileRef)
            if (!profileSnap.exists()) throw RuleViolation(SingleUserRules.ERR_PROFILE_REQUIRED, "Profile not found")
            val catId = profileSnap.getString("category_id")
            val catSnap = catId?.let { tx.get(firestore.collection("profile_categories").document(it)) }
            SystemClassRules.checkProfileFor(
                SystemClass.NORMAL,
                hasCategoryId = catId != null,
                category = catSnap?.takeIf { it.exists() }?.let {
                    ProfileCategory(id = it.id, systemClass = SystemClass.fromWire(it.getString("system_class")))
                }
            )?.let { throw RuleViolation(it, "Profile category is not a NORMAL category") }
            if (profileSnap.getBoolean("in_use") != true) tx.update(profileRef, "in_use", true)
            tx.set(
                ref, hashMapOf(
                    "router_username" to record.routerUsername,
                    "owner_id" to record.ownerId,
                    "owner_name" to ownerName,
                    "status" to record.status.name.lowercase(),
                    "origin" to record.origin.name.lowercase(),
                    "sync_status" to record.syncStatus.name.lowercase(),
                    "system_class" to record.systemClass.wire,
                    "limit_uptime_seconds" to record.limitUptimeSeconds,
                    "limit_transfer_bytes" to record.limitTransferBytes,
                    "download_kbps" to settings.downloadKbps,
                    "upload_kbps" to settings.uploadKbps,
                    "shared_users" to settings.sharedUsers,
                    // مصدر إعادة الإنشاء على الراوتر (لا دفعة): تقرؤه getSingleUserSource فقط، ولا يدخل LocalUserRecord ولا Room
                    "creation_password" to source.password,
                    "creation_profile_id" to source.profileId,
                    "creation_router_profile" to source.profileName,
                    "creation_comment" to source.userComment,
                    "last_action" to "CREATE_USER",
                    "created_at" to FieldValue.serverTimestamp(),
                    "updated_at" to FieldValue.serverTimestamp()
                )
            )
            true
        }.await()
    }

    /** نتيجة تنفيذ الإنشاء على الراوتر: تعليم السجل SYNCED أو PENDING/FAILED مع سبب ظاهر في السجل. */
    suspend fun markCreateResult(recordUuid: String, syncStatus: UserSyncStatus, error: String?): AppResult<Unit> = safeApiCall {
        usersCollection.document(recordUuid).set(
            hashMapOf<String, Any?>(
                "sync_status" to syncStatus.name.lowercase(),
                "last_action" to "CREATE_USER",
                "last_action_result" to (error?.take(160) ?: "ok"),
                "last_action_at" to FieldValue.serverTimestamp(),
                "updated_at" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    /** مصدر إعادة إنشاء مستخدم فردي على الراوتر من وثيقة سجله (انظر [createNormalRecord]). null = لا مصدر. */
    suspend fun getSingleUserSource(recordUuid: String): AppResult<VoucherSource?> = safeApiCall {
        val doc = usersCollection.document(recordUuid).get().await()
        val password = doc.getString("creation_password")
        val profile = doc.getString("creation_router_profile")
        if (!doc.exists() || password.isNullOrBlank() || profile.isNullOrBlank()) null
        else VoucherSource(
            username = doc.getString("router_username").orEmpty(),
            password = password,
            profileName = profile,
            batchNumber = SINGLE_USER_TAG,
            userComment = doc.getString("creation_comment").orEmpty(),
            profileId = doc.getString("creation_profile_id").orEmpty()
        )
    }

    /** لقطة سجلات الاكتشاف المفتوحة (NULL وMISSING معاً). [fromCache] يميّز كاش الـ SDK عن لقطة الخادم المؤكَّدة. */
    data class DiscoverySnapshot(val records: List<DiscoveryRecord>, val fromCache: Boolean)

    fun getOpenDiscoverySnapshots(): Flow<DiscoverySnapshot> =
        discoveryCollection.whereEqualTo("status", "open").snapshots(MetadataChanges.INCLUDE).map { snapshot ->
            DiscoverySnapshot(
                records = snapshot.documents.mapNotNull { doc -> mapDiscovery(doc) },
                fromCache = snapshot.metadata.isFromCache
            )
        }

    private fun mapDiscovery(doc: com.google.firebase.firestore.DocumentSnapshot): DiscoveryRecord? {
        val type = doc.getString("type").toEnumOrNull<DiscoveryType>()
        if (type == null) {
            reporter.rejected("discovery_records", doc.id, "missing or unknown type='${doc.getString("type").orEmpty()}'")
            return null
        }
        return DiscoveryRecord(
            discoveryId = doc.id,
            type = type,
            key = doc.getString("key") ?: "",
            routerUsername = doc.getString("router_username") ?: "",
            status = DiscoveryStatus.OPEN,
            firstDetectedAt = doc.getTimestamp("first_detected_at")?.toDate()?.toSortableIso() ?: "",
            lastDetectedAt = doc.getTimestamp("last_detected_at")?.toDate()?.toSortableIso() ?: ""
        )
    }

    /** معرّف وثيقة الاكتشاف الحتمي (نفس صيغة reconcileDiscovery). */
    fun discoveryIdFor(prefix: String, key: String) = prefix + "_" + key.replace('/', '_')

    /** هل لاسم الدخول هذا سجل مُدار في السحابة؟ (حارس قبل حذف/تعطيل مستخدم «غير معرّف».) */
    suspend fun isManagedUsername(routerUsername: String): AppResult<Boolean> = safeApiCall {
        usersCollection.whereEqualTo("router_username", routerUsername).limit(1).get().await().documents.isNotEmpty()
    }

    /** المستخدم صار مفقوداً من الراوتر (حُذف عنه): يُفتح سجل MISSING له إن لم يكن مفتوحاً. idempotent. */
    suspend fun openMissingDiscovery(recordUuid: String, routerUsername: String): AppResult<Unit> = safeApiCall {
        val ref = discoveryCollection.document(discoveryIdFor("missing", recordUuid))
        val reopen = ref.get().await().getString("status") != "open"
        val data = hashMapOf<String, Any>(
            "type" to "missing", "key" to recordUuid, "router_username" to routerUsername,
            "status" to "open", "last_detected_at" to FieldValue.serverTimestamp()
        )
        if (reopen) {
            data["first_detected_at"] = FieldValue.serverTimestamp()
            data["resolved_at"] = FieldValue.delete()
        }
        ref.set(data, SetOptions.merge()).await()
    }

    /** إغلاق سجل اكتشاف (MISSING لمستخدم عاد إلى الراوتر، أو NULL لمستخدم عُطِّل/حُذف/تُبنّي). لا يُنشئ وثيقة غائبة. */
    suspend fun resolveDiscovery(prefix: String, key: String): AppResult<Unit> = safeApiCall {
        val ref = discoveryCollection.document(discoveryIdFor(prefix, key))
        if (ref.get().await().exists()) {
            ref.set(mapOf("status" to "resolved", "resolved_at" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        }
    }

    /** وثيقة أرشيف واحدة (لحفظ نسخة محلية بعد إتمام الأرشفة). */
    suspend fun getArchivedUser(archiveId: String): AppResult<ArchivedUserSnapshot?> = safeApiCall {
        val doc = archiveCollection.document(archiveId).get().await()
        if (doc.exists()) mapArchivedUser(doc) else null
    }

    /**
     * ما يلزم لإعادة إنشاء حساب مستخدم مولَّد على الراوتر: كلمة السر والباقة من وثيقة الكرت في الدفعة،
     * وتعليق الدفعة من وثيقة الدفعة (حقل server التاريخي في الوثائق القديمة لم يعد يُقرأ). null = لا مصدر (مستخدم متبنّى، أو الكرت غير موجود).
     */
    data class VoucherSource(
        val username: String, val password: String, val profileName: String,
        val batchNumber: String, val userComment: String,
        /** معرّف الباقة في MGTik (يملؤه مسار المستخدم الفردي فقط؛ كروت الدفعات لا تحمله). */
        val profileId: String = ""
    )

    suspend fun getVoucherSource(batchId: String, routerUsername: String): AppResult<VoucherSource?> = safeApiCall {
        val batchRef = firestore.collection("batches").document(batchId)
        val voucher = batchRef.collection("vouchers").whereEqualTo("username", routerUsername).limit(1).get().await()
            .documents.firstOrNull()
        val batch = batchRef.get().await()
        val password = voucher?.getString("password")
        val profile = voucher?.getString("profile_name")
        if (voucher == null || !batch.exists() || password.isNullOrBlank() || profile.isNullOrBlank()) null
        else VoucherSource(
            username = routerUsername,
            password = password,
            profileName = profile,
            batchNumber = batch.getString("batch_number").orEmpty(),
            userComment = batch.getString("comment").orEmpty()
        )
    }

    /**
     * صفحة من الأرشيف مرتَّبة من الأحدث. المؤشّر "archivedAt|docId": الترتيب الثانوي بمعرّف الوثيقة يمنع تخطّي
     * سجلات تشترك في اللحظة نفسها عند startAfter. nextCursor = null حين لا صفحات بعدها.
     */
    suspend fun getArchivedUsersPage(pageSize: Int, cursor: String?): AppResult<ArchivedUsersPage> = safeApiCall {
        var query = archiveCollection
            .orderBy("archivedAt", Query.Direction.DESCENDING)
            .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
        if (cursor != null) {
            val sep = cursor.indexOf('|')
            if (sep > 0) query = query.startAfter(cursor.substring(0, sep), cursor.substring(sep + 1))
        }
        val snapshot = query.get().await()
        val items = snapshot.documents.mapNotNull { doc -> mapArchivedUser(doc) }
        val last = snapshot.documents.lastOrNull()
        val next = if (snapshot.size() < pageSize || last == null) null
        else "${last.getString("archivedAt").orEmpty()}|${last.id}"
        ArchivedUsersPage(items, next)
    }

    /** عدد المؤرشفين بتجميع count() على الخادم (لا تُنزَّل المستندات ولا تُحسب قراءاتها كاملة). */
    suspend fun countArchivedUsers(): AppResult<Long> = safeApiCall {
        archiveCollection.count().get(AggregateSource.SERVER).await().count
    }

    private fun mapArchivedUser(doc: com.google.firebase.firestore.DocumentSnapshot): ArchivedUserSnapshot? {
        val username = doc.getString("routerUsername")
        if (username.isNullOrBlank()) {
            reporter.rejected("archived_users", doc.id, "missing routerUsername")
            return null
        }
        val rawMap = doc.get("administrativeSnapshot") as? Map<*, *> ?: emptyMap<Any, Any>()
        return ArchivedUserSnapshot(
            archiveId = doc.id,
            originalRecordUuid = doc.getString("originalRecordUuid") ?: "",
            routerUsername = username,
            administrativeSnapshot = rawMap.entries.associate { (k, v) -> k.toString() to (v?.toString() ?: "") },
            reason = doc.getString("reason") ?: "",
            archivedBy = doc.getString("archivedBy"),
            archivedAt = doc.getString("archivedAt") ?: "",
            // وثائق أرشفة أقدم بلا الحقل: تُشتق من حالة الراوتر المسجَّلة في اللقطة
            archiveType = doc.getString("archiveType").toEnumOrNull<ArchiveType>()
                ?: if ((rawMap["routerState"] as? String).equals("absent", ignoreCase = true)) ArchiveType.MISSING else ArchiveType.DISABLED
        )
    }

    /**
     * قراءة سجل المستخدم من السحابة (المصدر الحاكم) قبل أي عملية عليه:
     * موجود ⇒ Found، غير موجود وله وثيقة أرشيف ⇒ AlreadyArchived (إعادة تنفيذ بعد نجاح سابق)، وإلا NotFound.
     * دون اتصال تُقرأ من كاش Firestore إن وُجدت الوثيقة فيه، وإلا يُعاد خطأ فيرجع المستدعي إلى الكاش المحلي.
     */
    suspend fun lookupRecord(recordUuid: String): AppResult<UserRecordLookup> = safeApiCall {
        val userDoc = usersCollection.document(recordUuid).get().await()
        if (userDoc.exists()) {
            val record = mapToLocalUserRecord(userDoc)
            if (record != null) UserRecordLookup.Found(record) else UserRecordLookup.NotFound
        } else if (archiveCollection.document(recordUuid).get().await().exists()) {
            UserRecordLookup.AlreadyArchived
        } else {
            UserRecordLookup.NotFound
        }
    }

    /**
     * أرشفة ذرية: لقطة الأرشيف + حذف السجل الأصلي + إغلاق سجل الاكتشاف، في معاملة واحدة.
     * تُستدعى فقط بعد تعطيل المستخدم على الراوتر والتحقق منه ([routerState])؛ لا تُحذف الوثيقة النشطة قبل ذلك.
     * - اللقطة تُبنى من وثيقة السجل في السحابة نفسها (المصدر الحاكم) لا من الكاش المحلي، فتحمل كل حقول السجل
     *   والمالك واسمه ومعرّف المستخدم وبيانات الراوتر ([routerHost] بلا أي أسرار) وحالة الراوتر المتحقَّق منها.
     *   حالة المستخدم في اللقطة disabled لأنه عُطِّل على الراوتر قبل الأرشفة.
     * - معرّف الأرشيف = معرّف السجل الأصلي: إعادة التنفيذ من الطابور بعد نجاح سابق لا تُنتج نسخة ثانية (idempotent).
     * - كل القراءات قبل أي كتابة (شرط Firestore).
     * - وثيقة الاكتشاف قد لا توجد (لم يُكتشف المستخدم مفقوداً قط): تُغلق بـ set(merge) فقط إن وُجدت،
     *   فلا تُنشأ وثيقة اكتشاف شبحية.
     * - الكتابة بخرائط صريحة: تمرير data class بلا مُنشئ افتراضي إلى set يفشل وقت التشغيل في مُحوِّل Firestore.
     */
    suspend fun archiveUser(
        recordUuid: String,
        reason: String,
        archivedAt: String,
        archivedBy: String?,
        routerState: RouterDisableState,
        routerHost: String
    ): AppResult<Unit> {
        val archiveRef = archiveCollection.document(recordUuid)
        val userRef = usersCollection.document(recordUuid)
        val discoveryRef = discoveryCollection.document("missing_$recordUuid")

        val result = safeApiCall {
            firestore.runTransaction { tx ->
                val userDoc = tx.get(userRef)
                val archiveDoc = tx.get(archiveRef)
                val discoveryDoc = tx.get(discoveryRef)
                val ownerId = userDoc.getString("owner_id").orEmpty()
                val ownerDoc = if (userDoc.exists() && ownerId.isNotBlank()) tx.get(ownersCollection.document(ownerId)) else null

                if (!userDoc.exists()) {
                    // سبق أرشفته (إعادة تنفيذ) = نجاح؛ وإلا فالسجل غير موجود فعلاً
                    return@runTransaction archiveDoc.exists()
                }

                val admin = linkedMapOf<String, String>()
                admin["ownerId"] = ownerId
                ownerDoc?.getString("name")?.takeIf { it.isNotBlank() }?.let { admin["ownerName"] = it }
                admin["status"] = UserRecordStatus.DISABLED.name.lowercase()
                userDoc.getString("origin")?.let { admin["origin"] = it }
                userDoc.getString("sync_status")?.let { admin["syncStatus"] = it }
                userDoc.getString("batch_id")?.let { admin["batchId"] = it }
                userDoc.getString("last_action")?.let { admin["lastAction"] = it }
                userDoc.getString("last_action_result")?.let { admin["lastActionResult"] = it }
                userDoc.getTimestamp("last_action_at")?.toDate()?.toSortableIso()?.let { admin["lastActionAt"] = it }
                userDoc.getTimestamp("created_at")?.toDate()?.toSortableIso()?.let { admin["createdAt"] = it }
                userDoc.getTimestamp("updated_at")?.toDate()?.toSortableIso()?.let { admin["updatedAt"] = it }
                userDoc.getLong("limit_uptime_seconds")?.let { admin["limitUptimeSeconds"] = it.toString() }
                userDoc.getLong("limit_transfer_bytes")?.let { admin["limitTransferBytes"] = it.toString() }
                admin["routerState"] = routerState.name.lowercase()
                if (routerHost.isNotBlank()) admin["routerHost"] = routerHost

                val data = hashMapOf<String, Any?>(
                    "archiveId" to recordUuid,
                    "originalRecordUuid" to recordUuid,
                    "routerUsername" to (userDoc.getString("router_username") ?: ""),
                    "reason" to reason,
                    "archivedAt" to archivedAt,
                    "archiveType" to routerState.toArchiveType().name.lowercase(),
                    "administrativeSnapshot" to admin
                )
                archivedBy?.takeIf { it.isNotBlank() }?.let { data["archivedBy"] = it }

                tx.set(archiveRef, data)
                tx.delete(userRef)
                if (discoveryDoc.exists()) {
                    tx.set(
                        discoveryRef,
                        mapOf("status" to "resolved", "resolved_at" to FieldValue.serverTimestamp()),
                        SetOptions.merge()
                    )
                }
                true
            }.await()
        }
        return when (result) {
            is AppResult.Success ->
                if (result.data) AppResult.Success(Unit)
                else AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found"))
            is AppResult.Error -> AppResult.Error(result.error)
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
    }

    private sealed interface EditTx {
        data object NotFound : EditTx
        data object Conflict : EditTx
        data object OwnerInvalid : EditTx
        data class Done(val record: LocalUserRecord) : EditTx
    }

    /**
     * تعديل سجل مستخدم في معاملة واحدة مع كشف التعارض (قيمة updated_at التي رآها المدير = الحالية، أو أن التعديل مطبَّق أصلاً).
     * المالك الجديد يجب أن يكون موجوداً وغير مؤرشف. القراءات كلها قبل الكتابة.
     * بعد الإقرار يُعاد قراءة الوثيقة لأخذ updated_at الفعلي من الخادم، فلا يُقارن تعديل لاحق بقيمة قديمة.
     */
    suspend fun editUserRecord(request: UserEditRequest): AppResult<LocalUserRecord> {
        val userRef = usersCollection.document(request.recordUuid)
        val outcome = safeApiCall {
            firestore.runTransaction<EditTx> { tx ->
                val userDoc = tx.get(userRef)
                val ownerDoc = tx.get(ownersCollection.document(request.ownerId.ifBlank { "_" }))
                if (!userDoc.exists()) return@runTransaction EditTx.NotFound
                val current = mapToLocalUserRecord(userDoc) ?: return@runTransaction EditTx.NotFound
                if (UserEditRules.isAlreadyApplied(current, request)) return@runTransaction EditTx.Done(current)
                if (UserEditRules.isConflict(current, request)) return@runTransaction EditTx.Conflict
                if (request.ownerId != current.ownerId &&
                    (!ownerDoc.exists() || ownerDoc.getString("status").equals(OwnerStatus.ARCHIVED.name, ignoreCase = true))
                ) return@runTransaction EditTx.OwnerInvalid

                tx.update(
                    userRef,
                    mapOf(
                        "owner_id" to request.ownerId,
                        "sync_status" to UserSyncStatus.SYNCED.name.lowercase(),
                        "last_action" to "EDIT_USER",
                        "last_action_at" to FieldValue.serverTimestamp(),
                        "updated_at" to FieldValue.serverTimestamp()
                    )
                )
                EditTx.Done(
                    current.copy(
                        ownerId = request.ownerId,
                        syncStatus = UserSyncStatus.SYNCED,
                        lastAction = "EDIT_USER"
                    )
                )
            }.await()
        }
        return when (outcome) {
            is AppResult.Error -> AppResult.Error(outcome.error)
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            is AppResult.Success -> when (val tx = outcome.data) {
                is EditTx.NotFound -> AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found"))
                is EditTx.Conflict -> AppResult.Error(ApiError(UserEditRules.ERR_CONFLICT, "User record changed since it was opened"))
                is EditTx.OwnerInvalid -> AppResult.Error(ApiError(UserEditRules.ERR_OWNER_INVALID, "Owner not found or archived"))
                is EditTx.Done -> {
                    val fresh = try {
                        mapToLocalUserRecord(userRef.get().await())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    AppResult.Success(fresh ?: tx.record)
                }
            }
        }
    }

    /**
     * مطابقة سجل المستخدمين مع الراوتر عبر سجلات الاكتشاف (discovery_records):
     *  - NULL: مستخدم على الراوتر بلا سجل سحابي (local_users) ← "null_<name>" مفتوح (يظهر في تبويب غير المعرّفين).
     *  - MISSING: سجل سحابي مدار اسمه غير موجود على الراوتر ← "missing_<recordUuid>" مفتوح.
     *  - ما كان مفتوحاً وزال سببه (تبنّي/أرشفة/عودة المستخدم) يُغلق resolved.
     * الكتابة set(merge) على معرّفات حتمية فلا تكرار عند إعادة المطابقة، وfirst_detected_at لا يُستبدل ما دام السجل مفتوحاً.
     */
    suspend fun reconcileDiscovery(routerNames: Set<String>): AppResult<Unit> = safeApiCall {
        val users = usersCollection.get().await().documents
        val localNames = HashSet<String>()
        val missing = HashMap<String, String>() // recordUuid -> routerUsername
        for (doc in users) {
            val name = doc.getString("router_username").orEmpty()
            if (name.isBlank()) continue
            localNames.add(name)
            if (name !in routerNames) missing[doc.id] = name
        }
        val nulls = routerNames.filter { it.isNotBlank() && it !in localNames }

        val existing = discoveryCollection.get().await().documents.associate { it.id to it.getString("status") }
        fun idFor(prefix: String, key: String) = prefix + "_" + key.replace('/', '_')

        val wanted = HashMap<String, Triple<String, String, String>>() // docId -> (type, key, username)
        nulls.forEach { wanted[idFor("null", it)] = Triple("null", it, it) }
        missing.forEach { (uuid, name) -> wanted[idFor("missing", uuid)] = Triple("missing", uuid, name) }

        val ops = ArrayList<(com.google.firebase.firestore.WriteBatch) -> Unit>()
        for ((docId, t) in wanted) {
            val ref = discoveryCollection.document(docId)
            val (type, key, username) = t
            val reopen = existing[docId] != "open"
            val data = hashMapOf<String, Any>(
                "type" to type, "key" to key, "router_username" to username,
                "status" to "open", "last_detected_at" to FieldValue.serverTimestamp()
            )
            if (reopen) {
                data["first_detected_at"] = FieldValue.serverTimestamp()
                data["resolved_at"] = FieldValue.delete()
            }
            ops.add { it.set(ref, data, SetOptions.merge()) }
        }
        for ((docId, status) in existing) {
            if (status == "open" && docId !in wanted && (docId.startsWith("null_") || docId.startsWith("missing_"))) {
                val ref = discoveryCollection.document(docId)
                ops.add { it.set(ref, mapOf("status" to "resolved", "resolved_at" to FieldValue.serverTimestamp()), SetOptions.merge()) }
            }
        }
        ops.chunked(450).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { it(batch) }
            batch.commit().await()
        }
    }

    /**
     * يطابق قيدي سجل مستخدم مُدار بعد تطبيق تصنيف على حسابه في الراوتر. المشترك الشهري لا يُمسّ (يملكه الاشتراك).
     * لا سجل بهذا الاسم = لا شيء يُحدَّث (مستخدم غير مُدار). يعيد true إن حُدّث سجل.
     */
    suspend fun setRecordLimitsByUsername(routerUsername: String, uptimeSeconds: Long?, transferBytes: Long?): AppResult<Boolean> = safeApiCall {
        val doc = usersCollection.whereEqualTo("router_username", routerUsername).limit(1).get().await().documents.firstOrNull()
            ?: return@safeApiCall false
        if (SystemClass.fromWire(doc.getString("system_class")) == SystemClass.SUBSCRIPTION) return@safeApiCall false
        doc.reference.update(
            mapOf(
                "limit_uptime_seconds" to uptimeSeconds,
                "limit_transfer_bytes" to transferBytes,
                "last_action" to "CLASSIFICATION_APPLY",
                "last_action_at" to FieldValue.serverTimestamp(),
                "updated_at" to FieldValue.serverTimestamp()
            )
        ).await()
        true
    }

    suspend fun deleteLocalUserRecord(recordUuid: String): AppResult<Unit> = safeApiCall {
        usersCollection.document(recordUuid).delete().await()
    }
}
