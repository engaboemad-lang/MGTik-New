package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.SystemClass

/**
 * تكوين التوليد: الأسماء (تسلسلي/عشوائي/بادئة/طول/عدد)، العدّادات واستمراريتها، منع التكرار، كلمات المرور،
 * وصول كل اختيار لمنفذ الراوتر والسحابة، المدخلات غير الصالحة، الإعدادات الافتراضية، الأعداد الكبيرة.
 * الساغا الحقيقية فوق مزيّفات (RunStore من GenerationSagaTest) بعدّادات لكل باقة تماماً كالسحابة.
 */
class GenerationConfigTest {

    // ------------------------------------------------------------------ مزيّفات تلتقط ما يصل فعلاً

    class SeenUser(val password: String, val profile: String, val comment: String)

    class Router(val chaos: GenerationSagaTest.Chaos, val preexisting: Set<String> = emptySet()) : RouterPort {
        val users = LinkedHashMap<String, SeenUser>() // username(lower) -> ما وصل
        var takenOnCreate: Set<String> = emptySet()    // أسماء «تُسرَق» لحساب آخر لحظة الإنشاء (سباق)
        var rejectAll = false
        var notReady = false
        var foreignOnRouter: Set<String> = emptySet()  // حسابات بهذه الأسماء على الراوتر لا تخصّنا (كلمة مرور/تعليق مختلفان)
        var credentialChecks = 0
        override suspend fun existingUsernames(): SagaResult<Set<String>> = SagaResult.Ok(users.keys + preexisting.map { it.lowercase() })
        override suspend fun usernamesWithComment(tag: String): SagaResult<Set<String>> = SagaResult.Ok(emptySet())
        override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome {
            if (username.lowercase() in takenOnCreate) return CreateOutcome.AlreadyExists
            if (rejectAll) return CreateOutcome.Rejected("rejected")
            users[username.lowercase()] = SeenUser(password, profileName, comment)
            return CreateOutcome.Created
        }
        override suspend fun credentialMismatches(expected: Map<String, String>, comment: String): SagaResult<Set<String>> {
            credentialChecks++
            return SagaResult.Ok(expected.keys.filter { it.lowercase() in foreignOnRouter }.toSet())
        }
        override suspend fun failedReadinessChecks(): SagaResult<List<String>> = SagaResult.Ok(if (notReady) listOf("radius") else emptyList())
        override suspend fun deleteUsers(usernames: Collection<String>): SagaResult<Int> {
            val d = usernames.map { it.lowercase() }.filter { users.remove(it) != null }; return SagaResult.Ok(d.size)
        }
        override suspend fun deleteByComment(tag: String): SagaResult<Int> = SagaResult.Ok(0)
    }

    /** سحابة بعدّاد دائم لكل باقة (profile_defaults) تُشارَك بين عمليات متتالية كما في Firestore. */
    class Cloud(val counters: HashMap<String, Long> = HashMap(), val routerName: String? = null) : CloudPort {
        val committed = ArrayList<StagedVoucher>()
        val invoices = HashMap<String, Long>()
        var prepareCalls = 0
        override suspend fun prepare(state: RunState): SagaResult<PrepareResult> {
            prepareCalls++
            return SagaResult.Ok(PrepareResult(state.orderId, state.batchId, "B-0001", 500L, "Owner", profileName = routerName))
        }
        override suspend fun reserveSequence(profileId: String, count: Int, maxValue: Long): SagaResult<Long> {
            val last = counters[profileId] ?: 0L
            if (last + count > maxValue) return SagaResult.Err(SagaError(SagaError.SEQUENCE_EXHAUSTED, "exhausted", false))
            counters[profileId] = last + count
            return SagaResult.Ok(last + 1)
        }
        override suspend fun commitVouchers(state: RunState, vouchers: List<StagedVoucher>): SagaResult<Unit> { committed += vouchers; return SagaResult.Ok(Unit) }
        override suspend fun finalizeBatch(state: RunState, status: String): SagaResult<Unit> = SagaResult.Ok(Unit)
        override suspend fun bill(state: RunState): SagaResult<BillingResult> {
            val id = "INV-" + state.orderId; invoices[id] = state.successful * state.pricePerVoucherMinor
            return SagaResult.Ok(BillingResult(id, invoices[id]!!))
        }
        override suspend fun cancelOrder(state: RunState): SagaResult<Unit> = SagaResult.Ok(Unit)
        override suspend fun mirror(state: RunState) {}
    }

