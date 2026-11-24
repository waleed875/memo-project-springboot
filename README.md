# Memo Project — Spring Boot Migration

مستودع عمل **جديد ومستقل** لإعادة كتابة خادم MemoDashboard باستخدام Java / Spring Boot، مع الإبقاء على واجهة React ومنطق الأعمال وتوافق قاعدة بيانات PostgreSQL، واعتماد Docker وPortainer للنشر لاحقًا، مع استضافة الكود المصدري على GitHub. تم تأجيل ربط Gitea إلى مرحلة لاحقة.

> **الحالة:** مرحلة التخطيط والتأسيس فقط. لا يوجد بعد خادم Spring Boot وظيفي أو صورة Docker قابلة للنشر.

## حدود المشروع

- المستودع الأصلي **للقراءة فقط**؛ ممنوع إجراء Commit أو Push أو Pull Request إليه ضمن مشروع التحويل.
- نقل منطق الأعمال يتم تدريجيًا مع اختبارات تطابق السلوك (Contract/Characterization Tests)، وليس بإعادة تصميم قواعد العمل.
- لا تُجرى تعديلات غير متوافقة على مخطط PostgreSQL قبل اختبار التوافق ووضع خطة رجوع.
- لا توضع بيانات حقيقية، كلمات مرور، مفاتيح تشفير، أو حزم إصدار ثنائية في Git.
- لا يُبدّل الإنتاج إلى Spring Boot حتى تنجح اختبارات القبول والأمن والترحيل والنسخ الاحتياطي.

## مصدر الحقيقة للنسخة الحالية

- المستودع الأصلي: https://github.com/waleed875/memo_project
- نقطة المرجع الثابتة (commit): `dd8eb2ba57864eac2371a3addd0b786ccefa2367`
- مجلد الكود الأصلي: `Memo_Project_Handover_v2.0.2/02_الكود_المصدري_Source_Code/source/`
- نسخة التسليم: 2.0.2؛ ملفات ترحيل قاعدة البيانات 001–070.

## التصميم المقترح

```text
Users / React Web UI
        |
Reverse Proxy / HTTPS
        |
Spring Boot API (Java 21 LTS, release chosen after compatibility review)
        |
PostgreSQL (retain current schema initially)
```

المستودع الرئيسي: **GitHub (`waleed875/memo-project-springboot`)**. سيتم تحديد مسار CI/CD الفعلي لاحقًا؛ ويمكن في مرحلة لاحقة توصيل Gitea Runner/Registry ببنية النشر دون نقل المصدر الأساسي من GitHub. يتم النشر والتحكم بالتحديثات عبر **Docker + Portainer** بعد إكمال التطبيق واختباراته.

## هيكل المستودع

- `backend/` — خادم Java الجديد (يُضاف بعد تثبيت عقود الواجهة والسلوك).
- `frontend/` — واجهة React الحالية، للترحيل لاحقًا دون تعديل واجهاتها قدر الإمكان.
- `deploy/` — Dockerfiles وملفات Compose وملفات النشر إلى Portainer لاحقًا.
- `.github/workflows/` — موضع العمل المستقبلي على CI بعد تحديد مسار الأتمتة، دون وجود Pipeline نشط الآن.
- `tests/` — اختبارات مقارنة الخادم الجديد بالخادم الأصلي.
- `docs/` — التصميم، خطة نقل منطق الأعمال، التوافق، النشر، وقواعد الأمان.

## الوثائق

1. [خطة نقل منطق الأعمال](docs/MIGRATION_PLAN.md)
2. [عقود التوافق ومعايير القبول](docs/COMPATIBILITY_CONTRACT.md)
3. [استضافة GitHub وخطة Docker / Portainer المستقبلية](docs/CI_CD_DEPLOYMENT.md)
4. [قائمة الأمان والتشغيل](docs/SECURITY.md)

## المرحلة التالية

جرد نقاط API، والخدمات، وجداول SQL والصلاحيات، ثم تأسيس تطبيق Spring Boot أولي مع اختبار Health + اتصال PostgreSQL بقاعدة اختبار منفصلة. لا تُستخدم قاعدة الإنتاج أثناء التطوير.

لا تُجرى أي عمليات Commit أو Push على المستودع القديم `waleed875/memo_project`.
