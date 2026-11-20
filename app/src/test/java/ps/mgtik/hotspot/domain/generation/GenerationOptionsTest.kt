package ps.mgtik.hotspot.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.Chaos
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.Crash
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeCloud
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeRouter
import ps.mgtik.hotspot.domain.generation.GenerationSagaTest.FakeStore
import java.util.Random

/**
 * اختبارات خيارات شاشة التوليد: الوضع التسلسلي والعدّاد الدائم لكل بروفايل، العشوائي، البادئة والأطوال وأنواع الأحرف،
 * كلمة المرور، التعليق، منع التكرار، وفشل التنفيذ على الراوتر.
 */
class GenerationOptionsTest {

    private fun spec(
        count: Int,
        mode: GenerationMode = GenerationMode.SEQUENTIAL,
        format: String = "NUMERIC_ONLY",
        prefix: String = "XN-",
        len: Int = 4,
        passwordType: String = "NUMERIC_ONLY",
        passwordLength: Int = 6,
        comment: String = "",
        profileId: String = "P1"
    ) = GenerationSpec(profileId, "Gold", "O1", count, format, prefix, len, mode.name, passwordType, passwordLength, comment)

    private fun run(spec: GenerationSpec, id: String = "EXEC-1") = RunState(
        executionId = id, idempotencyKey = "k-$id", orderId = "ORD-1", batchId = "BATCH-1", spec = spec, status = RunStatus.QUEUED
    )

    private fun usernames(st: FakeStore) = st.staged.values.sortedBy { it.seq }.map { it.username }

    // ------------------------------------------------------------ التسلسلي والعدّاد

