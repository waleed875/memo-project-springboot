# البيانات الأساسية — شريحة قراءة فقط

**الحالة:** تنفيذ تجريبي تحت المراجعة في `feature/masterdata-read-contract`.

## مرجع C# للقراءة فقط

- `MemoDashboard.Web/Backend/Endpoints/MasterDataEndpoints.cs`
- `AuditMemoSystem.Data/Repositories/DataServices.cs`: `CompanyService`, `FactoryService`, `ProductionLineService`
- `AuditMemoSystem.Core/Models/DomainModels.cs`
- SQL migrations: `002`, `008`, `016`, `017`, `024`.

المرجع الثابت: `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

## العقود التي نقلت

| URI | حقول الاستجابة | شروط |
|---|---|---|
| `GET /api/master/companies` | `id,name,isActive` | جلسة صالحة، استبعاد `is_deleted`، ترتيب الاسم |
| `GET /api/master/companies/{id}` | نفس الحقول أو 404 | جلسة صالحة |
| `GET /api/master/factories?companyId={uuid}` | `id,name,companyId,companyName,isActive` | جلسة صالحة، فلترة الشركة اختياريًا، استبعاد الحذف المنطقي |
| `GET /api/master/production-lines?factoryId={uuid}` | `id,factoryId,factoryName,companyId,companyName,name,isActive` | جلسة صالحة، فلترة المعمل اختياريًا، استبعاد الحذف المنطقي |

تظهر العناصر **غير النشطة** كما في الاستعلامات الأصلية، ولا تظهر العناصر المحذوفة منطقيًا. جميع الأدوار المسجلة الدخول يمكنها القراءة.

## هندسة التنفيذ

- `modules/masterdata/domain`: سجلات Company/Factory/ProductionLine لا تعتمد على Spring.
- `modules/masterdata/application`: `MasterDataQueryService` يتحقق من وجود هوية صالحة **قبل** منفذ القراءة؛ `MasterDataReader` هو port مستقل.
- `modules/masterdata/infrastructure`: `JdbcMasterDataReader` يعيد نفس JOIN والـ soft delete والـ ORDER BY من الأصل.
- `modules/masterdata/api`: `MasterDataReadController` يربط المسارات وJSON بالـ use cases، ولا يحتوي على SQL.
- `platform/security`: `SessionUserResolver` هو الموضع الموحد لاستخراج هوية جلسة سبق التحقق منها من قاعدة البيانات. `SecurityFilterChain` يفتح فقط مسارات GET المحددة عند مستوى الفلتر، بينما يتولى Application التفويض النهائي.

## التحقق

- Unit test: لا تقرأ قاعدة البيانات عند غياب الهوية.
- MockMvc: مستخدم VIEWER يستطيع قراءة العقود، والمجهول يتلقى 401، والكتابة ممنوعة.
- PostgreSQL 16 Testcontainers: حزمة مخطط معزولة مشتقة من الترحيلات الأصلية، مع فحص الاستعلامات والفلترة والحقول.

## الحدود الحالية

- لا توجد **POST/PUT/DELETE** للبيانات الأساسية في النسخة الجديدة. لن تفتح قبل نقل `ADMIN`، وضع القراءة فقط، المعاملات، سجل `audit.log` الخاص بالنطاق، وفحوص علاقات المذكرات.
- SQL fixture ليس نسخة لجميع 70 ترحيلًا؛ لا يُستخدم للتنفيذ على الإنتاج.
- الاستعلامات مُختبرة على بيانات مصطنعة فقط؛ التوافق مع React على بيانات النظام الحقيقي ما زال يحتاج اختبار قبول.
- لا يوجد CI/CD ينشر نسخة الإنتاج، ولا تعديل للمستودع الأصلي.

## ترتيب المرحلة التالية

1. حسم مراجعة PR الأساسي للمعمارية ثم PR المصادقة.
2. توثيق جداول `audit.audit_log` و`core.notes` وشروط التحكم في وضع النظام.
3. تصميم أوامر الإنشاء والتعديل والحذف لكل نطاق مع Application service وحدود معاملات واختبارات PostgreSQL.
4. بعدها نقل بيانات أنواع المذكرات وحقولها.
