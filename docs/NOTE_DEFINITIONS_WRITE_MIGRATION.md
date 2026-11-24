# كتابة تعريفات حقول المذكرات وتعطيلها — المرحلة الثانية

## المصدر والتوافق

تمت مراجعة تطبيق `NoteTypeService.SaveFieldDefinitionAsync` و`DeactivateFieldDefinitionAsync` في مشروع C# الأصلي، وهو مرجع **للقراءة فقط**:
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

### مسارات HTTP

- `POST /api/master/note-types/fields`: إنشاء تعريف حقل وربطه بنوع مذكرة، أو تحديث التعريف، مع إعادة حقول تعريف الطلب `id,noteTypeId,fieldId,fieldCode,fieldNameAr,description,sortOrder,isRequired,isActive`.
- `DELETE /api/master/note-types/fields/{fieldId}`: **تعطيل** تعريف الحقل دون حذف الارتباط أو قيم المؤشرات التاريخية.

كلاهما يتطلب جلسة مستخدم صالحة ومراجعة الهوية مقابل PostgreSQL ودور `ADMIN` وCSRF. جميع العمليات ممنوعة افتراضيًا ما لم يتحقق `MasterDataWriteGate`: تفعيل تشغيل صريح + نمط `SERVER` في إعداد الحاوية + `system.app_settings.APP_MODE=SERVER`.

## المعمارية وحدود الاعتمادات

- `domain/NoteTypeFieldDefinition`: نموذج بيانات مستقل عن Spring.
- `application/NoteDefinitionCommandService`: صلاحيات ADMIN وتحقق المعطيات وفتح بوابة التعديل.
- `application/NoteDefinitionWriter`: منفذ التخزين.
- `infrastructure/JdbcNoteDefinitionWriter`: PostgreSQL و`@Transactional`، معاملة واحدة لكل تعديل.
- `api/NoteDefinitionWriteController`: تعاقد HTTP وتحويل JSON، دون SQL أو قواعد أعمال.
- `platform/MasterDataWiring` و`SecurityConfiguration`: DI وتحديد HTTP routes المتاحة للطبقة التطبيقية؛ CSRF مطلوب.

## سلوك الكتابة

1. قفل استشاري PostgreSQL من نوع `pg_advisory_xact_lock(hashtextextended('note-type-fields:' + GuidN, 712))` لتسلسل التعديل الخاص بنوع المذكرة.
2. عند تحديث تعريف موجود: قفل الصف، التقاط `old_value` من الحقل/الربط بصيغة `jsonb_build_object`، ومنع استبدال `field_id` المرتبط بقيمة مختلفة.
3. عند إنشاء حقل جديد: إعادة استخدام حقل له الرمز نفسه دون حساسية لحالة الأحرف، أو إنشاء `core.note_fields` جديد؛ ثم تحديث بيانات الاسم والوصف والترتيب والحالة.
4. `INSERT ... ON CONFLICT(note_type_id,field_id) DO UPDATE` للربط؛ مع إحياء السجل المحذوف منطقيًا عند إعادة الربط.
5. إنشاء `new_value` بصيغة JSONB، وتسجيل `CREATE_NOTE_TYPE_FIELD` أو `UPDATE_NOTE_TYPE_FIELD` في `audit.logs` **ضمن المعاملة ذاتها**.
6. عند التعطيل: قراءة النوع، أخذ القفل الاستشاري **قبل قفل الصف** لتفادي انعكاس ترتيب الأقفال مع الحفظ؛ `is_active=false`, `is_deleted=false`، وتسجيل `DEACTIVATE_NOTE_TYPE_FIELD` مع `old_value/new_value` في المعاملة.

## الاختبارات

- JUnit: منع العمليات المجهولة وغير الإدارية أو في وضع القراءة فقط قبل أي كتابة؛ التحقق من مدخلات الحقول وتطبيع النصوص.
- MockMvc: CSRF، والاستجابات 401/403/409 ونجاح JSON.
- Testcontainers PostgreSQL 16: إنشاء الحقول، إعادة استخدام الرمز دون حساسية لحالة الأحرف، upsert، صور التدقيق القديمة والجديدة، التعطيل دون حذف، والتراجع عن المعاملة عند فشل تسجيل التدقيق، واختبار عميلين متزامنين يثبت انتظار الكاتب القفل الاستشاري حتى تحرير معاملة أخرى له.
- ArchUnit: لا اعتماد مباشر من domain/application على Spring وSQL.

## حدود واضحة قبل الاعتماد

- الاعتماد على fixture مستمد من SQL الأصلي **ليس تنفيذًا كاملاً للترحيلات 001–070**.
- نقل القفل الاستشاري مطابق للمرجع حيث تستعمل جميع عمليات الكاتب له؛ لكن عمليات أخرى في النظام القديم قد تتنافس على نفس الجداول دون احترام القفل، وقد أُضيف اختبار تزامن محدود بعميلين على PostgreSQL؛ لا يزال مطلوبًا اختبار أحمال متزامنة تشمل عمليات C# القديمة والمعاملات الأخرى قبل الإنتاج.
- قد تتطلب اختلافات قواعد أسماء الحقول والرموز بين الواجهة الأصلية والحزم الحديثة اختبارات Golden Master إضافية؛ لا نعد نجاح JUnit وحده دليلاً على مطابقة التطبيق كله.
- لا يُدمج هذا الفرع في `main` قبل مراجعة سلسلة طلبات الدمج السابقة، ولا يُنشر على قاعدة إنتاج.
- ملف `deploy/compose.preview.yaml` لا يُفعّل عمليات الكتابة افتراضيًا.
