# حذف المذكرات المنطقي — شريحة مستقلة للمراجعة

## المصدر المرجعي

تم فحص:
- `NotesEndpoints.cs` — `DELETE /api/notes/{id}?expectedUpdatedAt=...`
- `DataServices.cs` — `NoteService.DeleteNoteAsync`
- `DomainModels.cs`, جداول `core.notes`, `core.note_links`, `audit.logs`.

مرجع الشيفرة الأصلية غير المعدلة: `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## العقد

`DELETE /api/notes/{id}?expectedUpdatedAt=2026-11-24T11:00:00`.

- يحتاج جلسة أعيد التحقق منها من قاعدة البيانات، وصلاحية `ADMIN`، وCSRF، وبوابة كتابة مفتوحة عمدًا (مغلقة افتراضيًا).
- `expectedUpdatedAt` قيمة إلزامية لتفادي الحذف بناءً على نسخة قديمة.
- عند النجاح: `{ "message": "تم حذف المذكرة بنجاح." }`.
- عند التعارض أو الحذف السابق: `409` برسالة عربية.
- عند غياب الصلاحية: 401 أو 403.

## التصميم

`api/NoteDeleteController` → `application/NoteDeleteService` → `application/NoteDeleteRepository` → `infrastructure/JdbcNoteDeleteRepository`.

- الـ Application مستقل عن إطار HTTP وSQL؛ يفرض دور `ADMIN` وبوابة الكتابة ثم يتحقق من id وتاريخ الإصدار.
- JDBC يستعمل `@Transactional`: قفل صف المذكرة والتحقق من التاريخ أولًا، **ثم** فك روابط `core.note_links`، ثم `is_deleted=true, deleted_at=now(), deleted_by`، ثم إدراج `DELETE_LINK` إن وجدت روابط و`DELETE_NOTE` بقيم JSONB في `audit.logs`.
- عند أي فشل في تحديث الإصدار أو التدقيق، تُلغى تغييرات الروابط والحذف ضمن المعاملة نفسها.
- لا تُمس المرفقات أو قيم المؤشرات التاريخية: يُخفى السجل بطريقة soft-delete كما في الأصل.
- لا يمكن استخدام هذا الـ API لتجاوز قاعدة الملكية عن طريق تغيير user_id: يؤخذ actor من الجلسة فقط.

## ما تمت تغطيته بالاختبارات

- JUnit: دون جلسة/غير مدير/وضع القراءة فقط/طلب بلا نسخة تعديل.
- MockMvc: CSRF و401/403/409 وJSON الاستجابة.
- PostgreSQL 16 Testcontainers: الحذف الصحيح وتدقيقه؛ نسخة تعديل قديمة؛ مذكرة مفقودة/محذوفة؛ rollback عند فشل كتابة `audit.logs` سواء كان هناك رابط أو لا.
- استخدام fixture منفصل `postgres-notes-delete-schema.sql` للحقول التاريخية `deleted_by/deleted_at`.

## قيود لم تُحل

1. شريحة الحذف لا تعني توفر إنشاء/تعديل المذكرات؛ هاتان العمليتان تتطلبان قواعد المؤشرات والتكرار/الإدخال المتكرر وسير العمل وترابط المصادر والمرفقات.
2. التحقق يجري على مخطط PostgreSQL جزئي مشتق من الأصل، **وليس اختبار كل الترحيلات 001–070**؛ يُمنع تطبيق fixture على الإنتاج.
3. سياسة الأخطاء المبكرة/CSRF قد تختلف عمدًا عن C#؛ يجب إعادة اختبار التوافق مع React.
4. لا يُنشر هذا الفرع دون مراجعة هندسية وأمنية واختبار التزامن والتحديثات الموروثة.

لا تغيير على المستودع الأصلي ولا دمج في main ولا نشر لخادم الإنتاج.
