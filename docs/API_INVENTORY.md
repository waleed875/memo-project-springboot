# جرد HTTP الأولي للخادم الأصلي (من المصدر)

مرجع القراءة فقط: `waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

المصدر: `Memo_Project_Handover_v2.0.2/02_الكود_المصدري_Source_Code/source/src/MemoDashboard.Web/Backend/Endpoints/`.

**المنهج:** استخرجت المسارات من `MapGet/MapPost/MapPut/MapDelete` في ملفات C#. هذا جرد نقاط/مسارات وليس عقدًا كاملاً؛ ما زال يلزم استخراج حقول DTO، وترويسات CSRF، ورموز HTTP، والصلاحيات، وتفاصيل معاملة DB. لا تُفترض صلاحية بناء Java من المسارات وحدها.

## مجموعات المسارات المرصودة

| ملف المصدر | بادئة / نطاق المسارات | أمثلة / وظائف |
|---|---|---|
| `HealthEndpoints.cs` | `/health` | فحص DB، إصدار المخطط وحالة خدمة التحديث |
| `AuthEndpoints.cs` | `/api/auth` | `GET /csrf`, `GET /me`, `POST /login`, `/logout`, `/change-password` |
| `AdminEndpoints.cs` | `/api/admin` | جلسة المدير، المستخدمون، الأدوار، سجل التدقيق، وضع النظام، الترحيلات ونشر التحديثات |
| `SetupEndpoints.cs` | `/api/setup` | `GET /status`, `GET /config`, `POST /test-connection`, `/save`, `/apply-migrations` |
| `MasterDataEndpoints.cs` | `/api/master` | الشركات، المعامل، خطوط الإنتاج، أنواع المذكرات والحقول الديناميكية |
| `NotesEndpoints.cs` | `/api/notes` | CRUD، مراجعة، اعتماد، إرجاع، ربط، مصادر، ومرفقات |
| `DashboardEndpoints.cs` | `/api/dashboard` | `/snapshot`, `/company-metrics`, `/lookups`, `/review-queue` |
| `CompanyReportEndpoints.cs` | `/api/dashboard` | `/company-monthly`, `/company-report.{html,pdf,xlsx,docx,data}` |
| `ReportsEndpoints.cs` | `/api/reports` | القائمة، الهوية، البيانات، وتصدير Excel/PDF/HTML |
| `UpdateEndpoints.cs` | `/api/updates` و `/packages/{file}` | public key، manifest، releases، publish، device check-in وتنزيل الحزم |

## مسارات المذكرات الضرورية للاختبارات

```text
GET    /api/notes
GET    /api/notes/{id}
POST   /api/notes
PUT    /api/notes/{id}
DELETE /api/notes/{id}
GET    /api/notes/review-queue
POST   /api/notes/{id}/start-review
POST   /api/notes/{id}/approve
POST   /api/notes/{id}/return
POST   /api/notes/{id}/release-review
POST   /api/notes/{id}/link
DELETE /api/notes/{id}/unlink/{relatedNoteId}
GET    /api/notes/{id}/source
POST   /api/notes/{id}/source
DELETE /api/notes/{id}/source
GET    /api/notes/{id}/attachment
GET    /api/notes/{id}/attachments
GET    /api/notes/{id}/attachments/{attachmentId}
POST   /api/notes/{id}/attachments
DELETE /api/notes/{id}/attachments/{attachmentId}
POST   /api/notes/{id}/attachment
```

## الاختلافات الأمنية المقصودة عن المصدر

1. `/api/setup/save` و`/apply-migrations`: دور `ADMIN` فقط عند تهيئة نظام موجود، مع مسار first-run منفصل لاحقًا.
2. `/api/admin/updates/releases` و`/publish`: التحقق صراحةً من دور `ADMIN`.
3. `/api/notes/{id}/attachments/*`: تطبيق تصريح القراءة أو التعديل على المذكرة الأم قبل الرفع/التنزيل/الحذف.
4. كل عملية تغير الحالة وتتطلب جلسة Cookie يجب أن تتحقق من CSRF.
5. `/health` القديم ذو شكل JSON خاص؛ endpoint القياسي `/actuator/health` الجديد ليس متوافقًا معه حاليًا. سيُنقل العقد القديم لاحقًا بعد ربط معلومات DB والتحديثات.

## ترتيب نقل النطاقات

`auth/roles` → `master data` → `notes + workflow` → `attachments` → `dashboard/reports` → `setup/migrations` → `update service`.

كل مرحلة مشروطة باختبارات وحدة وتكامل ومطابقة عقد HTTP وأمان. هذا الجرد الأولي وحده **لا يثبت** اكتمال قواعد العمل.
