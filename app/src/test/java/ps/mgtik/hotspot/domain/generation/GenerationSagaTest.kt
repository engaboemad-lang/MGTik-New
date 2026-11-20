package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الساغا بمزيّفات نقية: المسار السعيد، قتل العملية عند **كل** عملية ممكنة ثم الاستئناف، الفشل الجزئي،
 * انقطاع الراوتر، فشل المصادقة، رفض كل الكروت، الإلغاء، الطلب غير الصالح، إعادة التشغيل بعد الاكتمال، المولّد والقواعد.
 */
class GenerationSagaTest {

    class Crash : RuntimeException("simulated process death")

    /** عدّاد عمليات مشترك: عند الوصول للعدد المحدد يُرمى Crash (موت العملية) من داخل أي منفذ. */
    class Chaos { var ops = 0; var crashAt = Int.MAX_VALUE
        fun tick() { ops++; if (ops == crashAt) throw Crash() } }

    class FakeStore(val chaos: Chaos) : RunStore {
        var run: RunState? = null
        val staged = LinkedHashMap<Int, StagedVoucher>()
        override suspend fun load(executionId: String) = run
        override suspend fun save(state: RunState) { chaos.tick(); run = state.copy(cancelRequested = run?.cancelRequested ?: false) }
        override suspend fun setCancelRequested(executionId: String, requested: Boolean) { run = run?.copy(cancelRequested = requested) }
        override suspend fun isCancelRequested(executionId: String) = run?.cancelRequested ?: false
        override suspend fun staged(executionId: String, state: VoucherState?) =
            staged.values.filter { state == null || it.state == state }.sortedBy { it.seq }
        override suspend fun insertStaged(vouchers: List<StagedVoucher>) { chaos.tick(); vouchers.forEach { staged[it.seq] = it } }
        override suspend fun setVoucherStates(executionId: String, seqs: List<Int>, state: VoucherState, error: String?) {
            chaos.tick(); seqs.forEach { staged[it] = staged[it]!!.copy(state = state, error = error) } }
        override suspend fun counts(executionId: String) = staged.values.groupingBy { it.state }.eachCount()
    }

    class FakeRouter(val chaos: Chaos) : RouterPort {
        val users = LinkedHashMap<String, String>()       // username(lower) -> comment
        val createCalls = HashMap<String, Int>()
        val limitsSeen = HashMap<String, UserLimits>()          // username(lower) -> القيود التي وصلت الراوتر
        var rejectPrefix: String? = null                    // أسماء تبدأ بها تُرفض
        var down = false
        var authFail = false
        override suspend fun existingUsernames(): SagaResult<Set<String>> {
            chaos.tick(); if (down) return SagaResult.Err(SagaError("NET", "down", true))
            return SagaResult.Ok(users.keys.toSet()) }
        override suspend fun usernamesWithComment(tag: String): SagaResult<Set<String>> {
            chaos.tick(); if (down) return SagaResult.Err(SagaError("NET", "down", true))
            return SagaResult.Ok(users.filterValues { RouterTags.matches(it, tag) }.keys) }
        override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome {
            chaos.tick()
            limitsSeen[username.lowercase()] = limits
            if (authFail) return CreateOutcome.Fatal(SagaError("AUTH", "401", false))
            if (down) return CreateOutcome.Transient(SagaError("NET", "down", true))
            createCalls.merge(username.lowercase(), 1, Int::plus)
            if (users.containsKey(username.lowercase())) return CreateOutcome.AlreadyExists
            if (rejectPrefix != null && username.startsWith(rejectPrefix!!)) return CreateOutcome.Rejected("rejected")
            users[username.lowercase()] = comment
            return CreateOutcome.Created }
        override suspend fun deleteUsers(usernames: Collection<String>): SagaResult<Int> {
            chaos.tick(); val del = usernames.map { it.lowercase() }.filter { users.containsKey(it) }; del.forEach { users.remove(it) }
            return SagaResult.Ok(del.size) }
        override suspend fun deleteByComment(tag: String): SagaResult<Int> {
            chaos.tick(); val del = users.filterValues { RouterTags.matches(it, tag) }.keys.toList(); del.forEach { users.remove(it) }
            return SagaResult.Ok(del.size) }
    }

