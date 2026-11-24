# معايير تطوير ومراجعة خادم Spring Boot

## قواعد إلزامية

1. كل وظيفة تقع داخل نطاق أعمال واضح في `modules/<name>`؛ لا تضف `Services` عامة تضم عشرات المهام.
2. Controller لا يستخدم `JdbcTemplate` أو `DataSource` أو `EntityManager` ولا يدخل في قرارات الأعمال؛ يربط HTTP بـ application.
3. Domain Java فقط ويعبر عن قواعد واضحة قابلة لاختبار وحدة دون Spring Context.
4. application مسؤولة عن authorization للموارد، transaction boundary، معالجة retries إن لزم؛ تستعمل Ports وليس implementation SQL.
5. Infrastructure تُعرّف SQL بشكل صريح عند الحاجة وتستخدم DTO خارج نطاق API؛ لا تنشئ جداول آليًا.
6. يُمنع استخدام `Optional.get()` غير المحمي، الاستثناءات غير المعيّنة للمستخدم، كلمات المرور في السجل، ووجود secrets في Git.
7. API متوافقة مع القديم عند نقلها؛ حدد بدقة status/JSON/timezones/errors/validation في كل PR.
8. يُسمّى إصدار صورة Docker بعلامة ثابتة أو digest، وتُجرى ترحيلات DB بإجراء موافقة منفصل.
9. كل bugfix يضيف اختبارًا يفشل قبل الإصلاح ويمر بعده؛ الثغرات الأمنية تُختبر ببيانات مستخدمين متغايرة.
10. يتطلب الدمج نجاح `mvn verify` وArchUnit ومراجعة أثر قاعدة البيانات والتوافق والأمن.

## قائمة مراجعة Pull Request

- نطاق الأعمال والمسؤولية واضحان؛ لا اعتماد على وحدة أخرى داخل `infrastructure`.
- الحالات العادية، الطرفية، والأخطاء لها اختبارات.
- تُختبر الملكية والأدوار وCSRF، خاصة للمرفقات وتعديلات الإدارة.
- لا تغييرات غير موثقة في JSON أو SQL أو صلاحيات المستخدمين.
- لا تعديل على المستودع المصدر `waleed875/memo_project`.
- أي نشر لـ production يحتاج خطة versioned image وDB backup وrollback.

## أدوات

- Maven verify: اختبارات وحدة، أمن HTTP، وArchUnit.
- GitHub PR + GitHub Actions: مراجعة تغييرات الهندسة (لم نفعّل حماية الفروع تلقائيًا).
- Testcontainers، OpenAPI comparison، وثائق الـ ADR: مراحل قادمة.
