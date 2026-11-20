# تدقيق منظومة المستخدمين والجلسات والتجديد — الجولة الثانية

> الحالة الصادقة: الإصلاحات أدناه **مُتحقَّق منها بالاختبارات الآلية فقط (راوتر وهمي)**. لم يُجرَ أي اختبار على راوتر v6/v7 حقيقي،
> ولم يُترجم المشروع عبر Gradle/Android في هذه البيئة (لا شبكة Maven). المُترجَم والمُختبَر: طبقات domain / core / data.userman /
> SubscriptionRouterExecutor / RouterRepositoryImpl بمترجم Kotlin 2.0.21 مع بدائل لـ Ktor/Firebase/serialization. **410 اختباراً ناجحاً، 0 فاشل.**

## 1. ما وُجد وأُصلح
| # | الفجوة | الإصلاح | الملفات |
|---|--------|---------|---------|
| 1 | `UserManSessionTest` لا يُترجم أصلاً (يستدعي `RouterConnectionManager(api)` وهو غير موجود) → كان سيكسر `testDebugUnitTest` كاملاً | نقطة حقن اختبارية `internal apiOverride` + تصحيح الاختبار | `RouterConnectionManager.kt`, `UserManSessionTest.kt` |
| 2 | لا قراءة لسجل الجلسات من User Manager (NAS، سبب الإنهاء، معرّف المحاسبة، وقت النهاية) | `UmSession` موسَّع + `sessionHistory()` في v6/v7 + `getUserSessionRecords()`؛ الحقول غير المتوفرة تبقى فارغة ولا تُخمَّن | `UserManModels.kt`, `UserManDialect.kt`, `UserManV6/V7Dialect.kt`, `RouterRepositoryImpl.kt` |
| 3 | احتمال احتساب الجلسة مرتين في الاستهلاك | `UmSessionHistory.dedupe/summarize` (هوية = acct-session-id أو مفتاح مركّب) وتطبيقه في `usage()` | `UserManModels.kt`, `UserManDialect.kt` |
| 4 | الجلسات العالقة تظهر "متصلة" | تأكيد قراءة-فقط على `/ip/hotspot/active`: UM يبقى المصدر، وما لا يعرفه NAS يُستبعد من المتصلين ويُعاد في `LiveSnapshot.stale` دون حذف أي سجل؛ فشل قراءة NAS ⇒ `UNVERIFIED` (لا حكم بالعلوق) | `LiveSessionRules.classify`, `LiveSessionModels.kt`, `RouterRepositoryImpl.getLiveSnapshot` |
| 5 | لا تمييز بين انتهاء المدة / استنفاد Uptime / استنفاد Bytes / التعطيل | `UserAccessRules` (مقارنة بعدّادات الفترة الحالية لا الإجمالي التاريخي، وnull لا يُعدّ استنفاداً) | `UserAccessRules.kt` |

الاختبارات الجديدة: `UserManSessionHistoryTest` (9) و`UserAccessRulesTest` (7) + تعديل اختبارين قائمين.

## 2. تدقيق سريع للمحاور (حسب الكود الحالي)
- **المستخدمون:** إنشاء/تعديل/تعطيل/حذف عبر `UserManSource` وlifecycle موجود من الجولة السابقة؛ لم أعدّل منطقه.
- **الجلسات التاريخية:** `SessionTracker` يبني سجلاً بالرصد الدوري (تقديري)؛ المصدر الحقيقي أصبح متاحاً الآن (البند 2) لكن **لم يُستبدل به التتبع ولم تُربط به شاشة**.
- **الجلسات الحية:** مصدرها UM (v6: `/tool/user-manager/session`، v7: `/user-manager/session`)؛ الفصل عبر NAS ثم تحقق.
- **التجديد:** مفتاح عدم التكرار `lastRenewKey` داخل transaction في Firestore (`CloudSubscriptionDataSource.renew`) وعمليات حتمية المعرّف؛ لم أغيّره.
- **الصلاحية:** الانتهاء الزمني مُدار بـ `SubscriptionRules`؛ استنفاد الحدود لم يكن يُعرَّف على مستوى التطبيق → أُضيف `UserAccessRules`.

## 3. غير مكتمل (بصراحة)
1. **واجهات المستخدم لم تُعدَّل:** لا عرض للجلسات العالقة/UNVERIFIED، ولا شاشة سجل جلسات من UM، ولا حالة "استنفاد الحد" في Users/Expired. الدوال جاهزة ومُختبَرة؛ الربط المرئي يحتاج ترجمة Compose فعلية.
2. **اختبارات v6/v7 الحقيقية والحالات الاستثنائية على راوتر حقيقي: لم تُنفَّذ.** أسماء حقول جلسات UM (`acct-session-id`, `nas-ip-address`, `terminate-cause`, `till-time`/`ended`, `last-accounting-packet`) مبنية على التوثيق ولم تُؤكَّد على جهاز.
3. **سياسات التجديد:** "التجديد الفوري" على v7 أثناء فترة جارية و"إبقاء المدة المتبقية" لم تُتحقَّق من سلوك UM الفعلي؛ لم أضفها. التجديد القائم يعمل بالسياسة الموثّقة سابقاً.
4. **استبدال `SessionTracker` بقراءة سجل UM** (توحيد مصدر التاريخ) قرار معماري مؤجَّل.
5. مشروع قائم يحوي WorkManager (`GenerationWorker`, `OfflineSyncWorker`) من قبل؛ لم أضف أي واحد.

## 4. أوامر API المستخدمة في المسارات الجديدة
- v6: `print /tool/user-manager/session ?user=<name>` ، `print /ip/hotspot/active` (تأكيد فقط)
- v7: `print /user-manager/session ?user=<name>` ، `print /ip/hotspot/active` (تأكيد فقط)
- كلها قراءة؛ لا كتابة ولا حذف في المسارات الجديدة.

## 5. دورة حياة المستخدم (ملخص)
إنشاء (Firestore+UM، حالة مزامنة) → تفعيل/تعديل Profile → جلسة (UM active ⇒ تأكيد NAS) → انتهاء {زمن | uptime | bytes | تعطيل} (`UserAccessRules`) → تجديد (idempotent، إعادة ضبط عدّادات الفترة دون حذف التاريخ) → أرشفة/حذف مع بقاء السجل المالي.
