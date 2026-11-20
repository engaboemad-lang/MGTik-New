package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ps.mgtik.hotspot.domain.usecase.OperationalSettingsResolver
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GenerationSaga - تنفيذ توليد دفعة كروت قابل للاستئناف:
 *
 *   PREPARE → GENERATE → ROUTER_PUSH → CLOUD_COMMIT → BILLING
 *
 * الضمانات:
 *  - الحالة تُحفظ (RunStore.save) عند كل انتقال، فيمكن قتل العملية في أي لحظة ثم إعادة [run].
 *  - كل خطوة تتخطى ما أُنجز: الأكواد لا تُعاد توليدها، والكروت الموجودة على الراوتر (بتعليق الدفعة) لا تُدفع مرتين،
 *    والكتابات السحابية idempotent (معرّفات مشتقة).
 *  - الفشل الجزئي: الكروت المرفوضة لا توقف الدفعة، وتُفوتَر الناجحة فقط.
 *  - الإلغاء قبل الفوترة: حذف مستخدمي الدفعة من الراوتر (بالتعليق) وإلغاء الطلب.
 *  - لا استثناءات تُبتلع: أي استثناء غير متوقع ينتشر للمستدعي (Worker) وتبقى الحالة المحفوظة سليمة.
 */
class GenerationSaga(
    private val store: RunStore,
    private val router: RouterPort,
    private val cloud: CloudPort,
    private val random: Random = SecureRandom(),
    private val routerChunkSize: Int = 50,
    private val routerConcurrency: Int = 4,
    // كل كرت يكتب وثيقتين (voucher + سجل المستخدم) فالسقف 200 ⇒ 400 عملية < حد Firestore (500)
    private val cloudChunkSize: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun run(executionId: String): SagaOutcome {
        var state = store.load(executionId)
            ?: return SagaOutcome.Failed(SagaError(SagaError.RUN_NOT_FOUND, "Run $executionId not found", false))

        if (state.status == RunStatus.COMPLETED) return SagaOutcome.Completed(partial = state.failed > 0)
        if (state.status == RunStatus.CANCELLED) return SagaOutcome.Cancelled

        // الإلغاء بعد الفوترة غير ممكن؛ العلم يُتجاهل (الإلغاء المالي له مساره الخاص)
        if (state.cancelRequested && state.step(SagaStep.BILLING) != StepState.SUCCESS) return cancel(state)

        state = checkpoint(state.copy(status = RunStatus.RUNNING, errorCode = null, errorMessage = null, completedAtMillis = null))

        for (step in SagaStep.values()) {
            if (state.step(step) == StepState.SUCCESS) continue
            state = checkpoint(state.withStep(step, StepState.RUNNING).copy(progressDone = 0, progressTotal = 0))

            val outcome = when (step) {
                SagaStep.PREPARE -> prepare(state)
                SagaStep.GENERATE -> generate(state)
                SagaStep.ROUTER_PUSH -> routerPush(state)
                SagaStep.CLOUD_COMMIT -> cloudCommit(state)
                SagaStep.BILLING -> billing(state)
            }

            when (outcome) {
                is StepOutcome.Done -> state = checkpoint(outcome.state.withStep(step, StepState.SUCCESS))
                is StepOutcome.Cancel -> return cancel(outcome.state)
                is StepOutcome.Suspend -> {
                    checkpoint(outcome.state.copy(status = RunStatus.RETRYING, errorCode = outcome.error.code, errorMessage = outcome.error.message))
                    return SagaOutcome.Suspended(outcome.error)
                }
                is StepOutcome.Fail -> {
                    checkpoint(
                        outcome.state.withStep(step, StepState.FAILED)
                            .copy(
                                status = RunStatus.FAILED,
                                errorCode = outcome.error.code,
                                errorMessage = outcome.error.message,
                                completedAtMillis = clock()
                            )
                    )
                    return SagaOutcome.Failed(outcome.error)
                }
            }
        }

        val done = checkpoint(
            state.copy(
                status = RunStatus.COMPLETED, errorCode = null, errorMessage = null,
                progressDone = 0, progressTotal = 0, completedAtMillis = clock()
            )
        )
        return SagaOutcome.Completed(partial = done.failed > 0)
    }

    /** يُستدعى من الـ Worker عند استنفاد محاولات إعادة التشغيل المؤقتة. */
    suspend fun failPermanently(executionId: String, error: SagaError) {
        val state = store.load(executionId) ?: return
        if (state.isTerminal) return
        val running = SagaStep.values().firstOrNull { state.step(it) != StepState.SUCCESS }
        checkpoint(
            (if (running != null) state.withStep(running, StepState.FAILED) else state)
                .copy(status = RunStatus.FAILED, errorCode = error.code, errorMessage = error.message, completedAtMillis = clock())
        )
    }

    // ---------------------------------------------------------------- الخطوات

    private suspend fun prepare(state: RunState): StepOutcome {
        val spec = state.spec
        val violations = GenerationRules.validate(
            spec.profileId, spec.ownerId, spec.count, spec.format, spec.prefix, spec.codeLength,
            spec.mode, spec.passwordType, spec.passwordLength, spec.comment
        )
        if (violations.isNotEmpty()) {
            return StepOutcome.Fail(state, SagaError(violations.values.first(), "Invalid request: $violations", false))
        }
        // الحاجز قبل أي طلب/دفعة: راوتر غير جاهز لا يصدر كروتاً لن تعمل. لا تعديل تلقائي؛ التصحيح من شاشة إعداد الراوتر
        when (val r = router.failedReadinessChecks()) {
            is SagaResult.Err -> return failOrSuspend(state, r.error)
            is SagaResult.Ok -> if (r.value.isNotEmpty()) {
                return StepOutcome.Fail(state, SagaError(GenerationRules.ERR_ROUTER_NOT_READY, "Router is not ready for generation: ${r.value.joinToString()}", false))
            }
        }
        val prepared = when (val r = cloud.prepare(state)) {
            is SagaResult.Err -> return failOrSuspend(state, r.error)
            is SagaResult.Ok -> r.value
        }
        // اسم الباقة المرجع هو المخزَّن في السحابة (الواجهة قد تحمل نسخة قديمة بعد إعادة تسمية من جهاز آخر)؛ وفارغ ⇒
        // إنشاء حسابات بلا Profile، فيُمنع قبل لمس الراوتر.
        val profileName = prepared.profileName?.takeIf { it.isNotBlank() } ?: spec.profileName
        if (profileName.isBlank()) {
            return StepOutcome.Fail(state, SagaError(SagaError.PROFILE_UNAVAILABLE, "Package has no User Manager profile name", false))
        }
        val resolved = if (profileName == spec.profileName) state else state.copy(spec = spec.copy(profileName = profileName))
        // السرعة والجلسات المتزامنة تُفرض عبر بروفايل الراوتر لا حساب المستخدم: لا ندفع حسابات قبل التأكد من تطابقه مع المحسوم
        prepared.settings?.let { expected ->
            when (val r = router.profileSettings(profileName)) {
                is SagaResult.Err -> return failOrSuspend(state, r.error)
                is SagaResult.Ok -> {
                    // القراءة نجحت ولا باقة بهذا الاسم في User Manager: الإنشاء سينتج حسابات بلا Profile ولا حدود ⇒ نمنع قبل لمس الراوتر
                    val actual = r.value ?: return StepOutcome.Fail(
                        state,
                        SagaError(OperationalSettingsResolver.ERR_ROUTER_PROFILE_MISSING, "Package '$profileName' does not exist in User Manager", false)
                    )
                    val diff = OperationalSettingsResolver.mismatches(expected, actual).map { it.name } +
                        OperationalSettingsResolver.validityMismatches(expected, actual)
                    if (diff.isNotEmpty()) {
                        return StepOutcome.Fail(
                            state,
                            SagaError(OperationalSettingsResolver.ERR_ROUTER_PROFILE_OUT_OF_SYNC, "Router profile '$profileName' differs from the package settings: $diff", false)
                        )
                    }
                }
            }
        }
        return StepOutcome.Done(
            resolved.copy(
                orderId = prepared.orderId,
                batchId = prepared.batchId,
                batchNumber = prepared.batchNumber,
                pricePerVoucherMinor = prepared.pricePerVoucherMinor,
                ownerName = prepared.ownerName,
                limits = prepared.limits
            )
        )
    }

    private suspend fun generate(state: RunState): StepOutcome {
        // استئناف: الأكواد المحفوظة لا تُعاد توليدها
        val already = store.counts(state.executionId).values.sum()
        if (already == state.requested) return StepOutcome.Done(state)

        val spec = state.spec

        val existing = when (val r = router.existingUsernames()) {
            is SagaResult.Err -> return failOrSuspend(state, r.error)
            is SagaResult.Ok -> r.value
        }
        val format = CodeFormat.parse(spec.format)
            ?: return StepOutcome.Fail(state, SagaError(GenerationRules.ERR_FORMAT_INVALID, "Unknown format", false))
        val mode = GenerationMode.parse(spec.mode) ?: GenerationMode.RANDOM
        val passwordType = PasswordType.parse(spec.passwordType)

        val credentials: List<GeneratedCredential> = if (mode == GenerationMode.SEQUENTIAL) {
            when (val r = generateSequential(state, existing, passwordType)) {
                is SequentialOutcome.Ok -> r.credentials
                is SequentialOutcome.Stop -> return r.outcome
            }
        } else {
            try {
                VoucherCodeGenerator.generate(state.requested, format, spec.prefix, spec.codeLength, existing, random, passwordType, spec.passwordLength)
            } catch (e: IllegalStateException) {
                return StepOutcome.Fail(state, SagaError(SagaError.KEYSPACE_EXHAUSTED, "Could not generate unique codes", false))
            }
        }

        store.insertStaged(credentials.mapIndexed { i, c -> StagedVoucher(state.executionId, i + 1, c.username, c.password) })
        return StepOutcome.Done(state)
    }

    private sealed class SequentialOutcome {
        class Ok(val credentials: List<GeneratedCredential>) : SequentialOutcome()
        class Stop(val outcome: StepOutcome) : SequentialOutcome()
    }

    /**
     * التوليد التسلسلي فوق عدّاد البروفايل الدائم: كل جولة تحجز أرقاماً بمعاملة ذرية (لا تتكرر بين عمليتين متزامنتين).
     * اسم محجوز مسبقاً على الراوتر يُتخطّى ويُحجز بديل في جولة تالية؛ الأرقام المتخطّاة تبقى محروقة (لا تُعاد).
     */
    private suspend fun generateSequential(state: RunState, existing: Set<String>, passwordType: PasswordType?): SequentialOutcome {
        val spec = state.spec
        val capacity = GenerationRules.sequenceCapacity(spec.codeLength)
        val credentials = ArrayList<GeneratedCredential>(state.requested)
        val taken = HashSet<String>(existing)
        var rounds = 0
        while (credentials.size < state.requested) {
            if (rounds++ >= MAX_SEQUENCE_ROUNDS) {
                return SequentialOutcome.Stop(StepOutcome.Fail(state, SagaError(SagaError.KEYSPACE_EXHAUSTED, "Too many conflicts with existing router users", false)))
            }
            val need = state.requested - credentials.size
            val start = when (val r = cloud.reserveSequence(spec.profileId, need, capacity)) {
                is SagaResult.Err -> return SequentialOutcome.Stop(failOrSuspend(state, r.error))
                is SagaResult.Ok -> r.value
            }
            val batch = try {
                VoucherCodeGenerator.generateSequential(need, start, need, spec.prefix, spec.codeLength, taken, random, passwordType, spec.passwordLength)
            } catch (e: IllegalStateException) {
                return SequentialOutcome.Stop(StepOutcome.Fail(state, SagaError(SagaError.SEQUENCE_EXHAUSTED, "Sequence exceeds the username length capacity", false)))
            }
            credentials += batch.credentials
            batch.credentials.forEach { taken.add(it.username.lowercase()) }
        }
        return SequentialOutcome.Ok(credentials)
    }

    private suspend fun routerPush(state: RunState): StepOutcome {
        var s = state

        // مصالحة بعد انقطاع: كرت من هذا التنفيذ موجود اسمه على الراوتر يُعلَّم ناجحاً ولا يُدفع مرة ثانية
        // (لا وسم ضمني في تعليق الحساب؛ الأسماء فريدة لأن التوليد يتحقق من عدم التعارض مسبقاً)
        val onRouter = when (val r = router.existingUsernames()) {
            is SagaResult.Err -> return failOrSuspend(s, r.error)
            is SagaResult.Ok -> r.value
        }
        val pendingAtStart = store.staged(s.executionId, VoucherState.PENDING)
        val alreadyThere = pendingAtStart.filter { it.username.lowercase() in onRouter }.map { it.seq }
        if (alreadyThere.isNotEmpty()) store.setVoucherStates(s.executionId, alreadyThere, VoucherState.ROUTER_OK)

        val alreadyThereSet = alreadyThere.toHashSet()
        val pending = pendingAtStart.filter { it.seq !in alreadyThereSet }
        s = refreshCounts(s, total = s.requested)

        for (chunk in pending.chunked(routerChunkSize)) {
            if (store.isCancelRequested(s.executionId)) return StepOutcome.Cancel(s)

            val results = pushChunk(chunk, s.spec.profileName, s.routerComment, s.limits)

            // Created فقط نجاح: AlreadyExists = الاسم لحساب آخر (حسابنا السابق يُعاد Created من المصدر)، وكرت كهذا لا يعمل بكلمة مرورنا
            val ok = results.filter { it.second is CreateOutcome.Created }.map { it.first.seq }
            if (ok.isNotEmpty()) store.setVoucherStates(s.executionId, ok, VoucherState.ROUTER_OK)
            val taken = results.filter { it.second is CreateOutcome.AlreadyExists }.map { it.first.seq }
            if (taken.isNotEmpty()) store.setVoucherStates(s.executionId, taken, VoucherState.ROUTER_FAILED, SagaError.USERNAME_TAKEN)
            results.filter { it.second is CreateOutcome.Rejected }.groupBy { (it.second as CreateOutcome.Rejected).message }.forEach { (msg, items) ->
                store.setVoucherStates(s.executionId, items.map { it.first.seq }, VoucherState.ROUTER_FAILED, msg)
            }
            s = checkpoint(refreshCounts(s, total = s.requested))

            results.map { it.second }.firstOrNull { it is CreateOutcome.Fatal }?.let { return StepOutcome.Fail(s, (it as CreateOutcome.Fatal).error) }
            results.map { it.second }.firstOrNull { it is CreateOutcome.Transient }?.let { return StepOutcome.Suspend(s, (it as CreateOutcome.Transient).error) }
        }

        // تحقق بعد الإنشاء من User Manager نفسه (قراءة دفعية واحدة): كل حساب ROUTER_OK يجب أن يكون موجوداً وبباقته وغير معطَّل.
        // يغطي أيضاً الحسابات التي وُجدت أسماؤها عند المصالحة دون أن نراها تُنشأ. الفاشل يُحذف من الراوتر ويُعدّ فاشلاً (لا يتيم بلا باقة).
        s = verifyPushed(s).let { r -> if (r is StepOutcome.Done) r.state else return r }

        val counts = store.counts(s.executionId)
        if ((counts[VoucherState.PENDING] ?: 0) > 0) {
            // لا يحدث عملياً (كل دفعة إما تُنجز أو تُوقف)، لكنه ضمان ضد حلقة صامتة
            return StepOutcome.Suspend(s, SagaError(SagaError.PENDING_REMAINS, "Vouchers still pending", true))
        }
        val okCount = (counts[VoucherState.ROUTER_OK] ?: 0) + (counts[VoucherState.CLOUD_OK] ?: 0)
        if (okCount == 0) {
            return StepOutcome.Fail(s, SagaError(SagaError.ALL_FAILED, "Router rejected every voucher", false))
        }
        return StepOutcome.Done(s)
    }

    private suspend fun verifyPushed(state: RunState): StepOutcome {
        val pushed = store.staged(state.executionId, VoucherState.ROUTER_OK)
        if (pushed.isEmpty()) return StepOutcome.Done(state)
        val failures = when (val r = router.verifyUsers(pushed.map { it.username }, state.spec.profileName)) {
            is SagaResult.Err -> return failOrSuspend(state, r.error)
            is SagaResult.Ok -> r.value
        }
        // حساب بالاسم نفسه لا يخصّنا (تعليق/كلمة مرور مختلفان، كمصالحة بعد انقطاع صادفت اسماً لمستخدم آخر): كرت لا يعمل ولا يُفوتَر.
        // لا يُحذف من الراوتر أبداً لأنه ليس حسابنا؛ يُعلَّم فاشلاً بـ USERNAME_TAKEN فقط.
        val healthy = pushed.filter { it.username !in failures }
        if (healthy.isNotEmpty()) {
            val foreign = when (val c = router.credentialMismatches(healthy.associate { it.username to it.password }, state.routerComment)) {
                is SagaResult.Err -> return failOrSuspend(state, c.error)
                is SagaResult.Ok -> c.value.map { it.lowercase() }.toHashSet()
            }
            val foreignSeqs = healthy.filter { it.username.lowercase() in foreign }.map { it.seq }
            if (foreignSeqs.isNotEmpty()) {
                store.setVoucherStates(state.executionId, foreignSeqs, VoucherState.ROUTER_FAILED, SagaError.USERNAME_TAKEN)
            }
        }
        if (failures.isEmpty()) return StepOutcome.Done(checkpoint(refreshCounts(state, total = state.requested)))
        val bad = pushed.filter { it.username in failures }
        when (val d = router.deleteUsers(bad.map { it.username })) {
            is SagaResult.Err -> return failOrSuspend(state, d.error)
            is SagaResult.Ok -> Unit
        }
        bad.groupBy { failures.getValue(it.username) }.forEach { (reason, items) ->
            store.setVoucherStates(
                state.executionId, items.map { it.seq }, VoucherState.ROUTER_FAILED,
                "${OperationalSettingsResolver.ERR_USER_ROUTER_UNVERIFIED}: $reason"
            )
        }
        return StepOutcome.Done(checkpoint(refreshCounts(state, total = state.requested)))
    }

    private suspend fun pushChunk(chunk: List<StagedVoucher>, profileName: String, comment: String, limits: UserLimits): List<Pair<StagedVoucher, CreateOutcome>> =
        coroutineScope {
            val gate = Semaphore(routerConcurrency)
            val abort = AtomicBoolean(false)
            chunk.map { voucher ->
                async {
                    gate.withPermit {
                        if (abort.get()) null
                        else {
                            val outcome = router.createUser(voucher.username, voucher.password, profileName, comment, limits)
                            if (outcome is CreateOutcome.Transient || outcome is CreateOutcome.Fatal) abort.set(true)
                            voucher to outcome
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun cloudCommit(state: RunState): StepOutcome {
        var s = state
        // لا تُكمَل دفعة فيها كروت لم تُحسم على الراوتر (لا ناجحة ولا مرفوضة): كانت ستُفوتَر ناقصة وتُعرض كاملة
        if ((store.counts(s.executionId)[VoucherState.PENDING] ?: 0) > 0) {
            return StepOutcome.Fail(s, SagaError(SagaError.PENDING_REMAINS, "Vouchers are still unresolved on the router", false))
        }
        val toCommit = store.staged(s.executionId, VoucherState.ROUTER_OK)
        for (chunk in toCommit.chunked(cloudChunkSize)) {
            when (val r = cloud.commitVouchers(s, chunk)) {
                is SagaResult.Err -> return failOrSuspend(s, r.error)
                is SagaResult.Ok -> store.setVoucherStates(s.executionId, chunk.map { it.seq }, VoucherState.CLOUD_OK)
            }
            s = checkpoint(refreshCounts(s, total = s.requested))
        }
        s = refreshCounts(s, total = s.requested)
        return when (val r = cloud.finalizeBatch(s, "COMPLETED")) {
            is SagaResult.Err -> failOrSuspend(s, r.error)
            is SagaResult.Ok -> StepOutcome.Done(s)
        }
    }

    private suspend fun billing(state: RunState): StepOutcome =
        when (val r = cloud.bill(state)) {
            is SagaResult.Err -> failOrSuspend(state, r.error)
            is SagaResult.Ok -> StepOutcome.Done(state.copy(invoiceId = r.value.invoiceId, totalAmountMinor = r.value.totalAmountMinor))
        }

    // ---------------------------------------------------------------- الإلغاء

    private suspend fun cancel(state: RunState): SagaOutcome {
        var s = state

        // حذف ما دُفع للراوتر (إن كان للدفعة رقم، أي إن بدأ الدفع محتملاً)
        if (s.batchNumber.isNotBlank() && s.step(SagaStep.GENERATE) == StepState.SUCCESS) {
            // دفعات قديمة كانت تحمل وسماً في التعليق
            when (val r = router.deleteByComment(s.routerTag)) {
                is SagaResult.Err -> return cancelFailed(s, r.error)
                is SagaResult.Ok -> Unit
            }
            // الحالي: حذف بأسماء الكروت المحفوظة (ما رفضه الراوتر لم يُنشأ أصلاً)
            val names = store.staged(s.executionId).filter { it.state != VoucherState.ROUTER_FAILED }.map { it.username }
            if (names.isNotEmpty()) {
                when (val r = router.deleteUsers(names)) {
                    is SagaResult.Err -> return cancelFailed(s, r.error)
                    is SagaResult.Ok -> Unit
                }
            }
        }
        if (s.batchId.isNotBlank() && s.step(SagaStep.PREPARE) == StepState.SUCCESS) {
            when (val r = cloud.cancelOrder(s)) {
                is SagaResult.Err -> return cancelFailed(s, r.error)
                is SagaResult.Ok -> Unit
            }
            // فشل تحديث حالة الدفعة لا يُبتلع: الإلغاء لا يُعدّ ناجحاً والدفعة ما زالت RUNNING في السحابة.
            // كل الخطوات السابقة idempotent (حذف بالتعليق، طلب CANCELLED) فتكرارها عند إعادة التشغيل آمن.
            when (val r = cloud.finalizeBatch(s, "CANCELLED")) {
                is SagaResult.Err -> return cancelFailed(s, r.error)
                is SagaResult.Ok -> Unit
            }
        }
        s = refreshCounts(s, total = s.requested)
        checkpoint(s.copy(status = RunStatus.CANCELLED, errorCode = null, errorMessage = null, completedAtMillis = clock()))
        store.setCancelRequested(s.executionId, false)
        return SagaOutcome.Cancelled
    }

    /**
     * تعذّر إكمال الإلغاء: العطل المؤقت ⇒ RETRYING ويُعاد لاحقاً (علم الإلغاء باقٍ فيُستأنف الإلغاء)،
     * والنهائي ⇒ FAILED (لا RETRYING: وإلا أعاد إقلاعُ التطبيق جدولته إلى الأبد بينما الواجهة تراه فاشلاً).
     */
    private suspend fun cancelFailed(state: RunState, error: SagaError): SagaOutcome =
        if (error.retryable) {
            checkpoint(state.copy(status = RunStatus.RETRYING, errorCode = error.code, errorMessage = error.message))
            SagaOutcome.Suspended(error)
        } else {
            checkpoint(
                state.copy(status = RunStatus.FAILED, errorCode = error.code, errorMessage = error.message, completedAtMillis = clock())
            )
            SagaOutcome.Failed(error)
        }

    // ---------------------------------------------------------------- مساعدات

    private suspend fun refreshCounts(state: RunState, total: Int): RunState {
        val c = store.counts(state.executionId)
        val ok = (c[VoucherState.ROUTER_OK] ?: 0) + (c[VoucherState.CLOUD_OK] ?: 0)
        val failed = c[VoucherState.ROUTER_FAILED] ?: 0
        return state.copy(successful = ok, failed = failed, progressDone = ok + failed, progressTotal = total)
    }

    private suspend fun checkpoint(state: RunState): RunState {
        store.save(state)
        cloud.mirror(state)
        return state
    }

    private fun failOrSuspend(state: RunState, error: SagaError): StepOutcome =
        if (error.retryable) StepOutcome.Suspend(state, error) else StepOutcome.Fail(state, error)

    private companion object {
        /** سقف جولات حجز الأرقام عند تعارضات متكررة مع حسابات موجودة (حماية من حلقة لا تنتهي). */
        const val MAX_SEQUENCE_ROUNDS = 20
    }

    private sealed class StepOutcome {
        data class Done(val state: RunState) : StepOutcome()
        data class Suspend(val state: RunState, val error: SagaError) : StepOutcome()
        data class Fail(val state: RunState, val error: SagaError) : StepOutcome()
        data class Cancel(val state: RunState) : StepOutcome()
    }
}