    @Test
    fun sequential_starts_at_one_pads_and_applies_prefix() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 5))
        val out = GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertTrue(out is SagaOutcome.Completed)
        assertEquals(listOf("XN-0001", "XN-0002", "XN-0003", "XN-0004", "XN-0005"), usernames(st))
        assertEquals(5L, cl.sequenceLast)
    }

    @Test
    fun sequential_continues_from_saved_counter_and_never_restarts() = runBlocking<Unit> {
        val cl = FakeCloud(Chaos()); val rt = FakeRouter(Chaos())
        cl.sequenceLast = 1250                             // المثال المطلوب: آخر رقم 1250 ⇒ البدء من 1251
        val st1 = FakeStore(Chaos()); st1.run = run(spec(count = 3, len = 5, prefix = ""), "E1")
        GenerationSaga(st1, rt, cl, Random(1)).run("E1")
        assertEquals(listOf("01251", "01252", "01253"), usernames(st1))

        val st2 = FakeStore(Chaos()); st2.run = run(spec(count = 2, len = 5, prefix = ""), "E2")
        GenerationSaga(st2, rt, cl, Random(2)).run("E2")
        assertEquals(listOf("01254", "01255"), usernames(st2))
        assertEquals(1255L, cl.sequenceLast)
    }

    @Test
    fun counters_are_independent_per_profile_in_cloud_contract() = runBlocking<Unit> {
        // العقد: الحجز بمفتاح معرّف البروفايل؛ المزيّف يمثّل وثيقة بروفايل واحدة، فنُنشئ سحابة لكل بروفايل
        val clA = FakeCloud(Chaos()); val clB = FakeCloud(Chaos()); val rt = FakeRouter(Chaos())
        clA.sequenceLast = 100
        val a = FakeStore(Chaos()); a.run = run(spec(count = 2, profileId = "A", prefix = "A"), "EA")
        val b = FakeStore(Chaos()); b.run = run(spec(count = 2, profileId = "B", prefix = "B"), "EB")
        GenerationSaga(a, rt, clA, Random(1)).run("EA")
        GenerationSaga(b, rt, clB, Random(1)).run("EB")
        assertEquals(listOf("A0101", "A0102"), usernames(a))
        assertEquals(listOf("B0001", "B0002"), usernames(b))
    }

    @Test
    fun two_runs_on_the_same_profile_never_share_numbers() = runBlocking<Unit> {
        val cl = FakeCloud(Chaos()); val rt = FakeRouter(Chaos())
        val s1 = FakeStore(Chaos()); s1.run = run(spec(count = 40), "E1")
        val s2 = FakeStore(Chaos()); s2.run = run(spec(count = 40), "E2")
        val saga1 = GenerationSaga(s1, rt, cl, Random(1)); val saga2 = GenerationSaga(s2, rt, cl, Random(2))
        // تداخل: الأولى تُنهي التوليد ثم الثانية قبل أن تدفع أيّ منهما (أسوأ توقيت للتعارض)
        val r1 = saga1.run("E1"); val r2 = saga2.run("E2")
        assertTrue(r1 is SagaOutcome.Completed && r2 is SagaOutcome.Completed)
        val all = usernames(s1) + usernames(s2)
        assertEquals(80, all.toSet().size)
        assertEquals(2, cl.reservations.size)
        assertTrue(cl.reservations[0].last < cl.reservations[1].first)   // نطاقان منفصلان
        assertEquals(80L, cl.sequenceLast)
    }

    @Test
    fun failed_router_accounts_burn_their_numbers_but_are_not_counted_successful() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 10))
        rt.rejectPrefix = "XN-0003"
        val out = GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertTrue(out is SagaOutcome.Completed && (out as SagaOutcome.Completed).partial)
        assertEquals(9, st.run!!.successful)
        assertEquals(1, st.run!!.failed)
        assertEquals(9L * 500, cl.invoices["INV-ORD-1"])         // لا فوترة لما لم يُنشأ
        assertEquals(10L, cl.sequenceLast)                       // الرقم المحروق لا يُعاد
        val next = FakeStore(Chaos()); next.run = run(spec(count = 1), "E2")
        GenerationSaga(next, FakeRouter(Chaos()), cl, Random(3)).run("E2")
        assertEquals(listOf("XN-0011"), usernames(next))
    }

    @Test
    fun existing_router_names_are_skipped_not_reused_or_duplicated() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        rt.users["xn-0003"] = "manual"; rt.users["xn-0004"] = "manual"
        st.run = run(spec(count = 5))
        val out = GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertTrue(out is SagaOutcome.Completed)
        assertEquals(listOf("XN-0001", "XN-0002", "XN-0005", "XN-0006", "XN-0007"), usernames(st))
        assertEquals(7L, cl.sequenceLast)
        assertTrue(rt.createCalls.values.all { it == 1 })
        assertEquals("manual", rt.users["xn-0003"])              // حساب اليدوي لم يُمسّ
    }

    @Test
    fun sequence_exhaustion_fails_cleanly_without_touching_counter_or_router() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        cl.sequenceLast = 9995                                  // سعة 4 خانات = 9999، المطلوب 10
        st.run = run(spec(count = 10))
        val out = GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertTrue(out is SagaOutcome.Failed && (out as SagaOutcome.Failed).error.code == SagaError.SEQUENCE_EXHAUSTED)
        assertEquals(9995L, cl.sequenceLast)
        assertTrue(rt.users.isEmpty() && cl.invoices.isEmpty())
    }

    @Test
    fun counter_reset_restarts_numbering_and_conflicts_are_still_skipped() = runBlocking<Unit> {
        val cl = FakeCloud(Chaos()); val rt = FakeRouter(Chaos())
        val s1 = FakeStore(Chaos()); s1.run = run(spec(count = 3), "E1")
        GenerationSaga(s1, rt, cl, Random(1)).run("E1")
        cl.sequenceLast = 0                                     // تصفير يدوي مؤكَّد من المستخدم
        val s2 = FakeStore(Chaos()); s2.run = run(spec(count = 2), "E2")
        GenerationSaga(s2, rt, cl, Random(2)).run("E2")
        // 0001..0003 موجودة على الراوتر ⇒ تُتخطّى ولا تُكرَّر
        assertEquals(listOf("XN-0004", "XN-0005"), usernames(s2))
        assertEquals(5L, cl.sequenceLast)
    }

    @Test
    fun sequential_killed_at_every_operation_resumes_without_duplicate_accounts() = runBlocking<Unit> {
        fun newRun() = run(spec(count = 45, comment = "cafe wifi", len = 6))
        val probe = Chaos(); val p = FakeStore(probe); p.run = newRun()
        GenerationSaga(p, FakeRouter(probe), FakeCloud(probe), Random(7), routerChunkSize = 15, cloudChunkSize = 20).run("EXEC-1")
        for (k in 1..probe.ops) {
            val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
            st.run = newRun(); c.crashAt = k
            try { GenerationSaga(st, rt, cl, Random(7), routerChunkSize = 15, cloudChunkSize = 20).run("EXEC-1") } catch (e: Crash) { /* موت العملية */ }
            c.crashAt = Int.MAX_VALUE
            val out = GenerationSaga(st, rt, cl, Random(9), routerChunkSize = 15, cloudChunkSize = 20).run("EXEC-1")
            assertTrue("crash@$k completed", out is SagaOutcome.Completed)
            assertEquals("crash@$k users", 45, rt.users.size)
            assertTrue("crash@$k no double create", rt.createCalls.values.all { it == 1 })
            assertEquals("crash@$k unique names", 45, usernames(st).map { it.lowercase() }.toSet().size)
            // الاستئناف قد يحرق أرقاماً (قتل بعد الحجز) لكنه لا يكرّر رقماً بين الحجزات
            assertTrue("crash@$k disjoint reservations", cl.reservations.sortedBy { it.first }.zipWithNext().all { (a, b) -> a.last < b.first })
            assertTrue("crash@$k comment kept", rt.users.values.all { it == "cafe wifi" })
        }
    }

    // ------------------------------------------------------------ العشوائي

    @Test
    fun random_mode_respects_prefix_length_and_charset_and_does_not_touch_counter() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 200, mode = GenerationMode.RANDOM, format = "UPPERCASE_ONLY", prefix = "WIFI", len = 6))
        GenerationSaga(st, rt, cl, Random(4)).run("EXEC-1")
        val names = usernames(st)
        assertEquals(200, names.toSet().size)
        assertTrue(names.all { it.startsWith("WIFI") && it.length == 10 && it.drop(4).all { ch -> ch in 'A'..'Z' } })
        assertEquals(0L, cl.sequenceLast)                       // العشوائي لا يستهلك العدّاد
        assertTrue(cl.reservations.isEmpty())
    }

    @Test
    fun random_mode_avoids_existing_router_names() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        // فضاء 4 خانات رقمية = 10000؛ نحجز على الراوتر 0000..0999 فلا يجوز أن يظهر أيٌّ منها
        for (i in 0..999) rt.users["r%04d".format(i)] = "old"
        st.run = run(spec(count = 500, mode = GenerationMode.RANDOM, prefix = "R"))
        GenerationSaga(st, rt, cl, Random(5)).run("EXEC-1")
        val names = usernames(st).map { it.lowercase() }
        assertEquals(500, names.toSet().size)
        assertTrue(names.none { it in rt.users.filterValues { v -> v == "old" }.keys })
    }

    // ------------------------------------------------------------ كلمة المرور

    @Test
    fun password_types_and_lengths_are_honoured_and_independent_of_username() {
        fun gen(type: PasswordType, len: Int) = VoucherCodeGenerator.generate(300, CodeFormat.ALPHANUMERIC, "", 6, emptySet(), Random(1), type, len)
        gen(PasswordType.NUMERIC_ONLY, 5).let { g -> assertTrue(g.all { it.password.length == 5 && it.password.all(Char::isDigit) }) }
        gen(PasswordType.LETTERS_ONLY, 7).let { g -> assertTrue(g.all { it.password.length == 7 && it.password.all(Char::isLetter) }) }
        gen(PasswordType.ALPHANUMERIC, 8).let { g -> assertTrue(g.all { it.password.length == 8 && it.password.all(Char::isLetterOrDigit) }) }
        gen(PasswordType.MIXED_CASE_ALNUM, 12).let { g ->
            assertTrue(g.all { it.password.length == 12 })
            assertTrue(g.any { p -> p.password.any(Char::isUpperCase) } && g.any { p -> p.password.any(Char::isLowerCase) } && g.any { p -> p.password.any(Char::isDigit) })
        }
        gen(PasswordType.SAME_AS_USERNAME, 0).let { g -> assertTrue(g.all { it.password == it.username }) }
        // لا افتراض بالمطابقة: أي نوع آخر ينتج كلمة مختلفة عن الاسم
        gen(PasswordType.ALPHANUMERIC, 6).let { g -> assertTrue(g.all { it.password != it.username }) }
    }

    @Test
    fun sequential_passwords_follow_selected_type() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 30, passwordType = "LETTERS_ONLY", passwordLength = 9))
        GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertTrue(st.staged.values.all { it.password.length == 9 && it.password.all(Char::isLetter) })
    }

    // ------------------------------------------------------------ التعليق

    @Test
    fun comment_is_applied_to_all_accounts_and_cancel_removes_them() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 30, comment = "  حفلة الصيف  "))
        // الإلغاء بعد أول دفعة (يحاكي ضغط المستخدم)
        val canceling = object : RouterPort by rt {
            var n = 0
            override suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome {
                val r = rt.createUser(username, password, profileName, comment, limits)
                if (++n == 10) st.setCancelRequested("EXEC-1", true)
                return r
            }
        }
        val out = GenerationSaga(st, canceling, cl, Random(1), routerChunkSize = 10, routerConcurrency = 1).run("EXEC-1")
        assertTrue(out is SagaOutcome.Cancelled)
        assertTrue("cancelled accounts are cleaned by username", rt.users.isEmpty())
    }

    @Test
    fun every_account_in_the_operation_gets_the_same_comment() = runBlocking<Unit> {
        val c = Chaos(); val st = FakeStore(c); val rt = FakeRouter(c); val cl = FakeCloud(c)
        st.run = run(spec(count = 25, comment = "event-7"))
        GenerationSaga(st, rt, cl, Random(1)).run("EXEC-1")
        assertEquals(setOf("event-7"), rt.users.values.toSet())
        val plain = FakeStore(c); plain.run = run(spec(count = 3, comment = ""), "E2")
        val rt2 = FakeRouter(c); GenerationSaga(plain, rt2, FakeCloud(c), Random(1)).run("E2")
        assertEquals("no comment entered => none sent", setOf(""), rt2.users.values.toSet())
    }

    @Test
    fun router_tag_matching_is_exact_and_ignores_other_batches() {
        assertEquals("MGTik:B-000001", RouterTags.tag("B-000001"))
        assertEquals("MGTik:B-000001 | note", RouterTags.comment("B-000001", " note "))
        assertEquals("MGTik:B-000001", RouterTags.comment("B-000001", "   "))
        assertTrue(RouterTags.matches("MGTik:B-000001", "MGTik:B-000001"))
        assertTrue(RouterTags.matches("MGTik:B-000001 | note", "MGTik:B-000001"))
        assertFalse(RouterTags.matches("MGTik:B-0000010", "MGTik:B-000001"))
        assertFalse(RouterTags.matches("MGTik:B-0000010 | note", "MGTik:B-000001"))
        assertFalse(RouterTags.matches("manual comment", "MGTik:B-000001"))
    }

    // ------------------------------------------------------------ المولّد النقي

    @Test
    fun sequential_generator_pads_prefixes_skips_taken_and_reports_last_number() {
        val b = VoucherCodeGenerator.generateSequential(3, 98, 3, "G", 4, setOf("g0099"), Random(1), PasswordType.NUMERIC_ONLY, 6)
        assertEquals(listOf("G0098", "G0100"), b.credentials.map { it.username })      // 99 تُخطّي (غير حساس للحالة)
        assertEquals(1, b.skipped)
        assertEquals(100L, b.lastNumber)
        val full = VoucherCodeGenerator.generateSequential(5, 1, 5, "", 3, emptySet(), Random(1))
        assertEquals(listOf("001", "002", "003", "004", "005"), full.credentials.map { it.username })
        assertEquals(5L, full.lastNumber)
    }

    @Test
    fun sequential_generator_throws_when_number_exceeds_length_capacity() {
        var threw = false
        try { VoucherCodeGenerator.generateSequential(5, 998, 5, "", 3, emptySet(), Random(1)) } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun usernames_use_only_routeros_safe_characters() {
        for (fmt in listOf(CodeFormat.NUMERIC_ONLY, CodeFormat.ALPHANUMERIC, CodeFormat.UPPERCASE_ONLY)) {
            val g = VoucherCodeGenerator.generate(200, fmt, "A_B-", 8, emptySet(), Random(2), PasswordType.MIXED_CASE_ALNUM, 10)
            assertTrue(g.all { it.username.all { ch -> ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '_' } })
            assertTrue(g.all { it.password.all { ch -> ch.isLetterOrDigit() && ch.code < 128 } })
        }
    }

    // ------------------------------------------------------------ القواعد

    private fun v(
        count: Int = 100, len: Int = 6, prefix: String = "X", fmt: String = "NUMERIC_ONLY", mode: String = "RANDOM",
        pt: String = "ALPHANUMERIC", pl: Int = 6, comment: String = ""
    ) = GenerationRules.validate("P", "O", count, fmt, prefix, len, mode, pt, pl, comment)

    @Test
    fun rules_cover_mode_password_and_comment() {
        assertTrue(v().isEmpty())
        assertEquals(GenerationRules.ERR_MODE_INVALID, v(mode = "chaos")[GenerationRules.Field.MODE])
        assertEquals(GenerationRules.ERR_SEQUENTIAL_NUMERIC_ONLY, v(mode = "SEQUENTIAL", fmt = "ALPHANUMERIC")[GenerationRules.Field.FORMAT])
        assertTrue(v(mode = "SEQUENTIAL", fmt = "NUMERIC_ONLY").isEmpty())
        assertEquals(GenerationRules.ERR_PASSWORD_TYPE_INVALID, v(pt = "weird")[GenerationRules.Field.PASSWORD_TYPE])
        assertEquals(GenerationRules.ERR_PASSWORD_LENGTH_INVALID, v(pl = 3)[GenerationRules.Field.PASSWORD_LENGTH])
        assertEquals(GenerationRules.ERR_PASSWORD_LENGTH_INVALID, v(pl = 17)[GenerationRules.Field.PASSWORD_LENGTH])
        assertTrue(v(pl = 4).isEmpty() && v(pl = 16).isEmpty())
        assertTrue("same-as-username needs no length", v(pt = "SAME_AS_USERNAME", pl = 0).isEmpty())
        assertTrue("legacy empty password type is accepted", v(pt = "", pl = 0).isEmpty())
        assertTrue(v(comment = "x".repeat(100)).isEmpty())
        assertEquals(GenerationRules.ERR_COMMENT_INVALID, v(comment = "x".repeat(101))[GenerationRules.Field.COMMENT])
        assertEquals(GenerationRules.ERR_COMMENT_INVALID, v(comment = "a\nb")[GenerationRules.Field.COMMENT])
        assertTrue("unicode comment is fine", v(comment = "حفلة الصيف ☀").isEmpty())
    }

    @Test
    fun max_count_fits_the_smallest_numeric_length_in_both_modes() {
        // 5000 اسماً بأربع خانات رقمية (10^4): مقبول تسلسلياً وعشوائياً عند الحد الأقصى
        assertTrue(v(count = 5000, len = 4, mode = "SEQUENTIAL").isEmpty())
        assertTrue(v(count = 5000, len = 4, mode = "RANDOM").isEmpty())
        assertEquals(GenerationRules.ERR_COUNT_LIMIT_EXCEEDED, v(count = 5001, len = 4, mode = "SEQUENTIAL")[GenerationRules.Field.COUNT])
    }

    @Test
    fun prefix_and_length_rules_still_hold() {
        assertTrue(v(prefix = "").isEmpty() && v(prefix = "GZ").isEmpty() && v(prefix = "WIFI").isEmpty())
        assertTrue(v(prefix = "bad prefix").containsKey("prefix") && v(prefix = "عربي").containsKey("prefix"))
        assertTrue(v(len = 3).containsKey("codeLength") && v(len = 13).containsKey("codeLength"))
    }
}
