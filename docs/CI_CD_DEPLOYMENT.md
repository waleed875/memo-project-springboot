# GitHub كمستودع أساسي وخطة نشر Docker / Portainer

## البنية المقترحة

```text
GitHub repository (waleed875/memo-project-springboot)
    |
    +-- Push / Pull Request
             |
        CI Runner (مؤجّل اختيار/تفعيل المزود)
             |
        Unit + Integration + Contract Tests
             |
        Build OCI image + scan + sign (اختياري)
             |
        Push OCI image to a private Registry (سيحدد لاحقًا)
             |
        Approved deployment manifest update
             |
        Portainer (Git-backed Stack / GitOps)
             |
        Docker host: reverse-proxy, memo-api, postgres/external PostgreSQL
```

## قواعد الأتمتة

- مستودع GitHub هو المصدر الأساسي. لم يُضبط أي Runner أو Registry أو نشر بعد؛ Gitea مؤجّل، ويمكن إضافته مستقبلًا كخدمة CI/Registry أو مرآة دون استبدال المستودع الأساسي.
- لا يتم منح Runner صلاحية Docker على خادم الإنتاج. تفضّل بيئة بناء معزولة، لأن التحكم في Docker daemon عالي الامتيازات.
- كتابة image tag غير متغير (semver + git SHA أو digest) في ملف Compose داخل مسار نشر معتمد.
- **بناء الصورة ورفعها وحده لا يعني تحديث Portainer**: يلزم تحديث مصدر Git للـ Stack، أو إعادة سحب image/redeploy ضمن آلية صريحة.
- اعتماد مرحلة موافقة قبل تحديث الإنتاج، والتأكد من نجاح DB backup وصحة الخدمة.
- Portainer يستطيع مراقبة مستودع Git وتحديث الـ Stack عند تغيّر commit أو من خلال Webhook؛ التفعيل والميزات الدقيقة تتبع نسخته وتكوينه.
- لا تستخدم `latest` في الإنتاج، ولا تُنشئ صورة `postgres` مدمجة داخل تطبيق Java.

## البيئة

- `memo-api`: خادم Spring Boot، non-root، read-only root filesystem حيث أمكن، مع volume لمجلدات البيانات المطلوبة.
- `postgres`: إذا كانت القاعدة على نفس الخادم، استخدم حاوية منفصلة بــ persistent volume، بدون نشر 5432 للعامة؛ أو استعمل قاعدة المؤسسة الخارجية.
- `reverse-proxy`: HTTPS أمام الـ API؛ لا تعرض API على الإنترنت مباشرة دون سياسات وصول.
- `backup`: نسخ PostgreSQL بطريقة `pg_dump` مجربة مع استعادة دورية؛ إيقاف العمليات عالية الخطورة أثناء ترقية المخطط.

## العمل دون إنترنت

- يلزم وجود registry داخلي يمكن للخادم الوصول إليه أو نقل الصور عبر `docker save` و`docker load`.
- Runner يحتاج مخزنًا/مرآة للحزم التي يحتاجها Maven/Gradle وNPM وإجراءات Actions في البيئات المعزولة تمامًا.

## الحالة

هذه خطة مستقبلية وليست إعداد CI/CD يعمل الآن. GitHub هو مكان استضافة الكود؛ الربط بـ Gitea مؤجّل. يجب تخصيص المضيفين والـ Registry وPortainer وRunner وSSL وSecrets عند التنفيذ.
