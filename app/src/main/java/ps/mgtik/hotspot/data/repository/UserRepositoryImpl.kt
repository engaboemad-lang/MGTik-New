package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.map
import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.data.cloud.source.CloudUserDataSource
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.data.offline.CommandCodec
import ps.mgtik.hotspot.data.offline.UserArchivePayload
import ps.mgtik.hotspot.domain.generation.CreateOutcome
import ps.mgtik.hotspot.domain.usecase.UserArchiveFlow
import ps.mgtik.hotspot.domain.usecase.OperationalSettingsResolver
import ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings
import ps.mgtik.hotspot.domain.usecase.UserEditFlow
import ps.mgtik.hotspot.domain.usecase.UserEditRules
import ps.mgtik.hotspot.domain.usecase.UserOperationPorts
import ps.mgtik.hotspot.data.userman.UserManSource
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.*
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.usecase.SingleUserRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserRepositoryImpl - تطبيق مستودع المستخدمين المحدث (§4.12)
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudUserDataSource,
    private val userMan: UserManSource,
    private val routerRepository: IRouterRepository,
    private val localCache: LocalCacheManager,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository,
    private val routerState: ps.mgtik.hotspot.domain.repository.IRouterStateManager,
    private val subscriptionRepository: ISubscriptionRepository,
    private val authRepository: IAuthRepository,
    private val settingsReader: ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader
) : IUserRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // مستمع واحد لكل (مالك، حالة): كان كل استدعاء يطلق مستمعاً جديداً لا يتوقف
    private val usersJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override fun listManagedUsers(ownerId: String?, status: UserRecordStatus?): Flow<List<LocalUserRecord>> {
        refreshUsersInBackground(ownerId, status)
        return localCache.getManagedUsersFlow(ownerId, status)
    }

    private fun refreshUsersInBackground(ownerId: String?, status: UserRecordStatus?) {
        val key = "${ownerId.orEmpty()}|${status?.name.orEmpty()}"
        usersJobs.remove(key)?.cancel()
        usersJobs[key] = repositoryScope.launch {
            try {
                cloudDataSource.getManagedUsersSnapshots(ownerId, status).collect { snap ->
                    localCache.syncManagedUsers(
                        users = snap.users,
                        presentIds = snap.presentIds,
                        fullScope = ownerId == null && status == null,
                        serverConfirmed = !snap.fromCache
                    )
                }
            } catch (e: Exception) {
                auditRepo.logAction("SYNC_ERROR", "Failed to refresh users: ${e.message}", LogSeverity.ERROR)
            }
        }
    }

    // مستمع واحد لسجلات الاكتشاف المفتوحة (NULL وMISSING) يكتب في Room؛ القوائم تُقرأ من Room فتعمل دون اتصال.
    @Volatile private var discoveryJob: kotlinx.coroutines.Job? = null

    private fun ensureDiscoveryObserver() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = repositoryScope.launch {
            try {
                cloudDataSource.getOpenDiscoverySnapshots().collect { snap ->
                    localCache.syncDiscovery(snap.records, serverConfirmed = !snap.fromCache)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                auditRepo.logAction("SYNC_ERROR", "Failed to refresh discovery records: ${e.message}", LogSeverity.ERROR)
            }
        }
    }

    override fun listNullUsers(): Flow<List<DiscoveryRecord>> {
        ensureDiscoveryObserver()
        return localCache.getOpenDiscoveryFlow(DiscoveryType.NULL)
    }

    override fun listMissingUsers(): Flow<List<DiscoveryRecord>> {
        ensureDiscoveryObserver()
        return localCache.getOpenDiscoveryFlow(DiscoveryType.MISSING)
    }

    /**
     * صفحة الأرشيف من السحابة، وتُحفظ نسخة محلية منها. تعذّر قراءة أول صفحة ⇒ آخر ما في النسخة المحلية (دون صفحات تالية)،
     * فإن لم توجد نسخة يُعاد الخطأ الأصلي.
     */
    override suspend fun getArchivedUsersPage(pageSize: Int, cursor: String?): AppResult<ArchivedUsersPage> {
        val result = cloudDataSource.getArchivedUsersPage(pageSize, cursor)
        if (result is AppResult.Success) {
            localCache.saveArchivedUsers(result.data.items)
        } else if (result is AppResult.Error && cursor == null) {
            val cached = localCache.getLatestArchivedUsers(pageSize)
            if (cached.isNotEmpty()) return AppResult.Success(ArchivedUsersPage(cached, null))
        }
        return result
    }

    override suspend fun countArchivedUsers(): AppResult<Long> = cloudDataSource.countArchivedUsers()

    private companion object {
        const val QUEUE_PRECHECK_TIMEOUT_MS = 5_000L
    }

    private suspend fun queueCommand(type: OfflineCommandType, payload: String, key: String) {
        val command = OfflineCommand(type = type, payloadJson = payload, idempotencyKey = key)
        offlineRepo.addCommand(command)
        offlineScheduler.scheduleSync()
        auditRepo.logAction("QUEUE_COMMAND", "Command ${type.name} queued for sync", LogSeverity.INFO)
    }

    override suspend fun enableUser(recordUuid: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> =
        setUserEnabled(recordUuid, idempotencyKey, skipQueue, enabled = true)

    override suspend fun disableUser(recordUuid: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> =
        setUserEnabled(recordUuid, idempotencyKey, skipQueue, enabled = false)

    /**
     * تفعيل/تعطيل مستخدم. التنفيذ المباشر (skipQueue = true، ومنه منفّذ الطابور) يغيّر الراوتر فعلاً أولاً
     * (disabled = yes/no)، ثم يسجّل النتيجة في السحابة والكاش. كان يكتفي بتحديث sync_status في السحابة فيعيد نجاحاً
     * بينما المستخدم على الراوتر لم يتغيّر وحالته (status) في السحابة كما كانت.
     * فشل الراوتر يُعاد كما هو (قابلاً للإعادة بالطابور)، وإعادة التنفيذ آمنة لأن PATCH disabled idempotent.
     */
    private suspend fun setUserEnabled(
        recordUuid: String,
        idempotencyKey: String,
        skipQueue: Boolean,
        enabled: Boolean
    ): AppResult<Unit> {
        val action = if (enabled) "ENABLE_USER" else "DISABLE_USER"
        subscriptionManagedError(recordUuid)?.let { return it }
        auditRepo.logAction(action, "${if (enabled) "Enabling" else "Disabling"} user record: $recordUuid", LogSeverity.INFO)
        if (!skipQueue) {
            queueCommand(if (enabled) OfflineCommandType.ENABLE_USER else OfflineCommandType.DISABLE_USER, recordUuid, idempotencyKey)
            return AppResult.Success(Unit)
        }

        val record = localCache.getUserByUuid(recordUuid)
            ?: return AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found in local cache"))
        val config = routerState.status.value.config.takeIf { it.host.isNotBlank() }
            ?: return AppResult.Error(ApiError("NO_CONFIG", "Router connection is not configured"))

        when (val routerResult = userMan.enforceDisabledState(config, record.routerUsername, disabled = !enabled)) {
            is AppResult.Error -> return routerResult
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            is AppResult.Success -> if (!routerResult.data) {
                return AppResult.Error(ApiError(UserEditRules.ERR_MISSING_ON_ROUTER, "User ${record.routerUsername} is not on the router"))
            }
        }

        val newStatus = if (enabled) UserRecordStatus.ACTIVE else UserRecordStatus.DISABLED
        val cloudResult = cloudDataSource.updateUserStatus(recordUuid, newStatus, UserSyncStatus.SYNCED, action)
        if (cloudResult is AppResult.Success) {
            localCache.saveManagedUsers(
                listOf(record.copy(status = newStatus, syncStatus = UserSyncStatus.SYNCED, lastAction = action))
            )
        }
        // التعطيل: حالة الحساب سُجِّلت أعلاه (نتيجة مستقلة)، ثم قطع الجلسات الحيّة على NAS والتحقق منه.
        // فشل القطع لا يُعرض نجاحاً: يُعاد خطأ قابل للإعادة (إعادة التنفيذ آمنة: التعطيل والقطع idempotent)
        if (!enabled) {
            val cut = routerRepository.disconnectUserSessions(config, record.routerUsername)
            if (cut is AppResult.Error) {
                auditRepo.logAction(action, "Account disabled but session disconnect failed for ${record.routerUsername}: ${cut.error.code}", LogSeverity.ERROR)
                return AppResult.Error(ApiError(UserEditRules.ERR_SESSION_DISCONNECT_FAILED, "Account disabled but its session could not be disconnected: ${cut.error.message}", retryable = true))
            }
            auditRepo.logAction(action, "Sessions of ${record.routerUsername} disconnected: ${(cut as? AppResult.Success)?.data}", LogSeverity.INFO)
        }
        return cloudResult
    }

    // ---------------------------------------------------------------------------------------------------------
    // الأرشفة والتعديل: المنطق (الترتيب والتحقق) في UserArchiveFlow/UserEditFlow؛ هنا التنفيذ الفعلي والطابور فقط.
    // ---------------------------------------------------------------------------------------------------------

    private val operationPorts = object : UserOperationPorts {
        override suspend fun lookup(recordUuid: String): AppResult<UserRecordLookup> =
            when (val cloud = cloudDataSource.lookupRecord(recordUuid)) {
                is AppResult.Success -> cloud
                // السحابة غير متاحة: نكمل من الكاش المحلي إن وُجد السجل فيه، وإلا نُعيد الخطأ كما هو (قابل للإعادة)
                else -> localCache.getUserByUuid(recordUuid)?.let { AppResult.Success(UserRecordLookup.Found(it)) }
                    ?: (cloud as? AppResult.Error) ?: AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            }

        override suspend fun disableOnRouter(routerUsername: String): AppResult<RouterDisableState> {
            val config = routerState.status.value.config.takeIf { it.host.isNotBlank() }
                ?: return AppResult.Error(ApiError.routerNotConfigured())
            return userMan.disableAndVerify(config, routerUsername)
        }

        override suspend fun disconnectSessions(routerUsername: String): AppResult<DisconnectOutcome> {
            val config = routerState.status.value.config.takeIf { it.host.isNotBlank() }
                ?: return AppResult.Error(ApiError.routerNotConfigured())
            return routerRepository.disconnectUserSessions(config, routerUsername)
        }

        override suspend fun commitArchive(
            record: LocalUserRecord, reason: String, archivedBy: String?, routerState: RouterDisableState
        ): AppResult<Unit> = cloudDataSource.archiveUser(
            recordUuid = record.recordUuid,
            reason = reason,
            archivedAt = java.util.Date().toSortableIso(),
            archivedBy = archivedBy,
            routerState = routerState,
            routerHost = this@UserRepositoryImpl.routerState.status.value.config.host
        )

        override suspend fun commitEdit(request: UserEditRequest): AppResult<LocalUserRecord> =
            cloudDataSource.editUserRecord(request)

        override suspend fun recordDisabledWithoutArchive(record: LocalUserRecord) {
            cloudDataSource.updateUserStatus(record.recordUuid, UserRecordStatus.DISABLED, UserSyncStatus.SYNCED, "ARCHIVE_USER")
            localCache.saveManagedUsers(listOf(record.copy(status = UserRecordStatus.DISABLED, lastAction = "ARCHIVE_USER")))
        }

        override suspend fun onArchived(recordUuid: String) {
            localCache.removeManagedUser(recordUuid)
            // سجل MISSING لهذا المستخدم أُغلق في معاملة الأرشفة نفسها؛ نُغلقه محلياً فوراً
            localCache.removeDiscovery(cloudDataSource.discoveryIdFor("missing", recordUuid))
            // نسخة محلية من وثيقة الأرشيف (جهد أقصى: فشل قراءتها لا يُفشل الأرشفة المكتملة)
            (cloudDataSource.getArchivedUser(recordUuid) as? AppResult.Success)?.data?.let { localCache.saveArchivedUsers(listOf(it)) }
        }

        override suspend fun onEdited(record: LocalUserRecord) = localCache.saveManagedUsers(listOf(record))

        override suspend fun currentAdminId(): String? =
            authRepository.getCurrentUser()?.uid?.takeIf { it.isNotBlank() }
    }

    private val archiveFlow by lazy { UserArchiveFlow(operationPorts, auditRepo) }
    private val editFlow by lazy { UserEditFlow(operationPorts, auditRepo) }

    /** (النوع، معرّف السجل) الذي يستهدفه أمر أرشفة/تعديل، أو null لغيرهما أو لحمولة تالفة. */
    private fun targetOf(cmd: OfflineCommand): Pair<UserPendingKind, String>? = try {
        when (cmd.type) {
            OfflineCommandType.ARCHIVE_USER -> UserPendingKind.ARCHIVE to CommandCodec.decode<UserArchivePayload>(cmd.payloadJson).recordUuid
            OfflineCommandType.EDIT_USER -> UserPendingKind.EDIT to CommandCodec.decode<UserEditRequest>(cmd.payloadJson).recordUuid
            OfflineCommandType.DELETE_USER_FROM_ROUTER -> UserPendingKind.DELETE_FROM_ROUTER to cmd.payloadJson
            OfflineCommandType.RE_EXPORT_USER -> UserPendingKind.REEXPORT to cmd.payloadJson
            else -> null
        }
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }

    /** نوع أمر أرشفة/تعديل حيّ (لم يستنفد محاولاته) في الطابور لهذا السجل، أو null. */
    private suspend fun livePendingKindFor(recordUuid: String): UserPendingKind? =
        offlineRepo.getRunnableCommands(MAX_COMMAND_ATTEMPTS)
            .mapNotNull { targetOf(it) }
            .firstOrNull { it.second == recordUuid }
            ?.first

    override fun observePendingOperations(): Flow<Map<String, UserPendingOperation>> =
        offlineRepo.getPendingCommands().map { commands ->
            val out = LinkedHashMap<String, UserPendingOperation>()
            for (cmd in commands) {
                val (kind, uuid) = targetOf(cmd) ?: continue
                out[uuid] = UserPendingOperation(kind, failed = cmd.isDead, lastError = cmd.lastError)
            }
            out
        }

    override suspend fun archiveUser(recordUuid: String, reason: String, idempotencyKey: String, skipQueue: Boolean): AppResult<UserOperationOutcome> {
        val cleanReason = reason.trim()
        subscriptionManagedError(recordUuid)?.let { return it }
        if (cleanReason.isEmpty()) {
            return AppResult.Error(ApiError.validationError("Archive reason is required"))
        }
        auditRepo.logAction("ARCHIVE_USER", "Archiving user: $recordUuid, Reason: $cleanReason", LogSeverity.WARNING)
        if (skipQueue) {
            return archiveFlow.run(recordUuid, cleanReason).map { UserOperationOutcome.COMPLETED }
        }
        // لا ننجز شيئاً هنا: الأمر يُسجَّل Pending ويبقى كذلك حتى يؤكد المنفّذ تعطيل الراوتر والأرشفة السحابية.
        livePendingKindFor(recordUuid)?.let { existing ->
            // ضغط مكرر على الأرشفة نفسها = الأمر القائم نفسه (لا أمر ثانٍ)؛ وتعديل معلّق يمنع الأرشفة حتى يُحسم
            return if (existing == UserPendingKind.ARCHIVE) AppResult.Success(UserOperationOutcome.QUEUED)
            else AppResult.Error(ApiError(UserEditRules.ERR_OPERATION_PENDING, "Another operation is pending for this user"))
        }
        queueCommand(OfflineCommandType.ARCHIVE_USER, CommandCodec.encode(UserArchivePayload(recordUuid, cleanReason)), idempotencyKey)
        return AppResult.Success(UserOperationOutcome.QUEUED)
    }

    override suspend fun editUser(request: UserEditRequest, idempotencyKey: String, skipQueue: Boolean): AppResult<UserOperationOutcome> {
        subscriptionManagedError(request.recordUuid)?.let { return it }
        auditRepo.logAction("EDIT_USER", "Editing user: ${request.recordUuid}", LogSeverity.INFO)
        if (skipQueue) {
            return editFlow.run(request).map { UserOperationOutcome.COMPLETED }
        }
        // تحقق مسبق على السجل المعروض كي لا يُقبل في الطابور ما سيُرفض حتماً (لا تغيير، قيد غير صالح، مالك مقفل)
        val current = localCache.getUserByUuid(request.recordUuid)
            ?: return AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found in local cache"))
        UserEditRules.plan(current, request).let { if (it is AppResult.Error) return it }
        if (livePendingKindFor(request.recordUuid) != null) {
            return AppResult.Error(ApiError(UserEditRules.ERR_OPERATION_PENDING, "Another operation is pending for this user"))
        }
        queueCommand(OfflineCommandType.EDIT_USER, CommandCodec.encode(request), idempotencyKey)
        return AppResult.Success(UserOperationOutcome.QUEUED)
    }

    // ---------------------------------------------------------------------------------------------------------
    // إنشاء مستخدم عادي فردي من S16 (بلا دفعة)
    // ---------------------------------------------------------------------------------------------------------

    // يسلسل الإنشاءات داخل التطبيق: يمنع سباقاً بين فحص اسم الدخول وكتابة السجل لإنشاءين متزامنين
    private val createMutex = Mutex()

    override suspend fun resolveAdminOwner(): AppResult<OwnerAccount> =
        when (val r = cloudDataSource.readOwnersForResolution()) {
            is AppResult.Success -> when (val a = SingleUserRules.resolveAdminOwner(r.data)) {
                is SingleUserRules.AdminOwner.Found -> AppResult.Success(a.owner)
                is SingleUserRules.AdminOwner.Error -> AppResult.Error(ApiError(a.code, "Default owner '${SingleUserRules.ADMIN_OWNER_KEY}' cannot be resolved (${a.code})"))
            }
            is AppResult.Error -> r
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

    override suspend fun createNormalUser(draft: NewNormalUser, idempotencyKey: String): AppResult<CreatedUser> = createMutex.withLock {
        auditRepo.logAction("CREATE_USER", "Creating single NORMAL user ${draft.username}", LogSeverity.INFO)
        SingleUserRules.validateNew(draft)?.let { return@withLock AppResult.Error(ApiError(it, it)) }
        val owner = when (val o = resolveAdminOwner()) {
            is AppResult.Success -> o.data
            is AppResult.Error -> return@withLock o
            is AppResult.Loading -> return@withLock AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

        // الإعدادات التشغيلية تُحسم من السحابة (البروفايل + تصنيفه) بالمحلِّل نفسه الذي يستعمله التوليد الجماعي
        val resolvedNow = when (val r = resolveSettings(draft.profileId)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return@withLock r
            is AppResult.Loading -> return@withLock AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        // السرعة والجلسات تُفرض عبر بروفايل الراوتر: عدم تطابقه مع المحسوم يمنع الإنشاء قبل أي كتابة
        verifyRouterProfile(draft.routerProfileName, resolvedNow)?.let { return@withLock it }

        // إعادة محاولة بالمفتاح نفسه: السجل موجود ⇒ لا سجل ثانٍ، نكمل خطوة الراوتر فقط
        val existing = when (val l = cloudDataSource.lookupRecord(idempotencyKey)) {
            is AppResult.Success -> (l.data as? UserRecordLookup.Found)?.record
            is AppResult.Error -> return@withLock l
            is AppResult.Loading -> return@withLock AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (existing != null && (existing.routerUsername != draft.username || existing.systemClass != SystemClass.NORMAL)) {
            return@withLock AppResult.Error(ApiError(SingleUserRules.ERR_USERNAME_TAKEN, "Operation key already used by another record"))
        }
        if (existing == null) {
            usernameConflict(draft.username)?.let { return@withLock it }
        }
        // إعادة المحاولة تلتزم بلقطة السجل الأصلية (تعديل الباقة بين المحاولتين لا يغيّر قيود الحساب)
        val settings = if (existing == null) resolvedNow
            else resolvedNow.copy(limits = ps.mgtik.hotspot.domain.generation.UserLimits(existing.limitUptimeSeconds, existing.limitTransferBytes))

        val record = existing ?: LocalUserRecord(
            recordUuid = idempotencyKey,
            routerUsername = draft.username,
            ownerId = owner.ownerId,
            status = UserRecordStatus.ACTIVE,
            origin = UserOrigin.GENERATED,
            syncStatus = UserSyncStatus.PENDING,
            limitUptimeSeconds = settings.limits.uptimeSeconds,
            limitTransferBytes = settings.limits.transferBytes,
            systemClass = SystemClass.NORMAL
        )
        val isNew = existing == null
        if (isNew) {
            val source = CloudUserDataSource.VoucherSource(
                username = draft.username, password = draft.password, profileName = draft.routerProfileName,
                batchNumber = CloudUserDataSource.SINGLE_USER_TAG, userComment = draft.comment.trim(),
                profileId = draft.profileId
            )
            when (val c = cloudDataSource.createNormalRecord(record, source, owner.name, settings)) {
                is AppResult.Error -> return@withLock c
                else -> Unit
            }
        }
        val created = executeCreateOnRouter(record, draft, settings, isNew)
        auditRepo.logAction(
            "CREATE_USER_RESULT", "User ${draft.username}: ${(created as? AppResult.Success)?.data?.syncStatus ?: "ERROR"}", LogSeverity.INFO
        )
        created
    }

    /** حسم إعدادات المستخدم من السحابة؛ أخطاء القراءة تُصنَّف قابلة للإعادة عبر safeApiCall، والمنطقية برمزها. */
    private suspend fun resolveSettings(profileId: String): AppResult<ResolvedUserSettings> =
        when (val r = ps.mgtik.hotspot.core.result.safeApiCall { settingsReader.read(profileId) }) {
            is AppResult.Success -> when (val o = r.data) {
                is ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader.Result.Ok -> AppResult.Success(o.settings)
                is ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader.Result.Invalid -> AppResult.Error(ApiError(o.code, o.detail))
            }
            is AppResult.Error -> r
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

    /**
     * يقارن الباقة على الراوتر بالمحسوم (سرعة/جلسات). تعذّر القراءة لا يمنع (الراوتر غير متاح يُعالَج بحالة PENDING
     * عند الإنشاء)، أما اختلاف مقروء فيُرفض صراحةً حتى لا يُنشأ حساب يخالف إعدادات باقته.
     */
    private suspend fun verifyRouterProfile(profileName: String, settings: ResolvedUserSettings): AppResult.Error? {
        val config = routerConfigOrNull() ?: return null
        val actual = (userMan.getProfileSettings(config, profileName) as? AppResult.Success)?.data ?: return null
        // نفس فحص التوليد الجماعي حرفياً (سرعة/جلسات/حدود/صلاحية/سياسة بداية): منطق واحد للمسارين
        val diff = OperationalSettingsResolver.mismatches(settings, actual).map { it.name } +
            OperationalSettingsResolver.validityMismatches(settings, actual)
        return if (diff.isEmpty()) null
        else AppResult.Error(ApiError(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, "Router profile '$profileName' differs from the package settings: $diff"))
    }

    override suspend fun ensureUsernameAvailable(username: String, operationKey: String): AppResult<Unit> {
        val own = ((cloudDataSource.lookupRecord(operationKey) as? AppResult.Success)?.data as? UserRecordLookup.Found)?.record
        if (own != null && own.routerUsername == username) return AppResult.Success(Unit)
        return usernameConflict(username) ?: AppResult.Success(Unit)
    }

    /** اسم الدخول محجوز؟ مُدار/أرشيف/اشتراك سحابياً، أو موجود على الراوتر. تعذّر التحقق السحابي يمنع الإنشاء؛ تعذّر الراوتر لا يمنعه (يُحسم عند الإنشاء). */
    private suspend fun usernameConflict(username: String): AppResult.Error? {
        val taken = AppResult.Error(ApiError(SingleUserRules.ERR_USERNAME_TAKEN, "$username is already in use"))
        when (val r = cloudDataSource.isUsernameReserved(username)) {
            is AppResult.Success -> if (r.data) return taken
            is AppResult.Error -> return r
            is AppResult.Loading -> Unit
        }
        when (val sub = subscriptionRepository.findActiveSubscriptionByUsername(username)) {
            is AppResult.Success -> if (sub.data != null) return taken
            is AppResult.Error -> return sub
            is AppResult.Loading -> Unit
        }
        val config = routerConfigOrNull() ?: return null
        val onRouter = userMan.exists(config, username)
        return if (onRouter is AppResult.Success && onRouter.data) taken else null
    }

    /**
     * ينشئ الحساب على الراوتر في User Manager (الاسم وكلمة السر والتعليق والباقة) ويتحقق بقراءة ثانية، ثم يسجّل النتيجة في السجل.
     * AlreadyExists على سجل جديد = اسم يخص حساباً مجهولاً على الراوتر ⇒ تراجع عن السجل (لا يتيم) وخطأ اسم محجوز؛
     * على إعادة محاولة = نجاح سابق ضاع إقراره ⇒ SYNCED دون إنشاء ثانٍ.
     */
    private suspend fun executeCreateOnRouter(record: LocalUserRecord, draft: NewNormalUser, settings: ResolvedUserSettings, isNew: Boolean): AppResult<CreatedUser> {
        suspend fun finish(status: UserSyncStatus, error: String?): AppResult<CreatedUser> {
            // فشل كتابة النتيجة سحابياً يُبقي السجل PENDING؛ إعادة المحاولة تلتقط الحساب الموجود وتعلّمه SYNCED
            val marked = cloudDataSource.markCreateResult(record.recordUuid, status, error)
            val effective = if (marked is AppResult.Error) UserSyncStatus.PENDING else status
            return AppResult.Success(CreatedUser(record.recordUuid, effective, error ?: (marked as? AppResult.Error)?.error?.message))
        }
        val config = routerConfigOrNull() ?: return finish(UserSyncStatus.PENDING, "NO_ROUTER_CONFIG: router is not configured")
        // التعليق لا يُرسَل إلا إذا أدخله المختص؛ لا وسم دفعة ضمني
        val comment = draft.comment.trim()
        when (val outcome = userMan.createUserClassified(config, draft.username, draft.password, comment, draft.routerProfileName.ifBlank { null }, disabled = false)) {
            is CreateOutcome.Created -> Unit
            is CreateOutcome.AlreadyExists -> if (isNew) {
                cloudDataSource.deleteLocalUserRecord(record.recordUuid)
                return AppResult.Error(ApiError(SingleUserRules.ERR_USERNAME_TAKEN, "${draft.username} already exists on the router"))
            }
            is CreateOutcome.Rejected -> return finish(UserSyncStatus.FAILED, "${ApiError.CODE_ROUTER_REJECTED}: ${outcome.message}")
            is CreateOutcome.Transient -> return finish(UserSyncStatus.PENDING, "${outcome.error.code}: ${outcome.error.message}")
            is CreateOutcome.Fatal -> return finish(UserSyncStatus.FAILED, "${outcome.error.code}: ${outcome.error.message}")
        }
        // تحقق بقراءة ثانية: لا نُعلن SYNCED قبل أن نرى الحساب على الراوتر وقيوده كما حُسمت
        return when (val e = userMan.getUserSnapshot(config, draft.username)) {
            is AppResult.Success -> {
                val snap = e.data
                when {
                    snap == null -> finish(UserSyncStatus.PENDING, "ERR_USER_ROUTER_CREATE_UNVERIFIED: account not found after creation")
                    !OperationalSettingsResolver.limitsMatch(settings.limits, snap.limitUptimeSeconds, snap.limitTransferBytes) ->
                        finish(UserSyncStatus.PENDING, "${OperationalSettingsResolver.ERR_ROUTER_LIMITS_UNVERIFIED}: router limits differ from the package limits")
                    else -> finish(UserSyncStatus.SYNCED, null)
                }
            }
            is AppResult.Error -> finish(UserSyncStatus.PENDING, "${e.error.code}: ${e.error.message}")
            is AppResult.Loading -> finish(UserSyncStatus.PENDING, "Unexpected loading state")
        }
    }

    override suspend fun adoptNullUser(routerUsername: String, ownerId: String, idempotencyKey: String): AppResult<Unit> {
        auditRepo.logAction("ADOPT_USER", "Adopting router user $routerUsername to owner $ownerId", LogSeverity.INFO)
        guardNotSubscriptionUsername(routerUsername)?.let { return it }
        val record = LocalUserRecord(
            recordUuid = UUID.randomUUID().toString(),
            routerUsername = routerUsername,
            ownerId = ownerId,
            origin = UserOrigin.ADOPTED,
            status = UserRecordStatus.ACTIVE
        )
        val created = cloudDataSource.createLocalRecord(record)
        if (created is AppResult.Success) {
            // المستخدم لم يعد «غير معرّف»: يُغلق سجل اكتشافه سحابياً ومحلياً بدل انتظار المطابقة التالية
            cloudDataSource.resolveDiscovery("null", routerUsername)
            localCache.removeDiscovery(cloudDataSource.discoveryIdFor("null", routerUsername))
        }
        return created
    }

    // ---------------------------------------------------------------------------------------------------------
    // حذف من الراوتر / إعادة تصدير / مزامنة / مستخدمو Null
    // ---------------------------------------------------------------------------------------------------------

    override suspend fun getRouterUserSnapshot(username: String): AppResult<RouterUserSnapshot?> {
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        return userMan.getUserSnapshot(config, username)
    }

    override suspend fun getStoredUserPassword(recordUuid: String): AppResult<String?> {
        val record = when (val r = foundRecord(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (record.origin != UserOrigin.GENERATED) return AppResult.Success(null)
        val lookup = if (!record.batchId.isNullOrBlank()) cloudDataSource.getVoucherSource(record.batchId, record.routerUsername)
            else cloudDataSource.getSingleUserSource(recordUuid)
        return when (lookup) {
            is AppResult.Success -> AppResult.Success(lookup.data?.password)
            is AppResult.Error -> lookup
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
    }

    private fun routerConfigOrNull() = routerState.status.value.config.takeIf { it.host.isNotBlank() }

    /**
     * المشترك الشهري (SUBSCRIPTION) تُدار حالته وراوتره من دورة حياة الاشتراك فقط (إيقاف/استئناف/إلغاء/تجديد/انتهاء)،
     * فعمليات المستخدم العادي (تعطيل/أرشفة/تعديل/حذف/إعادة تصدير/مزامنة) تُرفض عليه كي لا تتعارض معها.
     * الكاش المحلي كافٍ: التصنيف ثابت منذ الإنشاء، والسجل غير الموجود لا يُرفض هنا (تتولاه الدوال نفسها).
     */
    private suspend fun subscriptionManagedError(recordUuid: String): AppResult.Error? =
        if (localCache.getUserByUuid(recordUuid)?.systemClass == SystemClass.SUBSCRIPTION)
            AppResult.Error(ApiError(SystemClassRules.ERR_USER_SUBSCRIPTION_MANAGED, "Subscriber is managed by its subscription"))
        else null

    private suspend fun foundRecord(recordUuid: String): AppResult<LocalUserRecord> =
        when (val r = operationPorts.lookup(recordUuid)) {
            is AppResult.Success -> (r.data as? UserRecordLookup.Found)?.record?.let { rec ->
                // فحص النوع على السجل السحابي الحاكم (يغطي حالة غياب السجل من الكاش المحلي)
                SystemClassRules.normalOpsError(rec)?.let { code ->
                    AppResult.Error(ApiError(code, "Subscriber is managed by its subscription"))
                } ?: AppResult.Success(rec)
            } ?: AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found"))
            is AppResult.Error -> r
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

    /** طابور أمر على سجل: أمر مماثل معلّق ⇒ هو نفسه (لا تكرار)، وأمر مختلف معلّق ⇒ خطأ. */
    private suspend fun queueUserCommand(type: OfflineCommandType, kind: UserPendingKind, recordUuid: String, key: String): AppResult<UserOperationOutcome> {
        livePendingKindFor(recordUuid)?.let { existing ->
            return if (existing == kind) AppResult.Success(UserOperationOutcome.QUEUED)
            else AppResult.Error(ApiError(UserEditRules.ERR_OPERATION_PENDING, "Another operation is pending for this user"))
        }
        queueCommand(type, recordUuid, key)
        return AppResult.Success(UserOperationOutcome.QUEUED)
    }

    override suspend fun deleteUserFromRouter(recordUuid: String, idempotencyKey: String, skipQueue: Boolean): AppResult<UserOperationOutcome> {
        auditRepo.logAction("DELETE_USER_FROM_ROUTER", "Deleting user from router: $recordUuid", LogSeverity.WARNING)
        subscriptionManagedError(recordUuid)?.let { return it }
        if (!skipQueue) return queueUserCommand(OfflineCommandType.DELETE_USER_FROM_ROUTER, UserPendingKind.DELETE_FROM_ROUTER, recordUuid, idempotencyKey)

        val record = when (val r = foundRecord(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        when (val deleted = userMan.deleteAndVerify(config, record.routerUsername)) {
            is AppResult.Error -> return deleted
            else -> Unit
        }
        // السجل السحابي يبقى (لا يُحذف)؛ يصبح «مفقوداً» بسجل اكتشاف مفتوح. فشل أي كتابة سحابية هنا = خطأ قابل للإعادة (الحذف idempotent)
        val missing = cloudDataSource.openMissingDiscovery(recordUuid, record.routerUsername)
        if (missing is AppResult.Error) return missing
        val touched = cloudDataSource.updateUserStatus(recordUuid, record.status, UserSyncStatus.SYNCED, "DELETE_USER_FROM_ROUTER")
        if (touched is AppResult.Error) return touched
        localCache.saveManagedUsers(listOf(record.copy(syncStatus = UserSyncStatus.SYNCED, lastAction = "DELETE_USER_FROM_ROUTER")))
        auditRepo.logAction("DELETE_USER_FROM_ROUTER_DONE", "User removed from router, record kept as missing: $recordUuid", LogSeverity.INFO)
        return AppResult.Success(UserOperationOutcome.COMPLETED)
    }

    override suspend fun reExportUserToRouter(recordUuid: String, idempotencyKey: String, skipQueue: Boolean): AppResult<UserOperationOutcome> {
        auditRepo.logAction("RE_EXPORT_USER", "Re-exporting user to router: $recordUuid", LogSeverity.INFO)
        subscriptionManagedError(recordUuid)?.let { return it }
        if (!skipQueue) return queueUserCommand(OfflineCommandType.RE_EXPORT_USER, UserPendingKind.REEXPORT, recordUuid, idempotencyKey)

        val record = when (val r = foundRecord(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        val wantDisabled = record.status == UserRecordStatus.DISABLED

        val present = when (val e = userMan.exists(config, record.routerUsername)) {
            is AppResult.Success -> e.data
            is AppResult.Error -> return e
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (present) {
            // موجود أصلاً (إعادة تنفيذ بعد نجاح ضاع إقراره): لا إنشاء مكرر، نكتفي بفرض الحالة
            val enforced = userMan.enforceDisabledState(config, record.routerUsername, wantDisabled)
            if (enforced is AppResult.Error) return enforced
        } else {
            val batchId = record.batchId
            // مولَّد بدفعة ⇒ المصدر كرت الدفعة؛ مولَّد بلا دفعة (إنشاء فردي من S16) ⇒ مصدر الإنشاء المحفوظ في سجله؛ متبنّى ⇒ لا مصدر
            val source = if (record.origin == UserOrigin.GENERATED) {
                val lookup = if (!batchId.isNullOrBlank()) cloudDataSource.getVoucherSource(batchId, record.routerUsername)
                    else cloudDataSource.getSingleUserSource(recordUuid)
                when (lookup) {
                    is AppResult.Success -> lookup.data
                    is AppResult.Error -> return lookup
                    is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
                }
            } else null
            if (source == null) {
                return AppResult.Error(ApiError(UserEditRules.ERR_REEXPORT_SOURCE_MISSING, "No stored password/profile for ${record.routerUsername}"))
            }
            val comment = source.userComment.trim()
            when (val outcome = userMan.createUserClassified(config, source.username, source.password, comment, source.profileName.ifBlank { null }, disabled = wantDisabled)) {
                is CreateOutcome.Created, is CreateOutcome.AlreadyExists -> Unit
                is CreateOutcome.Rejected -> return AppResult.Error(ApiError(ApiError.CODE_ROUTER_REJECTED, outcome.message))
                is CreateOutcome.Transient -> return AppResult.Error(ApiError(outcome.error.code, outcome.error.message, retryable = true))
                is CreateOutcome.Fatal -> return AppResult.Error(ApiError(outcome.error.code, outcome.error.message, retryable = false))
            }
        }
        val resolved = cloudDataSource.resolveDiscovery("missing", recordUuid)
        if (resolved is AppResult.Error) return resolved
        val touched = cloudDataSource.updateUserStatus(recordUuid, record.status, UserSyncStatus.SYNCED, "RE_EXPORT_USER")
        if (touched is AppResult.Error) return touched
        localCache.removeDiscovery(cloudDataSource.discoveryIdFor("missing", recordUuid))
        localCache.saveManagedUsers(listOf(record.copy(syncStatus = UserSyncStatus.SYNCED, lastAction = "RE_EXPORT_USER")))
        auditRepo.logAction("RE_EXPORT_USER_DONE", "User re-exported to router: $recordUuid", LogSeverity.INFO)
        return AppResult.Success(UserOperationOutcome.COMPLETED)
    }
    /**
     * تجديد باقة المشترك = تجديد اشتراكه القائم عبر نظام الاشتراكات (فترة جديدة + فاتورة اشتراك + تصفير عدّادات وتفعيل
     * الحساب على الراوتر ضمن أمر RENEW نفسه). الاشتراك كيان مستقل: يُربط بالمستخدم باسم الدخول لا بمعرّف سجله.
     * لا اشتراك له = خطأ صريح (لا نخترع اشتراكاً). إن لم يتحقق أمر الراوتر بعد يُرجَع خطأ قابل لإعادة المحاولة فيعيده
     * الطابور بالمفتاح نفسه (التجديد idempotent: لا فاتورة ثانية).
     */
    override suspend fun renewUserPackage(recordUuid: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        auditRepo.logAction("RENEW_USER_PACKAGE", "Renewing package for user record: $recordUuid", LogSeverity.INFO)
        // تجديد المشترك الشهري من S28 فقط (مرجع دورة حياة الاشتراك)
        subscriptionManagedError(recordUuid)?.let { return it }
        val record = localCache.getUserByUuid(recordUuid)
            ?: return AppResult.Error(ApiError("USER_NOT_FOUND", "User record not found in local cache"))

        fun List<Subscription>.renewable(): Subscription? =
            filter { it.customerUsername == record.routerUsername && it.status != SubscriptionStatus.CANCELLED }.maxByOrNull { it.createdAt }

        if (!skipQueue) {
            // قاعدة المشروع: skipQueue=false → الطابور (WorkManager)، والمنفّذ يعيد الاستدعاء بـ skipQueue=true.
            // إن كانت السحابة قابلة للقراءة الآن نرفض فوراً غياب الاشتراك؛ وإن كنا دون اتصال نُدرج الأمر ويفشل صراحةً عند تنفيذه.
            val known = try {
                withTimeoutOrNull(QUEUE_PRECHECK_TIMEOUT_MS) { subscriptionRepository.getSubscriptions().first() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (known != null && known.renewable() == null) {
                return AppResult.Error(ApiError("ERR_NO_SUBSCRIPTION", "No subscription is linked to user ${record.routerUsername}"))
            }
            queueCommand(OfflineCommandType.RENEW_USER_PACKAGE, recordUuid, idempotencyKey)
            return AppResult.Success(Unit)
        }

        val subscription = try {
            subscriptionRepository.getSubscriptions().first().renewable()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AppResult.Error(ApiError.mapThrowable(e))
        } ?: return AppResult.Error(ApiError("ERR_NO_SUBSCRIPTION", "No subscription is linked to user ${record.routerUsername}"))

        return when (val renewed = subscriptionRepository.renewSubscription(subscription.id, idempotencyKey)) {
            is AppResult.Error -> renewed
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            is AppResult.Success ->
                if (renewed.data.execution == SubscriptionExecution.SYNCED) AppResult.Success(Unit)
                else AppResult.Error(ApiError(SubscriptionRules.ERR_ROUTER_PENDING, "Renewed, but the router update is still pending", retryable = true))
        }
    }
    override suspend fun deleteLocalUserRecord(recordUuid: String, idempotencyKey: String): AppResult<Unit> {
        subscriptionManagedError(recordUuid)?.let { return it }
        val result = cloudDataSource.deleteLocalUserRecord(recordUuid)
        if (result is AppResult.Success) localCache.removeManagedUser(recordUuid)
        return result
    }

    /** غاب الحساب عن الراوتر ⇒ يُعاد إنشاؤه من كرته؛ موجود ⇒ تُعاد مزامنة حالته فقط. */
    override suspend fun retryCreationFailedUser(recordUuid: String, idempotencyKey: String): AppResult<Unit> {
        auditRepo.logAction("RETRY_USER_CREATION", "Retrying creation for user record: $recordUuid", LogSeverity.INFO)
        subscriptionManagedError(recordUuid)?.let { return it }
        val record = when (val r = foundRecord(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        val present = when (val e = userMan.exists(config, record.routerUsername)) {
            is AppResult.Success -> e.data
            is AppResult.Error -> return e
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        return if (present) retryUserSync(recordUuid, idempotencyKey)
        else reExportUserToRouter(recordUuid, idempotencyKey, skipQueue = true).map { }
    }

    /**
     * يفرض حالة السجل على الراوتر (ACTIVE ⇒ disabled=no، DISABLED ⇒ disabled=yes) ويتحقق، ثم يعلّم السجل SYNCED.
     * EXPIRED لا يحدد تعطيلاً صريحاً فيُرفض. غياب الحساب عن الراوتر ⇒ يُفتح سجل MISSING ويُعاد خطأ (العلاج: إعادة التصدير).
     */
    override suspend fun retryUserSync(recordUuid: String, idempotencyKey: String): AppResult<Unit> {
        auditRepo.logAction("RETRY_USER_SYNC", "Retrying router sync for user record: $recordUuid", LogSeverity.INFO)
        subscriptionManagedError(recordUuid)?.let { return it }
        val record = when (val r = foundRecord(recordUuid)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (record.status == UserRecordStatus.EXPIRED) {
            return AppResult.Error(ApiError(UserEditRules.ERR_SYNC_NOT_APPLICABLE, "Expired users have no explicit router state to enforce"))
        }
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        val present = when (val e = userMan.enforceDisabledState(config, record.routerUsername, record.status == UserRecordStatus.DISABLED)) {
            is AppResult.Success -> e.data
            is AppResult.Error -> return e
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        if (!present) {
            cloudDataSource.openMissingDiscovery(recordUuid, record.routerUsername)
            return AppResult.Error(ApiError(UserEditRules.ERR_MISSING_ON_ROUTER, "User ${record.routerUsername} is not on the router"))
        }
        val touched = cloudDataSource.updateUserStatus(recordUuid, record.status, UserSyncStatus.SYNCED, "RETRY_USER_SYNC")
        if (touched is AppResult.Error) return touched
        localCache.saveManagedUsers(listOf(record.copy(syncStatus = UserSyncStatus.SYNCED, lastAction = "RETRY_USER_SYNC")))
        return AppResult.Success(Unit)
    }

    /** يمنع تنفيذ عملية «مجهول» على اسم صار مُداراً (تبنّاه جهاز آخر بين العرض والتنفيذ). */
    private suspend fun guardStillNull(routerUsername: String): AppResult<Unit>? =
        when (val m = cloudDataSource.isManagedUsername(routerUsername)) {
            is AppResult.Error -> m
            is AppResult.Success ->
                if (m.data) AppResult.Error(ApiError(UserEditRules.ERR_NULL_USER_NOW_MANAGED, "$routerUsername is now a managed user"))
                else guardNotSubscriptionUsername(routerUsername)
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }

    /**
     * اشتراك قديم بلا سجل مستخدم يظهر على الراوتر كـ«غير معرّف»؛ لا يُتبنّى ولا يُعطَّل ولا يُحذف كمستخدم عادي.
     * تعذّر التحقق من الاشتراكات (شبكة) يمنع العملية بدل افتراض أنه غير مشترك.
     */
    private suspend fun guardNotSubscriptionUsername(routerUsername: String): AppResult<Unit>? {
        val subs = try {
            withTimeoutOrNull(QUEUE_PRECHECK_TIMEOUT_MS) { subscriptionRepository.getSubscriptions().first() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return AppResult.Error(ApiError(ApiError.CODE_TIMEOUT, "Cannot verify subscriptions for $routerUsername", retryable = true))
        return if (subs.any { it.customerUsername == routerUsername })
            AppResult.Error(ApiError(SystemClassRules.ERR_USER_SUBSCRIPTION_MANAGED, "$routerUsername belongs to a subscription"))
        else null
    }

    override suspend fun disableNullUser(routerUsername: String, idempotencyKey: String): AppResult<Unit> {
        auditRepo.logAction("DISABLE_NULL_USER", "Disabling unassigned router user: $routerUsername", LogSeverity.WARNING)
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        guardStillNull(routerUsername)?.let { return it }
        return when (val r = userMan.disableAndVerify(config, routerUsername)) {
            is AppResult.Error -> r
            is AppResult.Loading -> AppResult.Error(ApiError.unknownError("Unexpected loading state"))
            is AppResult.Success -> {
                // غاب عن الراوتر أصلاً ⇒ سجل «غير معرّف» قديم: يُغلق. معطَّل ⇒ يبقى مفتوحاً حتى يُتبنّى أو يُحذف.
                if (r.data == RouterDisableState.ABSENT) {
                    cloudDataSource.resolveDiscovery("null", routerUsername)
                    localCache.removeDiscovery(cloudDataSource.discoveryIdFor("null", routerUsername))
                }
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun deleteNullUser(routerUsername: String, idempotencyKey: String): AppResult<Unit> {
        auditRepo.logAction("DELETE_NULL_USER", "Deleting unassigned router user: $routerUsername", LogSeverity.WARNING)
        val config = routerConfigOrNull() ?: return AppResult.Error(ApiError.routerNotConfigured())
        guardStillNull(routerUsername)?.let { return it }
        when (val r = userMan.deleteAndVerify(config, routerUsername)) {
            is AppResult.Error -> return r
            else -> Unit
        }
        val resolved = cloudDataSource.resolveDiscovery("null", routerUsername)
        if (resolved is AppResult.Error) return resolved
        localCache.removeDiscovery(cloudDataSource.discoveryIdFor("null", routerUsername))
        return AppResult.Success(Unit)
    }

    /**
     * مطابقة حقيقية: أسماء المستخدمين على الراوتر مقابل السجلات السحابية، وتُسجَّل الفروق في discovery_records
     * (NULL يظهر في تبويب «غير المعرّفين» لتبنّيه). كان يعيد خطأ NOT_IMPLEMENTED فيبقى زر المزامنة في S16 دون وظيفة
     * ويبقى تبويب غير المعرّفين فارغاً دائماً لأن لا شيء في التطبيق يكتب سجلات الاكتشاف.
     */
    override suspend fun reconcileUserRegistry(): AppResult<Unit> {
        auditRepo.logAction("RECONCILE_USERS", "Reconciling user registry with router", LogSeverity.INFO)
        val config = routerState.status.value.config.takeIf { it.host.isNotBlank() }
            ?: return AppResult.Error(ApiError("NO_CONFIG", "Router connection is not configured"))
        val names = when (val r = userMan.getUsernamesWithComments(config)) {
            is AppResult.Success -> r.data.mapTo(HashSet()) { it.first }
            is AppResult.Error -> return r
            is AppResult.Loading -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val result = cloudDataSource.reconcileDiscovery(names)
        if (result is AppResult.Error) {
            auditRepo.logAction("RECONCILE_USERS_FAILED", "Reconcile failed: ${result.error.code}", LogSeverity.ERROR)
        }
        return result
    }
}