    private fun spec(
        count: Int = 10, profileId: String = "P1", profileName: String = "Gold", prefix: String = "", len: Int = 6,
        format: String = "NUMERIC_ONLY", mode: String = "RANDOM", pwType: String = "NUMERIC_ONLY", pwLen: Int = 6, comment: String = ""
    ) = GenerationSpec(profileId, profileName, "O1", count, format, prefix, len, mode, pwType, pwLen, comment)

    private fun run0(spec: GenerationSpec, id: String = "EXEC-1") = RunState(
        executionId = id, idempotencyKey = "k-$id", orderId = "ORD-$id", batchId = "BATCH-$id", spec = spec, status = RunStatus.QUEUED
    )


    private fun go(spec: GenerationSpec, router: Router, cloud: Cloud, seed: Long = 5): Pair<SagaOutcome, GenerationSagaTest.FakeStore> {
        val store = GenerationSagaTest.FakeStore(router.chaos); store.run = run0(spec)
        val out = runBlocking { GenerationSaga(store, router, cloud, java.util.Random(seed), routerChunkSize = 200).run("EXEC-1") }
        return out to store
    }
    private fun chaos() = GenerationSagaTest.Chaos()
    private fun names(store: GenerationSagaTest.FakeStore) = store.staged.values.sortedBy { it.seq }.map { it.username }

    // ------------------------------------------------------------------ التسلسلي والعدّادات

    @Test
    fun sequential_continues_across_runs_with_padding_and_prefix() {
        val cloud = Cloud(); val c = chaos()
        val (o1, s1) = go(spec(count = 3, prefix = "AB-", len = 5, mode = "SEQUENTIAL"), Router(c), cloud)
        assertTrue(o1 is SagaOutcome.Completed)
        assertEquals(listOf("AB-00001", "AB-00002", "AB-00003"), names(s1))
        val (_, s2) = go(spec(count = 2, prefix = "AB-", len = 5, mode = "SEQUENTIAL"), Router(c), cloud)
        assertEquals("استمرار التسلسل بين عمليتين", listOf("AB-00004", "AB-00005"), names(s2))
        assertEquals(5L, cloud.counters["P1"])
    }

    @Test
    fun sequential_counter_is_per_profile_and_independent_of_prefix() {
        val cloud = Cloud(); val c = chaos()
        go(spec(count = 3, profileId = "P1", mode = "SEQUENTIAL"), Router(c), cloud)
        val (_, other) = go(spec(count = 2, profileId = "P2", mode = "SEQUENTIAL"), Router(c), cloud)
        assertEquals("باقة أخرى تبدأ من 1", listOf("000001", "000002"), names(other))
        // السياسة المعتمدة: عدّاد واحد للباقة بصرف النظر عن البادئة
        val (_, again) = go(spec(count = 1, profileId = "P1", prefix = "Z", mode = "SEQUENTIAL"), Router(c), cloud)
        assertEquals(listOf("Z000004"), names(again))
    }

    @Test
    fun sequential_skips_existing_router_names_case_insensitively_and_burns_them() {
        val cloud = Cloud()
        val router = Router(chaos(), preexisting = setOf("x-000002", "X-000003"))
        val (_, st) = go(spec(count = 3, prefix = "x-", mode = "SEQUENTIAL"), router, cloud)
        assertEquals(listOf("x-000001", "x-000004", "x-000005"), names(st))
        assertEquals("الأرقام المتخطّاة لا تُعاد", 5L, cloud.counters["P1"])
        assertTrue(router.users.keys.containsAll(listOf("x-000001", "x-000004", "x-000005")))
    }

