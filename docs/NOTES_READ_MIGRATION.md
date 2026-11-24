# نقل تفاصيل المذكرات — الشريحة الأولى (قراءة آمنة فقط)

**حالة العمل:** فرع `feature/notes-secure-detail-read` فوق `feature/note-type-fields-write`. الهدف نقل `GET /api/notes/{id}` دون فتح إنشاء/تحديث/حذف المذكرات أو المسارات الأخرى قبل نقل قواعد الأعمال الأصلية.

## المراجع المقروءة من المستودع الأصلي

`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`:

- `src/MemoDashboard.Web/Backend/Endpoints/NotesEndpoints.cs`: GET details وPOST/PUT/DELETE ومراجعة المذكرات.
- `src/AuditMemoSystem.Data/Repositories/DataServices.cs`: `NoteService.GetNoteAsync`, `GetNoteInternalAsync`, `EnsureNoteReadAccessAsync`.
- `src/AuditMemoSystem.Core/Models/DomainModels.cs`: `NoteModel`, `NoteFieldValueModel`, `NoteReviewHistoryModel`.
- `src/AuditMemoSystem.Core/Enums/SystemEnums.cs`: NoteStatus وIndicatorValue.
- SQL migrations `002,009,010,012,013,023,063,069,070` ذات الصلة.

## عقد HTTP المنقول

`GET /api/notes/{id}`: يتطلب جلسة فعالة وإعادة التحقق من المستخدم في PostgreSQL.

- عند غياب الجلسة: 401.
- عند عدم وجود المذكرة أو حذفها منطقيًا: 404 برسالة `المذكرة غير موجودة.`.
- عند محاولة DATA_ENTRY قراءة مذكرة مستخدم آخر: 403 **قبل جلب ملفات المذكرة**.
- الأدوار `ADMIN, REVIEWER, VIEWER` يمكنها قراءة المذكرات كما في C#.
- معرّف غير صالح: 404 على غرار القيد `{id:guid}`.

الإجابة JSON تتضمن حقول `NoteModel` من المصدر: رقم المذكرة، نوعها، الشركة، المعمل وخط الإنتاج، التواريخ، المؤلف والمراجع والمعتمد، الحالة، الملاحظات، صفة الغنائم/الفواتير، المحتوى الثنائي القديم `attachmentData` بصيغة Base64، روابط المذكرات، قيم المؤشرات وسبب تعطيلها وسجل إجراءات المراجعة. تقوم Java بتمثيل `NoteStatus` و`IndicatorValue` كنصوص مطابقة لقيم C#.

## الفصل الهندسي

- `modules/notes/domain`: NoteDetails/NoteFieldValue/NoteReviewHistory/NoteStatus/IndicatorValue و`NoteReadPolicy`.
- `modules/notes/application`: use case `NoteDetailsQueryService`, منفذ `NoteDetailsReader`، وحماية الملكية قبل جلب التفاصيل.
- `modules/notes/infrastructure`: adapter `JdbcNoteDetailsReader` مع حماية إضافية على SQL (`created_by = actor` للـ DATA_ENTRY) ومعاملة قراءة `REPEATABLE_READ` لتحميل الروابط والمؤشرات والتاريخ دون اختلاف لقطة البيانات.
- `modules/notes/api`: `NoteDetailsController` يربط طلب GET باستعلام مخول ويعيد 401/403/404. لا SQL أو صلاحيات متفرقة.
- `platform/security`: `SessionUserResolver` يتحقق من الجلسة؛ SecurityFilterChain يسمح بهذا المسار فقط، ويبقى `/api/notes` للبحث وبقية العمليات مغلقًا.

### دفاع من الوصول غير المصرح (IDOR)

نقرأ `id,created_by` منفصلًا دون محتوى الملف؛ نطبق سياسة الملكية للأدوار، ثم يتم تحميل البيانات كاملة فقط عند نجاح التفويض. وتعيد طبقة JDBC تطبيق شرط الملكية في الاستعلام الأصلي أيضًا، بحيث لا تكفي معرّفات المذكرات وحدها للوصول للمرفقات.

هذا النوع من التحقق مطلوب أيضًا لكل endpoint للمرفقات سيتم نقله لاحقًا.

## الاختبارات

- Unit: مجهول / دور غير معروف / DATA_ENTRY على مذكرة أجنبية / elevated roles.
- MockMvc: 401 دون جلسة، 403 دون ملكية، 404 عند غياب المذكرة، منع الكتابة والمراجعة غير المنفذة.
- Testcontainers PostgreSQL 16: تفاصيل مذكرة حقيقية مع حقول مؤشر وسجل مراجعة وربط وملف ضمن `attachment_data`، وإخفاء المحذوف، والفلترة حسب المالك.
- Architecture / Maven verify: التحقق من فصل الطبقات.

## ما لم يُنقل بعد

1. بحث `GET /api/notes`: يعتمد على `reporting.vw_note_details` وفلاتر التواريخ والمصادر والروابط والإحصاء؛ لا نعطي إجابة ناقصة تحت URI قديم.
2. إنشاء/تعديل/حذف المذكرات: `note_number_normalized`, numeric contract, idempotency, duplicate detection/resolution, scope and workflow, audit, field validations, linked notes; يلزم معاملات PostgreSQL واختبارات Golden Master قبل فتح API.
3. `review-queue/start-review/approve/return/release-review` وإدارة المصادر والملفات المتعددة `core.note_attachments` لم تُنقل بعد.
4. اختبار الترحيلات `001–070` كاملًا وتشغيل React end-to-end ومقارنة C# / Java على بيانات إنتاج مجهّلة الهوية.
5. يجب تحسين معالجة الأخطاء العامة وحدود الأحجام للملفات وعقود الإصدار قبل الإنتاج.

**ممنوع تشغيل هذه الشريحة على قاعدة إنتاج قبل استيفاء هذه المتطلبات. ولا يُعدل المستودع C# الأصلي.**
