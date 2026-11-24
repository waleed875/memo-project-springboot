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

## ما لم يُنفذ — ممنوع استخدامه إنتاجيًا

1. **تغيير كلمة المرور** `/api/auth/change-password` لم يُنقل؛ لذلك الحسابات التي تطلب تغيير كلمة المرور لا تستطيع إكمال عملية الدخول بعد.
2. **تسجيل محاولات الدخول والنجاح والخروج** في سجل التدقيق لم يُنقل.
3. **تحديد معدل محاولات الدخول** (rate limiting / lockout) لم يُنقل.
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