    @Test
    fun sequential_exhaustion_fails_cleanly_without_touching_counter_or_router() {
        val cloud = Cloud(hashMapOf("P1" to 99_998L)); val router = Router(chaos())
        val (out, st) = go(spec(count = 5, len = 5, mode = "SEQUENTIAL"), router, cloud)
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(SagaError.SEQUENCE_EXHAUSTED, (out as SagaOutcome.Failed).error.code)
        assertEquals(99_998L, cloud.counters["P1"])
        assertTrue(router.users.isEmpty() && st.staged.isEmpty())
        assertTrue("لا فوترة", cloud.invoices.isEmpty())
    }

    @Test
    fun failure_before_generation_reserves_no_numbers() {
        val cloud = Cloud()
        val notReady = Router(chaos()).also { it.notReady = true }
        val (o1, _) = go(spec(count = 5, mode = "SEQUENTIAL"), notReady, cloud)
        assertTrue(o1 is SagaOutcome.Failed)
        assertNull("راوتر غير جاهز ⇒ لا حجز", cloud.counters["P1"])
        val (o2, _) = go(spec(count = 5, profileName = "", mode = "SEQUENTIAL"), Router(chaos()), cloud)
        assertTrue(o2 is SagaOutcome.Failed)
        assertEquals(SagaError.PROFILE_UNAVAILABLE, (o2 as SagaOutcome.Failed).error.code)
        assertNull("باقة بلا اسم راوتر ⇒ لا حجز", cloud.counters["P1"])
        val (o3, _) = go(spec(count = 0, mode = "SEQUENTIAL"), Router(chaos()), cloud)
        assertTrue(o3 is SagaOutcome.Failed)
        assertNull(cloud.counters["P1"])
    }

    @Test
    fun retry_after_router_failure_reuses_same_names_and_reserves_nothing_new() {
        val cloud = Cloud(); val c = chaos(); val router = Router(c).also { it.rejectAll = true }
        val store = GenerationSagaTest.FakeStore(c); store.run = run0(spec(count = 4, mode = "SEQUENTIAL"))
        val saga = GenerationSaga(store, router, cloud, java.util.Random(1))
        val out = runBlocking { saga.run("EXEC-1") }
        assertTrue(out is SagaOutcome.Failed)
        val firstNames = names(store); assertEquals(4L, cloud.counters["P1"])
        // ما يفعله المستودع في retryExecution: الكروت المرفوضة تعود PENDING والخطوات الفاشلة PENDING
        store.staged.replaceAll { _, v -> v.copy(state = VoucherState.PENDING, error = null) }
        store.run = store.run!!.copy(status = RunStatus.QUEUED, steps = store.run!!.steps.mapValues { (_, s) -> if (s == StepState.FAILED) StepState.PENDING else s })
        router.rejectAll = false
        val again = runBlocking { saga.run("EXEC-1") }
        assertTrue(again is SagaOutcome.Completed)
        assertEquals("نفس الأسماء", firstNames, names(store))
        assertEquals("لا حجز جديد عند إعادة المحاولة", 4L, cloud.counters["P1"])
        assertEquals(4, router.users.size)
    }

    // ------------------------------------------------------------------ العشوائي: مطابقة الإعدادات فعلاً

    private fun charsetOf(f: CodeFormat) = VoucherCodeGenerator.charset(f)