    class FakeCloud(val chaos: Chaos) : CloudPort {
        var batchSeq = 0
        var batch: PrepareResult? = null                    // idempotent prepare: نفس المعرّفات دائماً
        var profileLimits = UserLimits.NONE                 // ما يقرؤه PREPARE من إعدادات البروفايل (لقطة تُجمَّد في أول prepare)
        val vouchers = HashMap<Int, StagedVoucher>()
        var invoices = HashMap<String, Long>()              // invoiceId -> total
        var batchStatus = "PENDING"; var orderCancelled = false
        var sequenceLast = 0L                               // العدّاد الدائم للبروفايل (profile_defaults.last_assigned_sequence)
        val reservations = ArrayList<LongRange>()
        override suspend fun prepare(state: RunState): SagaResult<PrepareResult> {
            chaos.tick()
            if (batch == null) { batchSeq++; batch = PrepareResult("ORD-1", "BATCH-1", "B-%04d".format(batchSeq), 500L, "Owner", profileLimits) }
            return SagaResult.Ok(batch!!) }
        override suspend fun reserveSequence(profileId: String, count: Int, maxValue: Long): SagaResult<Long> {
            chaos.tick()
            if (sequenceLast + count > maxValue) return SagaResult.Err(SagaError(SagaError.SEQUENCE_EXHAUSTED, "exhausted", false))
            val start = sequenceLast + 1; sequenceLast += count; reservations += start..(start + count - 1)
            return SagaResult.Ok(start) }
        override suspend fun commitVouchers(state: RunState, vouchers: List<StagedVoucher>): SagaResult<Unit> {
            chaos.tick(); vouchers.forEach { this.vouchers[it.seq] = it }; return SagaResult.Ok(Unit) }
        override suspend fun finalizeBatch(state: RunState, status: String): SagaResult<Unit> { chaos.tick(); batchStatus = status; return SagaResult.Ok(Unit) }
        override suspend fun bill(state: RunState): SagaResult<BillingResult> {
            chaos.tick(); val id = "INV-" + state.orderId
            invoices[id] = state.successful * state.pricePerVoucherMinor   // نفس المعرّف ⇒ لا تكرار
            return SagaResult.Ok(BillingResult(id, invoices[id]!!)) }
        override suspend fun cancelOrder(state: RunState): SagaResult<Unit> { chaos.tick(); orderCancelled = true; return SagaResult.Ok(Unit) }
        override suspend fun mirror(state: RunState) { /* best-effort: لا tick كي لا يُحسب خارج العمليات الحقيقية */ }
    }



    private fun check(name: String, cond: Boolean) = assertTrue(name, cond)

    private fun newRun(count: Int, format: String = "USER_PASS_SAME", len: Int = 6) = RunState(
        executionId = "EXEC-1", idempotencyKey = "k1", orderId = "ORD-1", batchId = "BATCH-1",
        spec = GenerationSpec("P1", "Gold", "O1", count, format, "XN-", len), status = RunStatus.QUEUED
    )

