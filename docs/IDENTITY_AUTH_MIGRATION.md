# نقل المصادقة — Vertical Slice 01 (تجريبي)

**الحالة:** قيد التطوير، لا يصلح للنشر الإنتاجي. مبني على فرع `feature/identity-auth-api` فوق أساس `architecture/modular-monolith-foundation`.

## مصدر السلوك

- `src/MemoDashboard.Web/Backend/Services/WebAdminAuthService.cs`
- `src/MemoDashboard.Web/Backend/Endpoints/{AuthEndpoints,AdminEndpoints}.cs`
- `auth.users` و`auth.roles` من PostgreSQL الحالي.

تُقرأ هذه الملفات من المستودع الأصلي `waleed875/memo_project` دون أي تعديلات عليه.

## تطبيق هندسة الخادم

| الطبقة | الملفات | المسؤولية |
|---|---|---|
| domain | `modules/identity/domain/UserAccount` | صورة الحساب النشط، دور المستخدم وقرار تغيير كلمة المرور |
| application | `IdentityApplicationService`, `UserAccountLookup`, `PasswordVerifier` | التحقق من الهوية وصلاحية الجلسة عبر منافذ مستقلة |
| infrastructure | `JdbcUserAccountLookup`, `BcryptPasswordVerifier` | JDBC على الجداول الأصلية وBCrypt |
| api | `LegacyAuthController` | عقود login/me/session/logout وCSRF |
| platform | `IdentityWiring`, `SecurityConfiguration` | Spring DI وSecurityFilterChain |

## ما يعمل مبدئيًا

- `GET /api/admin/csrf` و`GET /api/auth/csrf`: إرجاع JSON `{token}` متوافق مع واجهة React التي تستخدم `X-CSRF-TOKEN`.
- `POST /api/admin/login` و`POST /api/auth/login`: حساب نشط وغير محذوف ودور نشط، ومقارنة BCrypt مع القيم الموجودة، وحالة `mustChangePassword`.
- `GET /api/admin/session` و`GET /api/auth/me`: عرض الهوية الآمنة دون كلمة مرور أو hash، والتحقق من الحساب وختم التغيير من PostgreSQL في كل طلب.
- `POST /api/admin/logout` و`POST /api/auth/logout`: إنهاء الجلسة.
- حد مطلق للجلسة 8 ساعات من لحظة المصادقة؛ منع تثبيت الجلسة بتغيير Session ID.
- الطلبات المعدلة للحالة تحميها آلية CSRF الافتراضية في Spring Security.
- تبقى بقية مسارات `/api/**` غير متاحة (deny-by-default).

## متطلبات غير مكتملة — ممنوع استخدامه إنتاجيًا

1. **تغيير كلمة المرور** نُقل مبدئيًا، لكنه يحتاج اختبارات PostgreSQL وBCrypt وتزامن مع خادم .NET قبل اعتباره مكتملًا.
2. **تسجيل أحداث التدقيق** نُقل بنمط best effort إلى `updates.audit_log`، ويحتاج اختبارًا تشغيليًا وفحص حالات فشل التخزين.
3. **تحديد معدل محاولات الدخول** أُضيف محليًا ضمن JVM؛ يحتاج تخزينًا مشتركًا وتكوين reverse proxy في النشر متعدد الحاويات.
4. **Session backend متعدد الحاويات**: الحالة الحالية هي HttpSession محلية وتتطلب اختيار Redis أو Spring Session JDBC/بديل متوافق إذا شغّلنا أكثر من مثيل.
5. **تطابق BCrypt** يحتاج اختبارات تكامل مع تجزئات فعلية مولدة من تنفيذ C#، خصوصًا الاختلافات الخاصة بالإصدارات والـ Unicode.
6. **تجربة PostgreSQL على migrations 001–070** لم تنفذ بعد؛ `compose.preview.yaml` ينشئ قاعدة فارغة لا تشبه قاعدة الإنتاج.
7. **توافق cookies/CSRF end-to-end مع React** يحتاج تشغيل الواجهة الحقيقية، مع HTTPS وSameSite/Secure وتهيئة reverse proxy.
8. النظام القديم يملك جلسات ASP.NET Data Protection لا تنتقل مباشرة إلى Java؛ نخطط لإعادة تسجيل دخول المستخدمين عند التحويل.

## المخاطر المقبولة في مرحلة التطوير فقط

- `MEMO_SESSION_COOKIE_SECURE=false` افتراضيًا لتجربة loopback HTTP؛ يجب ضبطه true في الإنتاج خلف TLS.
- لا يُنشئ التطبيق مستخدمًا افتراضيًا ولا يزرع كلمات مرور في قاعدة البيانات.
- الاستثناءات الوظيفية الأمنية لا تسمح بفتح مرفقات أو واجهات إدارة قبل نقل authorization فعليًا.
- تحقق `updated_at` و`must_change_password` لكل session request قد يكلف اتصال SQL متكررًا؛ يُفضل الحفاظ على ضمان أمن الجلسة أولًا ثم قياس الأداء.