    @Test
    fun random_names_match_prefix_length_format_and_count_exactly() {
        for (format in listOf(CodeFormat.NUMERIC_ONLY, CodeFormat.ALPHANUMERIC, CodeFormat.UPPERCASE_ONLY)) for (len in listOf(4, 6, 12)) for (prefix in listOf("", "WiFi_", "a-")) {
            val (out, st) = go(spec(count = 300, prefix = prefix, len = len, format = format.name), Router(chaos()), Cloud())
            assertTrue("$format/$len/$prefix", out is SagaOutcome.Completed)
            val n = names(st)
            assertEquals(300, n.size)
            val allowed = charsetOf(format).toSet()
            n.forEach {
                assertTrue("$it prefix", it.startsWith(prefix))
                assertEquals("$it length", prefix.length + len, it.length)
                assertTrue("$it charset", it.removePrefix(prefix).all { ch -> ch in allowed })
            }
            assertEquals("فريدة", 300, n.map { it.lowercase() }.toSet().size)
        }
    }

    @Test
    fun random_never_collides_with_existing_names_or_other_runs() {
        val existing = (0 until 9000).map { "%04d".format(it) }.toSet() // 9000 من 10000 مأخوذ
        val router = Router(chaos(), preexisting = existing)
        val (out, st) = go(spec(count = 500, len = 4, format = "NUMERIC_ONLY"), router, Cloud())
        assertTrue(out is SagaOutcome.Completed)
        assertTrue(names(st).none { it in existing })
        // عملية ثانية على الراوتر نفسه (أسماء العملية الأولى صارت موجودة)
        val first = names(st).toSet()
        val (out2, st2) = go(spec(count = 400, len = 4, format = "NUMERIC_ONLY"), router, Cloud(), seed = 77)
        assertTrue(out2 is SagaOutcome.Completed)
        assertTrue(names(st2).none { it in existing || it in first })
    }

    @Test
    fun random_exhausted_keyspace_fails_with_clear_error_and_no_side_effects() {
        val router = Router(chaos(), preexisting = (0 until 10000).map { "%04d".format(it) }.toSet())
        val (out, st) = go(spec(count = 5, len = 4), router, Cloud())
        assertTrue(out is SagaOutcome.Failed)
        assertEquals(SagaError.KEYSPACE_EXHAUSTED, (out as SagaOutcome.Failed).error.code)
        assertTrue(st.staged.isEmpty() && router.users.isEmpty())
    }

    // ------------------------------------------------------------------ كلمات المرور

    @Test
    fun password_types_length_and_charset_reach_router_and_cloud() {
        for ((type, allowed) in listOf(
            "NUMERIC_ONLY" to VoucherCodeGenerator.passwordCharset(PasswordType.NUMERIC_ONLY),
            "LETTERS_ONLY" to VoucherCodeGenerator.passwordCharset(PasswordType.LETTERS_ONLY),
            "ALPHANUMERIC" to VoucherCodeGenerator.passwordCharset(PasswordType.ALPHANUMERIC),
            "MIXED_CASE_ALNUM" to VoucherCodeGenerator.passwordCharset(PasswordType.MIXED_CASE_ALNUM)
        )) for (len in listOf(4, 9, 16)) {
            val router = Router(chaos()); val cloud = Cloud()
            val (out, st) = go(spec(count = 80, pwType = type, pwLen = len), router, cloud)
            assertTrue("$type/$len", out is SagaOutcome.Completed)
            st.staged.values.forEach { v ->
                assertEquals("طول $type", len, v.password.length)
                assertTrue("أحرف $type", v.password.all { it in allowed })
                assertEquals("كلمة المرور المحفوظة هي المرسلة للراوتر", v.password, router.users[v.username.lowercase()]!!.password)
            }
            assertEquals("وللسحابة", st.staged.values.map { it.password }.toSet(), cloud.committed.map { it.password }.toSet())
        }
    }

    @Test
    fun password_same_as_username_and_sequential_passwords() {
        val router = Router(chaos())
        val (_, st) = go(spec(count = 20, pwType = "SAME_AS_USERNAME", pwLen = 0), router, Cloud())
        st.staged.values.forEach { assertEquals(it.username, it.password); assertEquals(it.username, router.users[it.username.lowercase()]!!.password) }
        // تسلسلي مع كلمة مرور عشوائية رقمية بطول محدد
        val (_, seq) = go(spec(count = 20, mode = "SEQUENTIAL", pwType = "NUMERIC_ONLY", pwLen = 8), Router(chaos()), Cloud())
        assertTrue(seq.staged.values.all { it.password.length == 8 && it.password.all(Char::isDigit) })
    }

