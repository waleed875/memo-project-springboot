# نواة إنشاء المذكرة بمعاملة واحدة — نطاق محدود وغير منشور

**الحالة:** `feature/notes-transactional-create-core` فوق `feature/notes-save-preflight`. هذا تنفيذ داخلي للمعاملة وليس فتحًا لمسار `POST /api/notes` العام. لا تغيير على مستودع C# ولا على `main` ولا نشر إنتاجي.

## المرجع الأصلي

المصدر المقروء فقط: `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- `AuditMemoSystem.Data/Repositories/DataServices.cs` — `NoteService.SaveNoteAsync` و`AcquireDuplicateLockAsync` و`FindDuplicateIdsAsync` و`AcquireNoteCreateOperationLockAsync` و`TryReserveNoteCreateOperationAsync` وسجل التدقيق.
- `database/057_add_idempotent_note_create_operations.sql`: `core.note_create_operations` مع FK مؤجل نحو `core.notes`.
- `database/062_add_numeric_note_number_contract.sql`: `note_number_value`, `note_year`.
- `AuditMemoSystem.Core/Validation/Validation.cs`: المؤشرات الإلزامية والحالة والتواريخ.

## التصميم الهندسي

- `notes/application/NoteCreateCommand`: أمر محدود مع `operationId` و`noteId` ثابتين، ومتطلبات البيانات الأساسية فقط.
- `notes/application/NoteCreateService`: يتحقق من دور `ADMIN` أو `DATA_ENTRY`، جلسة مثبتة، وبوابة كتابة مغلقة افتراضيًا؛ يستخدم تاريخ `Asia/Damascus`.
- `notes/application/NoteCreator`: منفذ الاستدامة.
- `notes/infrastructure/JdbcNoteCreator`: PostgreSQL مع `@Transactional` **شاملة** لكل أجزاء العملية.
- `platform/security/NotesWiring`: توصيل الخدمة داخليًا دون إضافة HTTP route.

## تسلسل المعاملة

1. تأمين `pg_advisory_xact_lock(hashtextextended(operationId:N,571))` للعملية وفق ترتيب C#.
2. البحث عن `operation_id` أو `note_id` مثبت سابقًا. تعاد المذكرة الأصلية **دون أي آثار جديدة** فقط إذا تطابقت العملية والمستخدم والمذكرة والبصمة.
3. حجز صف `core.note_create_operations` في المعاملة نفسها؛ مفتاح المذكرة يحمل FK مؤجلًا إلى نهاية المعاملة. لا يستمر الحجز إن لم ينجح إدراج المذكرة.
4. التحقق من صلاحية الشركة ونوع المذكرة والمعمل وخط الإنتاج، وقفل تعريفات المؤشرات ومراجعتها مع تطبيق `NoteSavePolicy` داخل المعاملة بدل الوثوق بنتيجة preflight قديمة.
5. قفل التكرار بـ `hashtextextended(key,127)`، مع `company:N|noteType:N|year-or-legacy|numeric`، ثم إعادة فحص PostgreSQL للرقم والسنة والبيانات القديمة المحذوفة منطقيًا.
6. إدخال `core.notes` مع `note_number_normalized` و`note_number_value` و`note_year`، ثم مؤشرات `core.note_field_values`، وسجل `core.note_review_history`، وأحداث `CREATE_NOTE` و`SAVE_INDICATORS` في `audit.logs`.
7. إذا فشل أي جزء، بما فيه سجل التدقيق، يتراجع PostgreSQL عن **الكل**: المذكرة والمؤشرات وسجل المراجعة وحجز العملية.

### بصمة إعادة المحاولة

نستخدم بصمة SHA-256 ثابتة من القيم النصية UTF-8 بطول صريح لكل قيمة، بوسم إصدار `note-create-core-1`. **هذه ليست نفس البصمة الثنائية C# v2**؛ لذلك لا يجوز تقديم عملية C# قديمة إلى هذه النواة على أنها قابلة لإعادة المحاولة عبر اللغتين دون تحويل/اعتماد عقد مشترك أولًا.

### نطاق مُقيد بوضوح

هذه النواة لا تمثّل كل `SaveNoteRequest`، لذلك **لم نفتح `POST /api/notes` ولا `PUT /api/notes/{id}`**:

- المرفقات والصور و`core.note_attachments`، المصادر، الغنائم والفواتير، الروابط المتعددة ومزامنتها، تصنيفات التكرار `COMPANY_DUPLICATE_NUMBER`، وتحديث المذكرات/Snapshots المراجعة ليست مدعومة.
- الرقم الإيجابي إلزامي في إنشاء هذه النواة فقط، وفق ترحيل 062.
- البصمة والاختبارات لا تضمن حتى الآن تطابق Golden Master مع مكتبة C# الأصلية.
- لا تُمثّل fixture الاختبارية تنفيذ ترحيلات 001–070 كاملة.
- يجب اختبار قفل التكرار المتزامن مع خادم C# القديم ذاته، وتطوير حماية HTTP وCSRF وتطابق React، قبل فتح مسار الحفظ.
- أمان idempotency الكامل يتطلب تشغيل DDL الفعلي للمخطط الكامل ومراجعة تزاحم العمليات واختلاف البصمات.

## الاختبارات

اختبارات JUnit لحدود الخدمة (التفويض قبل الكتابة، وضع القراءة فقط، UUID، يوم دمشق) واختبارات Testcontainers PostgreSQL 16 تغطي:
- إنشاء مسودة أو مذكرة مكتملة وحفظ المؤشرات والمراجعة والتدقيق.
- إعادة إرسال العملية ذاتها، ومعرّف العملية نفسه ببيانات/مستخدم مختلف، وإعادة استخدام معرّف المذكرة بعملية مختلفة.
- رفض التكرار بين الأرقام العربية واللاتينية داخل السنة نفسها.
- رفض نقص المؤشرات وتراجع الحجز.
- **فشل التدقيق** والتراجع عن المذكرة والمؤشرات والمراجعة والحجز جميعًا.
- طلبا إنشاء متزامنان بالعملية نفسها يعطيان مذكرة واحدة دون تكرار التدقيق.

يجب الحصول على نتيجة Maven `verify` خضراء قبل قبول الشريحة، ثم مواصلة التوافق الوظيفي مع C#.