## معايير إكمال الشريحة

- [ ] جميع اختبارات Maven/ArchUnit ناجحة بعد مراجعة PR.
- [ ] اختبار PostgreSQL فعلي بمخطط أصلي ومستخدمي اختبار.
- [ ] اختبار BCrypt لتجزئات C# وحالات mustChange.
- [ ] إضافة change-password بسياسة كلمة المرور الأصلية + تدقيق الأحداث + throttling.
- [ ] ربط authentication بسياق Spring Security للتفويض على بقية API.
- [ ] اختبارات HTTP للأخطاء وحماية CSRF والدور/الملكية وإعادة توليد الجلسة.
- [ ] تشغيل واجهة React الحقيقية في بيئة staging مع Docker.

**لا يُنقل هذا الفرع إلى الإنتاج ولا يُعامل كخادم مصادقة مكتمل.**


## تحديث هذه الشريحة — تغيير كلمة المرور والتدقيق وتقييد المحاولات

أُضيفت وظائف تمهيدية على الفرع نفسه:

- `POST /api/auth/change-password`: تحقق من كلمة المرور الحالية، وسياسة 8–512 محرفًا، إنشاء تجزئة BCrypt جديدة، ثم تحديث مشروط في `auth.users` مع إلغاء الجلسة المحلية بعد النجاح.
- `UserPasswordWriter` و`SecurityEventAudit`: منافذ تطبيقية مستقلة عن PostgreSQL، مع منفذ JDBC لكل منهما.
- أحداث `LOGIN` و`LOGIN_FAILURE` و`LOGIN_MUST_CHANGE_PASSWORD` و`LOGOUT` و`PASSWORD_CHANGED` و`PASSWORD_CHANGE_FAILURE` تسجل في `updates.audit_log` إن كانت موجودة. هذا التسجيل best effort كما في الخادم القديم ويُطلق تحذيرًا عند الفشل، **وليس ضمان تدقيق تدقيقيًا صلبًا**.
- `FixedWindowAttemptLimiter`: عشر محاولات في الدقيقة لكل عنوان اتصال مباشر، محلي داخل JVM وبذاكرة محدودة، يُعيد HTTP 429 مع `Retry-After`.
- اختبارات JUnit لتغيير كلمة المرور والتحكم في عدد المحاولات وسياسة طول كلمة المرور.

### قيود هذه الخطوة

1. تقييد المحاولات محلي وليس مشتركًا بين الحاويات. في الإنتاج يلزم Redis/مخزن مشترك مناسب أو ضبط التقييد عند الوكيل العكسي الموثوق، مع منع الالتفاف على IP الحقيقي. لا يُعتمد `X-Forwarded-For` غير الموثوق.
2. تجزئات BCrypt تحتاج اختبار parity بين C# وJava بواسطة fixtures آمنة.
3. `updates.audit_log` يرتبط بترحيل PostgreSQL رقم 046؛ يجب اختبار نسخة قاعدة بيانات محاكية فعلية قبل وصف التدقيق بالجاهز.
4. لا تزال إدارة كلمة مرور المستخدمين من الإدارة وتدوير جلسات متعددة والقياس الأمني مطلوبة قبل الإنتاج.
5. تغيير كلمة المرور الحالي يطابق الحد الأدنى في C# (8) ويضيف حدًا أقصى 512 لضمان عدم تعذر الدخول لاحقًا (اختلاف أمني مقصود).
6. لم يُنشر هذا الكود على خادم، ولم تُعدّل قاعدة بيانات حقيقية.


## PostgreSQL integration verification (Testcontainers)

`backend/src/test/java/io/github/waleed875/memo/modules/identity/infrastructure/IdentityPostgresIT.java`

- Tests start a **real PostgreSQL 16** Docker container and use the production JDBC adapters (not mocks/H2).
- Fixture SQL under `backend/src/test/resources/postgres-auth-schema.sql` is taken from
  source migrations **002, 015, 039 and 046** for just `auth.roles`,
  `auth.users`, `updates.releases`, `updates.audit_log`.
- Test cases: inactive/deleted accounts and inactive roles, normalized username
  uniqueness, BCrypt password change and authentication, audit rows, conditional
  hash updates under stale writes, old session stamp revocation.
- The suite runs under **Maven Failsafe** during `mvn verify`, which requires Docker.
  Running `mvn test` or `mvn package` exercises unit tests only.
- Building the preview Docker image does not launch nested Testcontainers;
  full integration verification is required in GitHub Actions.
- **Important limit**: this fixture is a *subset* of the legacy migrations, **not**
  a verification of every migration 001–070, nor C#-generated BCrypt vectors,
  nor end-to-end React or multi-node session behavior. Those remain open release gates.