    @Test
    fun legacy_format_user_pass_same_still_works_without_password_type() {
        val (_, st) = go(spec(count = 10, format = "USER_PASS_SAME", pwType = "", pwLen = 0), Router(chaos()), Cloud())
        assertTrue(st.staged.values.all { it.username == it.password })
    }

    // ------------------------------------------------------------------ التعليق والباقة والاختيارات تصل للراوتر

    @Test
    fun comment_profile_and_every_choice_reach_router_unchanged() {
        val router = Router(chaos())
        val (out, st) = go(spec(count = 25, profileName = "Gold", prefix = "G", len = 7, comment = "  حملة رمضان  "), router, Cloud())
        assertTrue(out is SagaOutcome.Completed)
        assertEquals(25, router.users.size)
        assertTrue(router.users.values.all { it.comment == "حملة رمضان" && it.profile == "Gold" })
        assertTrue(st.staged.values.all { it.username.startsWith("G") && it.username.length == 8 })
        // تعليق فارغ ⇒ لا تعليق ضمني
        val r2 = Router(chaos()); go(spec(count = 3, comment = ""), r2, Cloud())
        assertTrue(r2.users.values.all { it.comment.isEmpty() })
    }

    @Test
    fun router_profile_name_comes_from_cloud_not_stale_request() {
        val router = Router(chaos()); val cloud = Cloud(routerName = "Gold-Renamed")
        val (out, st) = go(spec(count = 5, profileName = "Gold-Old"), router, cloud)
        assertTrue(out is SagaOutcome.Completed)
        assertTrue("الإنشاء بالاسم المرجع", router.users.values.all { it.profile == "Gold-Renamed" })
        assertEquals("Gold-Renamed", st.run!!.spec.profileName)
    }

    @Test
    fun different_packages_use_their_own_router_profile_and_counter() {
        val cloud = Cloud(); val c = chaos(); val r = Router(c)
        go(spec(count = 2, profileId = "P1", profileName = "Daily", mode = "SEQUENTIAL"), r, cloud)
        go(spec(count = 2, profileId = "P2", profileName = "Monthly", mode = "SEQUENTIAL"), r, cloud)
        // الاسمان 000001/000002 لباقتين مختلفتين على راوتر واحد: الثاني يُتخطّى بسبب التعارض (الأسماء فريدة عالمياً)
        assertEquals(setOf("Daily", "Monthly"), r.users.values.map { it.profile }.toSet())
        assertEquals(4, r.users.size)
        assertEquals(4L, cloud.counters["P2"])
    }

    // ------------------------------------------------------------------ اسم أُخذ أثناء التنفيذ لا يُعدّ نجاحاً ولا يُفوتَر

    @Test
    fun username_taken_by_foreign_account_fails_voucher_and_is_not_billed() {
        val router = Router(chaos()); val cloud = Cloud()
        val c = chaos()
        val store = GenerationSagaTest.FakeStore(c); store.run = run0(spec(count = 5, mode = "SEQUENTIAL"))
        router.takenOnCreate = setOf("000003")
        val out = runBlocking { GenerationSaga(store, router, cloud, java.util.Random(1)).run("EXEC-1") }
        assertTrue(out is SagaOutcome.Completed && (out as SagaOutcome.Completed).partial)
        assertEquals(4, store.run!!.successful); assertEquals(1, store.run!!.failed)
        assertEquals(SagaError.USERNAME_TAKEN, store.staged.values.first { it.username == "000003" }.error)
        assertEquals(4 * 500L, cloud.invoices["INV-ORD-EXEC-1"])
        assertTrue(cloud.committed.none { it.username == "000003" })
    }

