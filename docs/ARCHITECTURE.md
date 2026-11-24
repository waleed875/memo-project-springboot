# هندسة الخادم — Modular Monolith + Clean/Hexagonal Architecture

## الهدف والقرار

**الهدف**: نقل منطق MemoDashboard من C# إلى Java/Spring Boot دون فقد السلوك المعتمد، وبهيكل يمكن تطويره واختباره وصيانته على المدى الطويل. معمارية النشر تبدأ **كتطبيق Java واحد**، لا Microservices. يمكن أن يحتوي Docker Compose على حاويات منفصلة للخادم وقاعدة البيانات والوكيل العكسي، وهذا **لا يعني** تقسيم منطق الأعمال إلى خدمات مصغرة.

القرارات المعتمدة في [ADR-0001](adr/0001-modular-monolith.md) و[ADR-0002](adr/0002-compatibility-first.md).

## الهيكل

```text
io.github.waleed875.memo
├── MemoApplication.java                 # Composition root
├── modules/
│   ├── identity/                         # users, roles, sessions, admin operations
│   │   ├── domain/                       # pure rules
│   │   ├── application/                  # use cases, ports, transactions
│   │   ├── api/                          # HTTP controller, DTO, validation
│   │   └── infrastructure/               # SQL, external adapters
│   ├── masterdata/                       # companies, factories, lines, note types
│   ├── notes/                            # note lifecycle, delays, attachments
│   ├── reporting/                        # dashboards, PDF/XLSX/HTML
│   ├── setup/                            # configuration, migrations
│   └── updates/                          # update manifests, packages, signatures
├── sharedkernel/                         # a few stable shared value objects
│   └── identity/MemoRole.java
└── platform/                             # Spring configuration, HTTP security, telemetry
    └── security/SecurityConfiguration.java
```

**ملاحظة**: المجلدات غير الموجودة بعد هي تصميم مستهدف، وليست وظائف مطورة.

## اتجاه الاعتمادات (Dependency Rule)

```text
HTTP API adapter ────────┐
                         v
                   Application
                   (Use cases)
                         |
                         v
                   Domain model
                (Rules / Policies)
                         ^
                         |
               Infrastructure adapter
            (PostgreSQL / files / external APIs)

Platform: Spring Boot wiring, security and operational concerns
```

- `domain`: Java خالص؛ لا `@Entity`، لا Spring، لا JDBC، لا أسماء HTTP، ولا استعلامات SQL.
- `application`: حالات الاستخدام، واجهات منافذ التخزين (ports)، تنسيق المعاملات، والتفويض قبل أي I/O؛ لا يستدعي Controller أو Repository implementation.
- `api`: تحويل DTO، فحص المدخلات، Cookie/CSRF وHTTP status؛ لا يكتب SQL ولا يتخذ قرارات الملكية وحده.
- `infrastructure`: adapters لتنفيذ منافذ الـ application بواسطة JDBC/jOOQ/ملفات/توقيع RSA.
- `platform`: إعدادات Spring Security والـ cross-cutting wiring؛ لا يتضمن قواعد أعمال.
- `sharedkernel`: مصطلحات صغيرة **متفق عليها**؛ لا توضع فيه الخدمات العامة اللامحدودة.

**الاتصال بين الوحدات**: عبر واجهة عامة `application` أو قيمة مستقرة من shared kernel؛ ممنوع الوصول إلى Infrastructure الخاصة بوحدة أخرى. لا دورات اعتماد بين الوحدات.

## حدود المعاملات والصلاحيات

- تبدأ معاملة PostgreSQL في `application service`، عند اكتمال نقل وظيفة حقيقية؛ لا تُنشأ transactions في Controller.
- التحقق من الهوية في boundary، والتحقق من صلاحية المورد في application بعد استرجاع ملكيته الصحيحة من القاعدة. `NoteReadPolicy` وحده لا يكفي دون التأكد من المذكرة والمرفق.
- لا يُسمح لطرف HTTP بتنفيذ migration أو تغيير اتصال القاعدة أو نشر التحديثات إلا وفق دور ADMIN وسياق تهيئة أولى منفصل ومقيّد.
- لا تُقبل الصلاحيات المبنية على الدور فقط للملفات؛ يجب التحقق من الملكية والأذونات لكل عملية.
- لا تُعرّض سجلات الأخطاء والاستثناءات الداخلية للمستخدم؛ استخدم error model ثابتًا وCorrelation ID.

## عقود النظام القديم

- يُحافظ على URI وJSON وHTTP status وتفاصيل التاريخ والوقت قبل التغيير؛ وتُسجل الاستثناءات الأمنية عمدًا.
- PostgreSQL migrations 001–070 و`system.schema_migrations` مرجع قائم؛ **لا تشغّل Flyway أو Hibernate DDL على الإنتاج تلقائيًا** قبل خطة baseline.
- لا نفترض قابلية فك تشفير أسرار .NET أو ASP.NET Data Protection مباشرة في Java.
- لكل vertical slice: اختبار API تعاقدي + اختبار Domain + اختبار تكامل PostgreSQL + اختبار تفويض + خطة تراجع.

## قابلية الاختبار وقياس الجودة

- `JUnit 5`: قواعد العمل بقيم ثابتة وحدود رقمية.
- `ArchUnit`: منع Domain/SharedKernel من الاعتماد على Spring وSQL ومحوّلات API، ومنع الدورات بين الوحدات.
- `MockMvc`: العقد والـ status والترويسات والـ CSRF عند إضافة API.
- `Testcontainers PostgreSQL`: المعاملات والمخطط واستعلامات التقارير (لم يُضف بعد).
- Compare Golden Master: ناتج C# مقابل Java على حالات مرجعية لنفس البيانات (لم يُضف بعد).

هذه الوثيقة تحدد **الهندسة الملزمة** للتحويل المستقبلي؛ لا تدعي اكتمال النطاقات غير المنفذة.
