# مراجعة وإصلاح وحدة الوكلاء (Owners)

> لم يُبنَ المشروع ولم تُشغَّل أي اختبارات في بيئة المراجعة (لا Kotlin compiler / Android SDK / شبكة). كل ما يلي مراجعة شيفرة + تعديلات لم تُترجَم بعد.

## ما سُلِّم صحيحاً (بعد التتبع UI → UseCase → Repository → Firestore)
- الرصيد/الدفتر: `owner_ledger` بمعرّف = مفتاح العملية (لا تكرار)، الرصيد لا يتغير إلا بقيد.
- التسعير: `PricingPolicy.resolveFor` مصدر واحد (سعر الوكيل الخاص ← جملة/تجزئة الفئة ← سعر قديم) مع لقطة سعر ومصدره على الدفعة/الاشتراك.
- التوليد يتحقق من حالة الوكيل؛ الأرشفة = status فقط (لا حذف، لا يتيّم سجلات).

## الثغرات التي وُجدت وأُصلحت
| # | المشكلة | الإصلاح |
|---|---|---|
| D1 | إنشاء اشتراك جديد باسم وكيل موقوف/مؤرشف/غير موجود كان يمرّ (السياسة المعتمدة: يُمنع الجديد فقط) | فحص داخل معاملة `CloudSubscriptionDataSource.create` عبر `OwnerCreditRules.checkOwnerCanOperate`؛ رمز `ERR_OWNER_NOT_ACTIVE` + ترجمة AR/EN |
| D2 | المستخدمون المنسوبون لوكيل مؤرشف يظهر لهم معرّف خام | `allOwners` في `MgtikUiState` + `ownerNameIndex` في S16/S16D للأسماء فقط (اختيار الوكيل للتبنّي يبقى للفعّالين) |
| D3 | `updateOwner` يقبل أي status/اسم فارغ | `OwnerCreditRules.validateIdentityUpdate` قبل الكتابة |
| D4 | ملاحظات قيود الدفتر لا تظهر، والتاريخ خام ISO، و`createdAt` للوكيل لا يُقرأ | حقل `note` في `OwnerLedgerEntry`، عرض مختصر للتاريخ، قراءة createdAt |
| D5 | لا طريقة لمعرفة مصدر السعر النهائي/استثناءات الوكيل | تبويب «الأسعار» في S14: `OwnerPriceBook` (سعر خاص مقابل أساسي، مطبَّق/معطَّل، الفرق) + `getOwnerPricingRules` |
| D6 | الدليل بلا ترتيب، ونص حالة الفراغ خاطئ | قائمة ترتيب (الاسم/الأعلى مستحقاً/الأعلى رصيداً) + نص فراغ صحيح |
| D7 | لا ملخص مبيعات للوكيل | ثلاث بطاقات: إجمالي الفوترة، المحصَّل، عدد الفواتير (من `OwnerStatement.summarize`) |

## الملفات المعدَّلة/المضافة
OwnerCreditRules, OwnerPriceBook (جديد), OwnerOperationsUseCases, IProfileRepository, ProfileRepositoryImpl, CloudProfileDataSource, CloudOwnerDataSource, CloudSubscriptionDataSource, DomainEntities, ErrorTranslator, OwnersViewModel, MgtikViewModel, MgtikNavGraph, S12, S14, S16, S16D، وstrings (ar/en). الاختبار: `OwnerLifecycleAndPriceBookTest` (جديد).

## لم يُتحقق منه / قيود معروفة
- لا بناء ولا تشغيل اختبارات ولا فحص بصري (RTL/M3/تابلت).
- **التكلفة والربح لكل وكيل غير قابلين للحساب**: الفواتير لا تحمل سعر التكلفة؛ لذلك لم تُضف مؤشرات تكلفة/ربح (لا أرقام وهمية).
- عدد مستخدمي الوكيل وأداؤه عبر الزمن غير مضافين (يحتاجان مصدر Room/تجميعاً لم يُراجَع).
- تعديل/حذف الأسعار الخاصة يبقى من شاشة الفئة (التبويب للقراءة فقط).
- `safeApiCall` بلا مهلة؛ العمليات المالية للوكلاء مباشرة للسحابة (لا طابور offline) — تفشل بوضوح دون اتصال لكن لا تُعاد تلقائياً.
- تجديد/تغيير باقة اشتراك لوكيل موقوف لم يُمنع (قرار سياسة: يُعتبر خدمة قائمة). USERNAME_TAKEN وكلمة مرور v6 المخزّنة ما زالا مخاطر مفتوحة من الجولة السابقة.
- نقاط الدخول الأخرى لإنشاء مستخدم عادي (`UserRepositoryImpl.createNormalUser`) تتحقق عبر SingleUserRules كما سبق.