    @Test
    fun foreign_account_with_same_name_fails_voucher_is_not_billed_and_is_never_deleted() {
        val router = Router(chaos()); val cloud = Cloud()
        val store = GenerationSagaTest.FakeStore(chaos()); store.run = run0(spec(count = 4, mode = "SEQUENTIAL"))
        router.foreignOnRouter = setOf("000002")
        val out = runBlocking { GenerationSaga(store, router, cloud, java.util.Random(1)).run("EXEC-1") }
        assertTrue(out is SagaOutcome.Completed && (out as SagaOutcome.Completed).partial)
        assertTrue("الفحص نُفِّذ فعلاً", router.credentialChecks > 0)
        assertEquals(3, store.run!!.successful); assertEquals(1, store.run!!.failed)
        assertEquals(SagaError.USERNAME_TAKEN, store.staged.values.first { it.username == "000002" }.error)
        assertTrue("حساب الغير لا يُحذف من الراوتر", router.users.containsKey("000002"))
        assertEquals(3 * 500L, cloud.invoices["INV-ORD-EXEC-1"])
        assertTrue(cloud.committed.none { it.username == "000002" })
    }

    @Test
    fun own_accounts_pass_credential_check_and_all_are_billed() {
        val router = Router(chaos()); val cloud = Cloud()
        val store = GenerationSagaTest.FakeStore(chaos()); store.run = run0(spec(count = 5))
        val out = runBlocking { GenerationSaga(store, router, cloud, java.util.Random(1)).run("EXEC-1") }
        assertTrue(out is SagaOutcome.Completed && !(out as SagaOutcome.Completed).partial)
        assertEquals(5, store.run!!.successful); assertEquals(0, store.run!!.failed)
        assertEquals(5 * 500L, cloud.invoices["INV-ORD-EXEC-1"])
    }

    // ------------------------------------------------------------------ الأعداد الكبيرة

    @Test
    fun large_counts_are_exact_and_unique() {
        val (o1, s1) = go(spec(count = 5000, len = 6, format = "NUMERIC_ONLY"), Router(chaos(), preexisting = (0 until 100_000).map { "u$it" }.toSet()), Cloud())
        assertTrue(o1 is SagaOutcome.Completed)
        assertEquals(5000, names(s1).map { it.lowercase() }.toSet().size)
        val cloud = Cloud()
        val (o2, s2) = go(spec(count = 5000, len = 6, mode = "SEQUENTIAL"), Router(chaos()), cloud)
        assertTrue(o2 is SagaOutcome.Completed)
        assertEquals("000001", names(s2).first()); assertEquals("005000", names(s2).last())
        assertEquals(5000L, cloud.counters["P1"])
    }

    // ------------------------------------------------------------------ القواعد والمدخلات غير الصالحة

    private fun v(
        profile: String = "P", owner: String? = "O", count: Int = 10, format: String = "NUMERIC_ONLY", prefix: String = "", len: Int = 6,
        mode: String = "RANDOM", pw: String = "NUMERIC_ONLY", pwLen: Int = 6, comment: String = ""
    ) = GenerationRules.validate(profile, owner, count, format, prefix, len, mode, pw, pwLen, comment)

