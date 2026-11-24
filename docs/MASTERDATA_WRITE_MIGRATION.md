# عمليات البيانات الأساسية — شريحة الكتابة (Draft)

## مراجع التوافق من مستودع C# للقراءة فقط

- `MasterDataEndpoints.cs` — مسارات HTTP، الإدارة، CSRF، رسالة خطأ الحذف.
- `DataServices.cs` — `CompanyService` / `FactoryService` / `ProductionLineService`، و`RepositoryBase.WriteAuditRecordAsync`.
- `database/002_create_tables.sql` — `audit.logs` و`core.notes`؛ `016`, `017`, `024` — العلاقات والقيود والفهارس؛ `system.app_settings`.
- المرجع الثابت: `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## مسارات HTTP المنقولة

- `POST /api/master/companies`; `PUT/DELETE /api/master/companies/{id}`.
- `POST /api/master/factories`; `PUT/DELETE /api/master/factories/{id}`.
- `POST /api/master/production-lines`; `PUT/DELETE /api/master/production-lines/{id}`; `POST /api/master/production-lines/{id}/toggle`.

تستخدم الاستجابات صيغة JSON الأصلية `id/name/isActive` وإضافات الروابط `companyId/companyName` و`factoryId/factoryName`. يتطلب كل طلب معدل للحالة Session صالحة ودور `ADMIN` وCSRF. تُعاد أخطاء تحقق الأعمال برسالة عربية دون SQL، لكن بعض حالات HTTP قد تختلف أمنيًا عن المصدر إلى أن تكتمل اختبارات Contract مع React.

## المعمارية

`api/MasterDataWriteController` → `application/MasterDataCommandService` و`MasterDataWriteRepository` (Port) → `infrastructure/JdbcMasterDataWriteRepository` (Adapter).

`MasterDataCommandService` Java خالص لا يعرف Spring ولا PostgreSQL؛ يتحقق من `ADMIN` قبل فتح gate الكتابة. `JdbcMasterDataWriteRepository` ينفذ تحديث الصف والـ `audit.logs` ضمن **معاملة واحدة** بـ Spring `@Transactional` (ضمن Spring proxy وقت التشغيل).

## منع الكتابة افتراضيًا (Fail closed)

الكتابة ممنوعة ما لم تتحقق **كل الشروط**:

1. `memo.masterdata.writes-enabled=true` (إذن تشغيل صريح غير متوفر في الإعداد الافتراضي).
2. `memo.masterdata.connection-profile-type=SERVER` (الافتراضي LOCAL).
3. `system.app_settings.APP_MODE` في PostgreSQL تساوي `SERVER`.

لا يضبط Docker preview أيًا من الشرطين الأولين؛ لن يتسبب تحديث الحاوية بمفرده في فتح عمليات الكتابة. هذه طبقة حماية إضافية **لا تحل محل** إعداد صلاحيات قاعدة بيانات المستخدم نفسها. قبل الإنتاج يجب التحقق من trusted proxy، ومن دعم session store متعدد العقد، وإغلاق أي مسار وصول يتجاوز خدمة التفويض.

## قواعد نقل البيانات

- الشركات: رفض الاسم الفارغ، حذف منطقي، لا تحذف شركة لها مذكرات أو معامل غير محذوفة.
- المعامل: شركة موجودة ونشطة، عدم نقل معمل له مذكرات إلى شركة أخرى؛ قيد إضافي يحمي المعمل صاحب خطوط الإنتاج الموجودة؛ الفهارس تمنع تكرار الاسم.
- خطوط الإنتاج: المعمل موجود ونشط، منع نقل خط مستخدم في مذكرات، ومنع حذف خط مرتبط بمذكرات؛ الإزالة منطقية.
- التدقيق: `audit.logs` في **نفس المعاملة** وبالأكواد الأصلية `CREATE_*`, `UPDATE_*`, `DELETE_*`, `TOGGLE_PRODUCTION_LINE`. إذا فشل التدقيق تتراجع الكتابة.
- `FOR UPDATE` لتثبيت السجلات الأم، ثم التأكد من القيود والكتابة. تظل قيود PostgreSQL وTriggers الأصلية ضمانة أخيرة. يلزم اختبار حالات التزامن التي تنشئ مذكرات أثناء الحذف على مخطط 001–070 الكامل.

## الاختبارات والحدود

- Unit: عدم تمكين كتابة لدى VIEWER/بدون جلسة/في وضع القراءة فقط؛ حالات تحقق الاسم والروابط.
- Spring MockMvc: CSRF وسلوك 401/403/409.
- Testcontainers PostgreSQL 16: عمليات الإنشاء والتعديل والحذف، الحذف المنطقي، تدقيق وتراجع، والقيود المرجعية على fixtures **مشتقة** من SQL الأصلي.
- ما زال **اختبار جميع ترحيلات 001–070** مطلوبًا، ويجب استكمال parity للـ API على React قبل دمج أو نشر هذه الشريحة.
- في حالات أخطاء SQL غير المصنّفة، يبقى السلوك HTTP عامًا دون عرض التفاصيل؛ يلزم ترحيل معالجات أخطاء التطبيق بالكامل.
- **لا يُنشر هذا الفرع على إنتاج** ولا يغير المستودع القديم.