    @Test
    fun happy_path_completes_and_bills_once() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(120)
        val out = GenerationSaga(st, rt, cl, java.util.Random(1)).run("EXEC-1")
        check("happy: completed", out is SagaOutcome.Completed && !(out as SagaOutcome.Completed).partial)
        check("happy: 120 users on router", rt.users.size == 120)
        check("happy: no implicit comment", rt.users.values.all { it.isEmpty() })
        check("happy: 120 cloud vouchers", cl.vouchers.size == 120)
        check("happy: invoice total", cl.invoices["INV-ORD-1"] == 120L * 500)
        check("happy: status COMPLETED", st.run!!.status == RunStatus.COMPLETED && st.run!!.successful == 120)
        check("happy: unique names", st.staged.values.map { it.username.lowercase() }.toSet().size == 120)
    }
    }

    @Test
    fun killed_at_every_operation_resumes_without_duplicates() = runBlocking<Unit> {    run {
        val probe = Chaos(); val s0 = FakeStore(probe); s0.run = newRun(60)
        GenerationSaga(s0, FakeRouter(probe), FakeCloud(probe), java.util.Random(7), routerChunkSize = 20, cloudChunkSize = 25).run("EXEC-1")
        val totalOps = probe.ops
        var crashesTested = 0
        for (k in 1..totalOps) {
            val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
            st.run = newRun(60); c.crashAt = k
            try { GenerationSaga(st, rt, cl, java.util.Random(7), routerChunkSize = 20, cloudChunkSize = 25).run("EXEC-1") } catch (e: Crash) { crashesTested++ }
            c.crashAt = Int.MAX_VALUE
            // الاستئناف بنسخة ساغا جديدة (عملية جديدة) على نفس المخازن
            val out = GenerationSaga(st, rt, cl, java.util.Random(99), routerChunkSize = 20, cloudChunkSize = 25).run("EXEC-1")
            check("crash@$k: completed", out is SagaOutcome.Completed)
            check("crash@$k: 60 users", rt.users.size == 60)
            check("crash@$k: no double-create", rt.createCalls.values.all { it == 1 })
            check("crash@$k: 60 vouchers", cl.vouchers.size == 60)
            check("crash@$k: one invoice with right total", cl.invoices.size == 1 && cl.invoices["INV-ORD-1"] == 60L * 500)
            check("crash@$k: one batch number", cl.batchSeq == 1)
            check("crash@$k: staged all CLOUD_OK", st.staged.values.all { it.state == VoucherState.CLOUD_OK })
        }
    }
    }

    @Test
    fun partial_failure_bills_only_successful_vouchers() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(100)
        // نرفض كل اسم ينتهي بالرقم 7 (تقريباً 10%)
        val badRouter = object : RouterPort by rt {
            override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome =
                if (username.endsWith("7")) CreateOutcome.Rejected("dup") else rt.createUser(username, password, profileName, comment, limits)
        }
        val out = GenerationSaga(st, badRouter, cl, java.util.Random(3)).run("EXEC-1")
        val failed = st.staged.values.count { it.state == VoucherState.ROUTER_FAILED }
        check("partial: outcome partial", out is SagaOutcome.Completed && (out as SagaOutcome.Completed).partial)
        check("partial: failed>0", failed > 0)
        check("partial: counts add up", st.run!!.successful + st.run!!.failed == 100 && st.run!!.failed == failed)
        check("partial: billed only successful", cl.invoices["INV-ORD-1"] == st.run!!.successful * 500L)
        check("partial: only successful committed", cl.vouchers.size == st.run!!.successful)
    }
    }

    @Test
    fun router_not_ready_blocks_generation_before_any_order_or_user() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(10)
        val notReady = object : RouterPort by rt {
            override suspend fun failedReadinessChecks(): SagaResult<List<String>> = SagaResult.Ok(listOf("radius", "use-radius"))
        }
        val out = GenerationSaga(st, notReady, cl, java.util.Random(1)).run("EXEC-1")
        check("notready: failed not suspended", out is SagaOutcome.Failed)
        check("notready: code", st.run!!.errorCode == GenerationRules.ERR_ROUTER_NOT_READY)
        check("notready: no users created", rt.users.isEmpty())
        check("notready: nothing billed", cl.invoices.isEmpty())
    }
    }

    @Test
    fun router_outage_suspends_then_resumes() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(80)
        rt.down = true
        val saga = GenerationSaga(st, rt, cl, java.util.Random(5), routerChunkSize = 20)
        val o1 = saga.run("EXEC-1")
        check("down: suspended", o1 is SagaOutcome.Suspended)
        check("down: status RETRYING", st.run!!.status == RunStatus.RETRYING)
        check("down: nothing billed", cl.invoices.isEmpty())
        rt.down = false
        val o2 = saga.run("EXEC-1")
        check("down→up: completed", o2 is SagaOutcome.Completed)
        check("down→up: 80 users, billed once", rt.users.size == 80 && cl.invoices.size == 1)
    }
    }

    @Test
    fun router_auth_failure_fails_without_billing_and_retry_works() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(30); rt.authFail = true
        val o = GenerationSaga(st, rt, cl, java.util.Random(5)).run("EXEC-1")
        check("auth: failed", o is SagaOutcome.Failed && (o as SagaOutcome.Failed).error.code == "AUTH")
        check("auth: status FAILED, nothing billed", st.run!!.status == RunStatus.FAILED && cl.invoices.isEmpty())
        check("auth: failed step recorded", st.run!!.step(SagaStep.ROUTER_PUSH) == StepState.FAILED)
        // إعادة المحاولة اليدوية بعد إصلاح الراوتر
        rt.authFail = false
        st.run = st.run!!.copy(status = RunStatus.RETRYING, errorCode = null, attempt = 2).withStep(SagaStep.ROUTER_PUSH, StepState.PENDING)
        val o2 = GenerationSaga(st, rt, cl, java.util.Random(5)).run("EXEC-1")
        check("auth retry: completed", o2 is SagaOutcome.Completed && rt.users.size == 30 && cl.invoices.size == 1)
    }
    }

    @Test
    fun router_rejecting_everything_fails_without_billing() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(20); rt.rejectPrefix = "XN-"
        val o = GenerationSaga(st, rt, cl, java.util.Random(2)).run("EXEC-1")
        check("allfail: failed code", o is SagaOutcome.Failed && (o as SagaOutcome.Failed).error.code == SagaError.ALL_FAILED)
        check("allfail: no invoice, no router users", cl.invoices.isEmpty() && rt.users.isEmpty())
    }
    }

    @Test
    fun cancel_during_push_cleans_router_and_cancels_order() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(100)
        // بعد أول دفعة نطلب الإلغاء (يحاكي ضغط المستخدم)
        val canceling = object : RouterPort by rt {
            var n = 0
            override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome {
                val r = rt.createUser(username, password, profileName, comment, limits)
                if (++n == 10) st.setCancelRequested("EXEC-1", true)
                return r }
        }
        val o = GenerationSaga(st, canceling, cl, java.util.Random(4), routerChunkSize = 10, routerConcurrency = 1).run("EXEC-1")
        check("cancel: outcome", o is SagaOutcome.Cancelled)
        check("cancel: router cleaned", rt.users.isEmpty())
        check("cancel: order cancelled + batch CANCELLED", cl.orderCancelled && cl.batchStatus == "CANCELLED")
        check("cancel: status CANCELLED, not billed", st.run!!.status == RunStatus.CANCELLED && cl.invoices.isEmpty())
        // إعادة التشغيل بعد الإلغاء idempotent
        check("cancel: rerun stays cancelled", GenerationSaga(st, rt, cl).run("EXEC-1") is SagaOutcome.Cancelled)
    }
    }

    @Test
    fun cancel_flag_survives_saga_checkpoints() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); st.run = newRun(10)
        st.setCancelRequested("EXEC-1", true)
        st.save(st.run!!.copy(status = RunStatus.RUNNING))
        check("cancel flag survives save", st.isCancelRequested("EXEC-1"))
    }
    }

    @Test
    fun invalid_owner_has_no_side_effects() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(10).let { it.copy(spec = it.spec.copy(ownerId = "")) }
        val o = GenerationSaga(st, rt, cl).run("EXEC-1")
        check("invalid owner: failed", o is SagaOutcome.Failed && (o as SagaOutcome.Failed).error.code == GenerationRules.ERR_OWNER_REQUIRED)
        check("invalid owner: no side effects", cl.batch == null && rt.users.isEmpty())
    }
    }

    @Test
    fun rerun_after_completion_is_idempotent() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(40)
        val saga = GenerationSaga(st, rt, cl, java.util.Random(11))
        saga.run("EXEC-1"); val ops = c.ops
        val again = saga.run("EXEC-1")
        check("rerun completed: same result, no ops", again is SagaOutcome.Completed && c.ops == ops && cl.invoices.size == 1)
    }
    }

    @Test
    fun generator_and_rules() = runBlocking<Unit> {    run {
        val g = VoucherCodeGenerator.generate(2000, CodeFormat.NUMERIC_ONLY, "A-", 5, setOf("a-00001"), java.util.Random(1))
        check("gen: unique", g.map { it.username.lowercase() }.toSet().size == 2000)
        check("gen: avoids taken", g.none { it.username.equals("a-00001", true) })
        check("gen: format", g.all { it.username.startsWith("A-") && it.username.length == 7 })
        val up = VoucherCodeGenerator.generate(300, CodeFormat.UPPERCASE_ONLY, "", 5, emptySet(), java.util.Random(2))
        check("gen: uppercase only, no lookalikes", up.all { u -> u.username.all { it in 'A'..'Z' && it != 'O' && it != 'I' } })
        val same = VoucherCodeGenerator.generate(50, CodeFormat.USER_PASS_SAME, "", 6, emptySet(), java.util.Random(3))
        check("gen: user=pass", same.all { it.username == it.password })
        val sep = VoucherCodeGenerator.generate(50, CodeFormat.ALPHANUMERIC, "", 6, emptySet(), java.util.Random(3))
        check("gen: separate password length", sep.all { it.password.length == 6 && it.password != it.username })
        var threw = false
        try { VoucherCodeGenerator.generate(20, CodeFormat.NUMERIC_ONLY, "", 1, emptySet(), java.util.Random(1)) } catch (e: IllegalStateException) { threw = true }
        check("gen: exhausted keyspace throws", threw)

        fun v(count: Int = 100, len: Int = 6, prefix: String = "X", owner: String? = "O", fmt: String = "numeric_only", profile: String = "P") =
            GenerationRules.validate(profile, owner, count, fmt, prefix, len)
        check("rules: ok", v().isEmpty())
        check("rules: owner required", v(owner = null)[GenerationRules.Field.OWNER] == GenerationRules.ERR_OWNER_REQUIRED && v(owner = "  ").isNotEmpty())
        check("rules: count 0/neg", v(count = 0).containsKey("count") && v(count = -3).containsKey("count"))
        check("rules: count limit", v(count = 5001)["count"] == GenerationRules.ERR_COUNT_LIMIT_EXCEEDED && v(count = 5000).isEmpty())
        check("rules: length bounds", v(len = 3).containsKey("codeLength") && v(len = 13).containsKey("codeLength") && v(len = 4, count = 100).isEmpty())
        check("rules: prefix", v(prefix = "bad prefix").containsKey("prefix") && v(prefix = "عربي").containsKey("prefix") && v(prefix = "123456789").containsKey("prefix"))
        check("rules: format", v(fmt = "weird")["format"] == GenerationRules.ERR_FORMAT_INVALID && v(fmt = "USER_PASS_SAME").isEmpty())
        check("rules: max count fits min length", v(count = 5000, len = 4).isEmpty())
        check("rules: profile required", v(profile = "").containsKey("profileId"))
    }
    }

    @Test
    fun completion_time_is_recorded_and_cleared_on_resume() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = newRun(10)
        GenerationSaga(st, rt, cl, java.util.Random(1), clock = { 1234L }).run("EXEC-1")
        check("completedAt set on COMPLETED", st.run!!.completedAtMillis == 1234L)
        val failing = FakeStore(c); failing.run = newRun(10).copy(completedAtMillis = 99L, status = RunStatus.FAILED)
        val down = FakeRouter(c).also { it.down = true }
        GenerationSaga(failing, down, FakeCloud(c), java.util.Random(1), clock = { 5L }).run("EXEC-1")
        check("completedAt cleared when run restarts", failing.run!!.completedAtMillis == null)
    }
    }

    private fun cancellingRun() = newRun(10).withStep(SagaStep.PREPARE, StepState.SUCCESS)
        .copy(batchNumber = "B-1", cancelRequested = true, status = RunStatus.RETRYING)

    @Test
    fun cancel_with_failing_batch_finalize_is_not_reported_as_cancelled() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = cancellingRun()
        val transient = object : CloudPort by cl {
            override suspend fun finalizeBatch(state: RunState, status: String): SagaResult<Unit> =
                SagaResult.Err(SagaError("NET", "down", true))
        }
        val o = GenerationSaga(st, rt, transient).run("EXEC-1")
        check("cancel finalize transient: suspended", o is SagaOutcome.Suspended)
        check("cancel finalize transient: RETRYING, flag kept", st.run!!.status == RunStatus.RETRYING && st.isCancelRequested("EXEC-1"))
        // الإعادة بعد عودة السحابة تُتمّ الإلغاء
        check("cancel resumes", GenerationSaga(st, rt, cl).run("EXEC-1") is SagaOutcome.Cancelled && cl.batchStatus == "CANCELLED")
    }
    }

    @Test
    fun cancel_with_permanent_failure_ends_failed_not_retrying() = runBlocking<Unit> {    run {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = cancellingRun()
        val permanent = object : CloudPort by cl {
            override suspend fun cancelOrder(state: RunState): SagaResult<Unit> =
                SagaResult.Err(SagaError("DENIED", "no", false))
        }
        val o = GenerationSaga(st, rt, permanent, clock = { 77L }).run("EXEC-1")
        check("cancel permanent: failed", o is SagaOutcome.Failed)
        check("cancel permanent: FAILED (not auto-resumed)", st.run!!.status == RunStatus.FAILED && st.run!!.completedAtMillis == 77L)
    }
    }

    @Test
    fun limits_reach_every_router_user_and_unspecified_stays_null() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        cl.profileLimits = UserLimits(uptimeSeconds = 86_400L, transferBytes = 5L * 1_073_741_824L)
        st.run = newRun(30)
        val out = GenerationSaga(st, rt, cl, java.util.Random(11)).run("EXEC-1")
        check("limits: completed", out is SagaOutcome.Completed)
        check("limits: all 30 users got both limits", rt.limitsSeen.size == 30 &&
            rt.limitsSeen.values.all { it.uptimeSeconds == 86_400L && it.transferBytes == 5L * 1_073_741_824L })
        check("limits: stored in run snapshot", st.run!!.limits == UserLimits(86_400L, 5L * 1_073_741_824L))

        // بروفايل بلا قيود: null (لا صفر) لكلا القيدين
        val c2 = Chaos(); val st2 = FakeStore(c2); val rt2 = FakeRouter(c2); val cl2 = FakeCloud(c2)
        st2.run = newRun(10)
        GenerationSaga(st2, rt2, cl2, java.util.Random(12)).run("EXEC-1")
        check("limits: unspecified stays null", rt2.limitsSeen.values.all { it.uptimeSeconds == null && it.transferBytes == null })

        // قيد واحد فقط
        val c3 = Chaos(); val st3 = FakeStore(c3); val rt3 = FakeRouter(c3); val cl3 = FakeCloud(c3)
        cl3.profileLimits = UserLimits(uptimeSeconds = null, transferBytes = 1_048_576L)
        st3.run = newRun(5)
        GenerationSaga(st3, rt3, cl3, java.util.Random(13)).run("EXEC-1")
        check("limits: only transfer", rt3.limitsSeen.values.all { it.uptimeSeconds == null && it.transferBytes == 1_048_576L })
    }

    @Test
    fun limits_snapshot_survives_crash_and_profile_edit_between_attempts() = runBlocking<Unit> {
        val probe = Chaos(); val s0 = FakeStore(probe); val cl0 = FakeCloud(probe)
        cl0.profileLimits = UserLimits(3_600L, 1_048_576L); s0.run = newRun(40)
        GenerationSaga(s0, FakeRouter(probe), cl0, java.util.Random(7), routerChunkSize = 10, cloudChunkSize = 10).run("EXEC-1")
        val totalOps = probe.ops
        for (k in 1..totalOps step 3) {
            val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
            cl.profileLimits = UserLimits(3_600L, 1_048_576L); st.run = newRun(40); c.crashAt = k
            try { GenerationSaga(st, rt, cl, java.util.Random(7), routerChunkSize = 10, cloudChunkSize = 10).run("EXEC-1") } catch (e: Crash) { }
            c.crashAt = Int.MAX_VALUE
            // «المدير عدّل البروفايل» بعد الانقطاع: القيود المحسومة سابقاً هي المعتمدة (batch محفوظ)
            if (cl.batch != null) cl.profileLimits = UserLimits(999L, 999L)
            GenerationSaga(st, rt, cl, java.util.Random(99), routerChunkSize = 10, cloudChunkSize = 10).run("EXEC-1")
            check("crash@$k: every user has the original limits", rt.limitsSeen.size == 40 &&
                rt.limitsSeen.values.all { it.uptimeSeconds == 3_600L && it.transferBytes == 1_048_576L })
        }
    }
}
