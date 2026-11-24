# البحث في المذكرات — شريحة القراءة والترقيم الصفحي

**الحالة:** فرع `feature/notes-search-pagination` مبني فوق فرع `feature/notes-secure-detail-read`، ما زال للتطوير والمراجعة ولا يصلح للإنتاج.

## مرجع التوافق

المستودع الأصلي لم يتغير:
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- `NotesEndpoints.cs`: معاملات `GET /api/notes` و`ParseDateFlexible`.
- `DataServices.cs`: `NoteService.SearchNotesAsync` ونص SQL الحتمي لترتيب النتائج.
- `DomainModels.cs`: `NoteSearchFilterModel`, `NoteDetailsModel`, `PagedResult<T>`.
- `database/030_refresh_reporting_contracts.sql`: `reporting.vw_note_details` والمجاميع؛ المراحل 050، 063، 064، 069، 070 تضيف قيودًا وتعاقدات ذات صلة.

## عقد API الجديد

`GET /api/notes` يدعم نفس معاملات البحث الأساسية:

`noteNumber`, `companyId`, `factoryId`, `productionLineId`,
`noteTypeId`, `createdBy`, `status`, `from`, `to`,
`dateBasis`, `hasWritingDate`, `unlinkedOnly`,
`isLoot`, `isInvoice`, `entryCutoffDate`,
`pageNumber` (افتراضي 1)، `pageSize` (افتراضي 25).

- الصيغة: `{items, totalCount, pageNumber, pageSize, totalPages}`.
- `pageSize` محصور بين 1 و200؛ `pageNumber` لا يقل عن 1، ويُخفض إلى آخر صفحة موجودة إن تجاوز عدد الصفحات.
- تاريخ البحث يمكن أن يكون `YYYY-MM-DD` أو `YYYY-MM` أو `MM/YYYY`؛ نهاية الشهر متضمنة.
- `dateBasis=WritingDate` يرجع إلى `arrivalAt` عندما يكون `writingDate` غائبًا و`hasWritingDate` غير محدد، كما في C#.
- حالة المذكرة وخصائصها تُعاد بأسماء JSON الأصلية و`NoteStatus` سلسلة نصية.
- تُستبعد المذكرات المحذوفة منطقيًا؛ أولوية الترتيب `arrivalAt DESC, createdAt DESC, noteId DESC`.
- `unlinkedOnly=true` يبحث عن مذكرات `ENTRY` غير الغنائم وغير المرتبطة؛ مصدر `LOOT` يفحص كذلك جدول `core.note_sources` بصرف النظر عن العلم القديم.
- `DATA_ENTRY` مقيد قسرًا بمعرّف حسابه من الجلسة، ولا يستطيع تعديل نطاق البحث بإرسال `createdBy` مختلف؛ أدوار `ADMIN,REVIEWER,VIEWER` تتبع القواعد الأصلية.

### تغييرات موثقة تحتاج اختبار قبول

- المعامل `status` غير المعروف يعيد 400 بدلاً من اختلافات ربط C# المحتملة.
- دعم تواريخ مخصص لأشكال صريحة (ISO, `d/M/yyyy`, `YYYY-MM`, `MM/YYYY`)؛ بعض صيغ الثقافة المحلية التي قد يقبلها `.NET DateOnly.TryParse` غير مدعومة بعد.
- أعيد `imagePath=null` في البحث عن قصد، مطابقًا لتعيين `ImagePath = null` في C#.
- استعلام SQL يستخدم view `reporting.vw_note_details` القائم ولا يحاول إعادة حساب المؤشرات في Java.
- اختلاف بسيط محتمل في ترتيب الصفوف المتساوية أو اقتطاع التاريخ مع إعدادات المنطقة الزمنية يستدعي Golden Master لاحقًا.

## الهندسة

- `notes/domain`: `NoteSearchCriteria`, `NoteSearchItem`, `PagedNotes`.
- `notes/application`: `NoteSearchService` يتحقق من الجلسة والدور والنطاق والزمن والحجم قبل تمرير معايير immutable إلى `NoteSearchReader`.
- `notes/infrastructure`: `JdbcNoteSearchReader` يستخدم `NamedParameterJdbcTemplate` وSQL مربوطًا بالمعاملات؛ `REPEATABLE_READ` لتوحيد عدد النتائج والصفحات في لقطة واحدة.
- `notes/api`: `NoteSearchController` يستقبل URI ويحلل التواريخ والحالة، دون SQL.
- `platform/security`: مسار GET الجديد فقط، وتغلق صراحة المسارات `review-queue` و`incomplete-indicators` و`duplicates/check` و`link-candidates` قبل wildcard الخاص بالتفاصيل.

## الاختبارات

- JUnit: صلاحيات مدخل البيانات، منع دور غير معروف، التواريخ، حدود صفحات النتائج.
- MockMvc: مجهول 401، تجاهل `createdBy` المتلاعب، 400 عند مدخلات غير صالحة، وإغلاق المسارات غير المنفذة.
- Testcontainers PostgreSQL 16: اختبارات فلترة الشركات والأدوار والحالات والغنائم والفواتير والمذكرات غير المرتبطة، القيم المحذوفة، ترتيب الصفحات، fallback لتاريخ الكتابة.
- ملف `postgres-notes-search-view.sql` يمثل نسخة جزئية من تعاقد view في الترحيل 030؛ **ليس ضمانًا لتنفيذ كامل 001–070**.

## ما لم يُنفذ

1. إنشاء/تحديث/حذف المذكرات والعمليات المتقدمة المرتبطة بها.
2. اختبارات مقارنة C# وJava على بيانات مجهلة الهوية وتأكيد ترحيلات SQL كلها.
3. بحث قوائم المراجعة والمؤشرات غير المكتملة والتكرار والمرشحات المرتبطة بالتحويلات.
4. تنفيذ المرفقات المتعددة وسير الاعتماد والرابط مع React.

لا يوجد تعديل على الأصل أو نشر للحاويات في الإنتاج.
