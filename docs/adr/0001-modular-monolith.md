# ADR-0001 — اختيار Modular Monolith

**الحالة:** Accepted

## السياق

الخادم الأصلي ASP.NET Core 8 يحتوي على خدمات مذكرات وتقارير وصلاحيات وقاعدة PostgreSQL مشتركة. إعادة الكتابة إلى Java لا تعني أن فصلها إلى خدمات مصغرة مناسب؛ البيانات والمعاملات والتقارير مترابطة، ونشر Docker/Portainer يعمل كذلك مع تطبيق واحد.

## القرار

- نعتمد Modular Monolith داخل Spring Boot، بحزم حسب نطاق الأعمال.
- كل نطاق يملك domain/application/api/infrastructure الخاصة به عند نقل الوظائف.
- Domain مستقل عن Spring/HTTP/PostgreSQL؛ قواعد الربط مراقبة بواسطة ArchUnit.
- توحيد نشر الحاوية لا يمنع استخراج خدمة مستقلة مستقبلًا عندما توجد حاجة تشغيلية مثبتة.
- Security والـ observability وDocker في طبقة platform/deploy، وليست منطق أعمال.

## النتائج

الإيجابي: اختبار مستقل، معاملات بسيطة، تغييرات محسوبة، نشر أقل تعقيدًا من Microservices.

المقايضات: التطبيق يُنشر كوحدة واحدة؛ يتطلب انضباطًا في حدود الوحدات واختبارات تمنع التداخل. لن نضيف CQRS buses أو Events أو Message Broker دون احتياج وظيفي واضح.

## المراجع

- [ARCHITECTURE.md](../ARCHITECTURE.md)
- [خطة النقل](../MIGRATION_PLAN.md)