    @Test
    fun invalid_inputs_are_rejected_with_the_right_field() {
        assertTrue(v().isEmpty())
        assertEquals(GenerationRules.ERR_PROFILE_REQUIRED, v(profile = " ")[GenerationRules.Field.PROFILE])
        assertEquals(GenerationRules.ERR_OWNER_REQUIRED, v(owner = null)[GenerationRules.Field.OWNER])
        assertEquals(GenerationRules.ERR_COUNT_INVALID, v(count = 0)[GenerationRules.Field.COUNT])
        assertEquals(GenerationRules.ERR_COUNT_INVALID, v(count = -3)[GenerationRules.Field.COUNT])
        assertEquals(GenerationRules.ERR_COUNT_LIMIT_EXCEEDED, v(count = 5001)[GenerationRules.Field.COUNT])
        assertTrue(v(count = 5000).isEmpty())
        assertEquals(GenerationRules.ERR_LENGTH_INVALID, v(len = 3)[GenerationRules.Field.LENGTH])
        assertEquals(GenerationRules.ERR_LENGTH_INVALID, v(len = 13)[GenerationRules.Field.LENGTH])
        assertEquals(GenerationRules.ERR_PREFIX_INVALID, v(prefix = "a b")[GenerationRules.Field.PREFIX])
        assertEquals(GenerationRules.ERR_PREFIX_INVALID, v(prefix = "ابج")[GenerationRules.Field.PREFIX])
        assertEquals(GenerationRules.ERR_PREFIX_INVALID, v(prefix = "123456789")[GenerationRules.Field.PREFIX])
        assertEquals(GenerationRules.ERR_FORMAT_INVALID, v(format = "HEX")[GenerationRules.Field.FORMAT])
        assertEquals(GenerationRules.ERR_MODE_INVALID, v(mode = "FANCY")[GenerationRules.Field.MODE])
        assertEquals(GenerationRules.ERR_SEQUENTIAL_NUMERIC_ONLY, v(mode = "SEQUENTIAL", format = "ALPHANUMERIC")[GenerationRules.Field.FORMAT])
        assertEquals(GenerationRules.ERR_PASSWORD_TYPE_INVALID, v(pw = "WEIRD")[GenerationRules.Field.PASSWORD_TYPE])
        assertEquals(GenerationRules.ERR_PASSWORD_LENGTH_INVALID, v(pwLen = 3)[GenerationRules.Field.PASSWORD_LENGTH])
        assertEquals(GenerationRules.ERR_PASSWORD_LENGTH_INVALID, v(pwLen = 17)[GenerationRules.Field.PASSWORD_LENGTH])
        assertTrue(v(pw = "SAME_AS_USERNAME", pwLen = 0).isEmpty())
        assertEquals(GenerationRules.ERR_COMMENT_INVALID, v(comment = "a\nb")[GenerationRules.Field.COMMENT])
        assertEquals(GenerationRules.ERR_COMMENT_INVALID, v(comment = "x".repeat(101))[GenerationRules.Field.COMMENT])
    }

    @Test
    fun legacy_same_format_conflicting_with_explicit_password_type_is_rejected() {
        assertEquals(GenerationRules.ERR_PASSWORD_TYPE_INVALID, v(format = "USER_PASS_SAME", pw = "NUMERIC_ONLY")[GenerationRules.Field.PASSWORD_TYPE])
        assertTrue(v(format = "USER_PASS_SAME", pw = "SAME_AS_USERNAME", pwLen = 0).isEmpty())
        assertTrue(v(format = "USER_PASS_SAME", pw = "", pwLen = 0).isEmpty())
    }

    @Test
    fun sequence_capacity_check_matches_cloud_reservation() {
        assertEquals(999_999L, GenerationRules.sequenceCapacity(6))
        assertTrue(GenerationRules.sequenceFits(0, 5000, 4))
        assertFalse(GenerationRules.sequenceFits(5001, 5000, 4))
        assertTrue(GenerationRules.sequenceFits(9_994, 5, 4))
        assertFalse(GenerationRules.sequenceFits(9_995, 5, 4))
        assertFalse(GenerationRules.sequenceFits(9_994, 6, 4))
        // نفس القرار الذي تتخذه السحابة (last + count > max ⇒ رفض)
        val cloud = Cloud(hashMapOf("P" to 9_994L))
        assertTrue(runBlocking { cloud.reserveSequence("P", 5, GenerationRules.sequenceCapacity(4)) } is SagaResult.Ok)
        assertTrue(runBlocking { cloud.reserveSequence("P", 1, GenerationRules.sequenceCapacity(4)) } is SagaResult.Err)
    }

    // ------------------------------------------------------------------ أهلية الباقة لنوع التوليد

