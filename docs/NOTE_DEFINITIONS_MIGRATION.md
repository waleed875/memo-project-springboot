# ترحيل أنواع المذكرات وتعريفات المؤشرات — القراءة والمعاينة

**الحالة:** شريحة قراءة ومعاينة فقط، داخل `feature/note-type-fields-query-preview`، فوق فرع `feature/masterdata-write-contract`. ليست جاهزة للإنتاج.

## المصدر المرجعي

المستودع الأصلي (للقراءة فقط): `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- `MemoDashboard.Web/Backend/Endpoints/MasterDataEndpoints.cs` (مسارات note-types).
- `AuditMemoSystem.Data/Repositories/DataServices.cs` — `NoteTypeService` وأسماء حقول SQL وحساب معاينة أثر التغيير.
- `AuditMemoSystem.Core/Models/DomainModels.cs` — نماذج `NoteTypeModel`, `NoteTypeFieldModel`, `NoteTypeFieldDefinitionModel`, `NoteTypeFieldImpactPreviewModel`.
- `database/002_create_tables.sql` — `core.note_types`, `core.note_fields`, `core.note_type_fields`, `core.note_field_values`, `core.notes`.

## عقود HTTP المنفذة

| المسار | التفويض | النتيجة |
|---|---|---|
| `GET /api/master/note-types` | جلسة مستخدم صالحة | القائمة `id,code,nameAr,description,isActive` مرتبة حسب `code` |
| `GET /api/master/note-types/{id}/fields` | جلسة مستخدم صالحة | `id,noteTypeId,fieldId,fieldCode,fieldNameAr,sortOrder,isRequired,isActive` |
| `POST /api/master/note-types/fields/preview` | `ADMIN` + CSRF | `noteTypeId,fieldId,affectedNoteCount,existingValueCount,missingOrUnreviewedCount,isNewField` |

في الاستعلام الثاني، `isActive` ناتج **AND** لنشاط الربط `core.note_type_fields` ونشاط الحقل `core.note_fields`. يُستبعد المحذوف منطقيًا؛ وتبقى القيم غير النشطة في النتيجة، حفاظًا على السجل التاريخي والعقد الأصلي.

في المعاينة، يحتسب الاستعلام عدد قيم المؤشرات الموجودة، وعدد المذكرات غير المحذوفة ذات القيمة المفقودة أو غير المراجعة (`field_value = 0`). **استجابة C# الحالية تسند هذا العدد إلى الخاصيتين `affectedNoteCount` و`missingOrUnreviewedCount`**، وقد احتفظنا بهذا السلوك، حتى وإن كان اسم `affectedNoteCount` يحتمل معنى أوسع. عند غياب `fieldId` تكون المعاينة لحقل جديد دون تعديل للقاعدة.

### استثناء أمني مقصود

أصل C# لا يطلب CSRF في endpoint المعاينة لأنه لا يعدّل البيانات؛ أبقينا `POST` وفرضنا CSRF لأن حماية POST هي افتراضية في Spring Security. **هذا تغيير قابل للملاحظة يجب اعتماده في اختبار واجهة React قبل دمج المشروع.**

## هندسة البرمجيات

- `domain`: سجلات Java خالصة (`NoteType`, `NoteTypeField`, `NoteTypeFieldImpactPreview`).
- `application`: `NoteDefinitionQueryService` يتحقق من الهوية، و`ADMIN` للمعاينة، و`NoteDefinitionReader` كمنفذ.
- `infrastructure`: `JdbcNoteDefinitionReader` يستخدم SQL الأصلي على PostgreSQL.
- `api`: `NoteDefinitionReadController` يحافظ على JSON والمسارات.
- `platform`: التوصيل داخل `MasterDataWiring`، وبقاء المسارات الأخرى مغلقة في `SecurityConfiguration`.

## التحقق الآلي

- اختبارات JUnit للهوية والدور والتحقق من UUID.
- اختبارات MockMvc لحقول JSON و401/403 وCSRF ورفض عمليات الحفظ والتعطيل غير المنقولة.
- اختبارات Testcontainers PostgreSQL 16: جدول الأنواع، وحقول مؤشرات معطلة، والسجلات المحذوفة، والمذكرات غير المراجعة أو ذات القيمة المفقودة، وعدم احتساب المذكرات المحذوفة.

المخطط المختبر **fixture جزئي مستمد من ترحيل 002**، وليس تنفيذًا لكل الترحيلات 001–070.

## ما لم يُنقل بعد

1. `POST /api/master/note-types/fields`: يتطلب upsert وقفل advisory خاص بالنوع، وترتيب الحقول، والسماح بإعادة استخدام الرموز، وسجل تدقيق `old_value/new_value` بصيغة JSONB.
2. `DELETE /api/master/note-types/fields/{fieldId}`: تعطيل لا حذف؛ يحافظ على القيم التاريخية، مع سجل تدقيق داخل المعاملة.
3. اختبار واجهة React الحقيقية، ومقارنة C# / Java على بيانات مرجعية كاملة، والتحقق من الترحيلات 001–070.
4. اختبار التزامن والأحداث والصلاحيات في بيئة إنتاج مماثلة.

لا تُفعّل هذه الشريحة أي تغييرات على الجداول أو بيانات الإنتاج، ولم يُعدّل المستودع الأصلي.