    @Test
    fun only_generatable_packages_are_offered() {
        val normalCat = ProfileCategory(id = "c1", nameAr = "عادية", systemClass = SystemClass.NORMAL)
        val subCat = ProfileCategory(id = "c2", nameAr = "اشتراكات", systemClass = SystemClass.SUBSCRIPTION)
        val cats = listOf(normalCat, subCat)
        fun p(cat: String = "", name: String = "Gold", st: ProfileStatus = ProfileStatus.ACTIVE) = Profile(categoryId = cat, linkedProfileName = name, status = st)
        assertTrue(GenerationRules.isProfileGeneratable(p("c1"), cats))
        assertFalse("اشتراك", GenerationRules.isProfileGeneratable(p("c2"), cats))
        assertFalse("بلا اسم في UM", GenerationRules.isProfileGeneratable(p("c1", name = ""), cats))
        assertFalse("غير فعّالة", GenerationRules.isProfileGeneratable(p("c1", st = ProfileStatus.MISSING), cats))
        assertTrue("فئة غير محمّلة لا تُمنع (السحابة تحكم)", GenerationRules.isProfileGeneratable(p("zzz"), cats))
        assertTrue("بلا فئة", GenerationRules.isProfileGeneratable(p(""), cats))
    }

    // ------------------------------------------------------------------ حفظ الإعدادات وإعادة استخدامها

    @Test
    fun defaults_roundtrip_and_reject_stale_or_corrupt_values() {
        val d = GenerationDefaults("Wi-", 8, "ALPHANUMERIC", "RANDOM", "MIXED_CASE_ALNUM", 10)
        assertEquals(d, GenerationDefaults.decode(d.encode()))
        val seq = GenerationDefaults("", 5, "NUMERIC_ONLY", "SEQUENTIAL", "SAME_AS_USERNAME", 0)
        assertEquals(seq, GenerationDefaults.decode(seq.encode()))
        assertNull(GenerationDefaults.decode(null))
        assertNull(GenerationDefaults.decode(""))
        assertNull(GenerationDefaults.decode("garbage"))
        assertNull(GenerationDefaults.decode("a;b;c;d;e;f"))
        assertNull("طول غير صالح", GenerationDefaults.decode(d.copy(codeLength = 2).encode()))
        assertNull("تسلسلي مع أحرف", GenerationDefaults.decode(d.copy(mode = "SEQUENTIAL").encode()))
        assertNull("بادئة غير صالحة", GenerationDefaults.decode(d.copy(prefix = "a b").encode()))
        assertNotNull(GenerationDefaults.decode(GenerationDefaults.FACTORY.encode()))
    }

    @Test
    fun defaults_are_per_package_with_global_fallback_and_do_not_leak() {
        // محاكاة AppPreferences.generationDefaults: مفتاح لكل باقة + مفتاح عام
        val store = HashMap<String?, String>()
        fun remember(pid: String, d: GenerationDefaults) { store[pid] = d.encode(); store[null] = d.encode() }
        fun defaultsFor(pid: String?) = pid?.let { GenerationDefaults.decode(store[it]) } ?: GenerationDefaults.decode(store[null]) ?: GenerationDefaults.FACTORY
        assertEquals("لا شيء محفوظ ⇒ مصنعي", GenerationDefaults.FACTORY, defaultsFor("A"))
        val a = GenerationDefaults("A-", 8, "ALPHANUMERIC", "RANDOM", "LETTERS_ONLY", 5)
        val b = GenerationDefaults("", 5, "NUMERIC_ONLY", "SEQUENTIAL", "NUMERIC_ONLY", 6)
        remember("A", a); remember("B", b)
        assertEquals(a, defaultsFor("A")); assertEquals(b, defaultsFor("B"))
        assertEquals("باقة جديدة ⇒ آخر استعمال عام", b, defaultsFor("C"))
        // تغيير إعدادات الباقة A لا يغيّر B
        remember("A", a.copy(prefix = "Z-")); assertEquals(b, defaultsFor("B"))
    }
}
