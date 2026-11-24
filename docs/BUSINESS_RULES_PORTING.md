# تنفيذ أول قاعدة أعمال فعلية: حساب تأخر المذكرات

**المرجع:** `src/MemoDashboard.Web/Backend/Services/DelayCalculator.cs` داخل commit الأصلي `dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

تم نقل المنطق إلى Java مستقلًا عن HTTP وقاعدة البيانات، مع تماثل أسماء الفئات الأساسية:

| C# | Java |
|---|---|
| `Unknown` | `UNKNOWN` |
| `InvalidDateOrder` | `INVALID_DATE_ORDER` |
| `OnTime` | `ON_TIME` |
| `LateSameMonth` | `LATE_SAME_MONTH` |
| `LateCrossMonth` | `LATE_CROSS_MONTH` |

قواعد التطابق:
1. إذا كان تاريخ كتابة المذكرة غير متوفر، الحالة UNKNOWN ولا يوجد تأخر.
2. وصول قبل تاريخ الكتابة: INVALID_DATE_ORDER.
3. من صفر إلى خمسة أيام كاملة (120 ساعة) من وقت الكتابة: ON_TIME.
4. بعد خمسة أيام: LATE_SAME_MONTH أو LATE_CROSS_MONTH بناءً على الشهر والسنة.
5. عدد أيام التأخر هو المدة الكلية **حتى تاريخ الوصول** (ليس طرح خمسة أيام)، ويقرب منزلة عشرية واحدة بطريقة `HALF_EVEN` التي تطابق `Math.Round` الافتراضية في C#.
6. تقارن الأوقات دون تحويل منطقة زمنية، بما يطابق التعامل الحسابي مع `DateTime` المحلي في الكود الأصلي. يحتاج تحديد سياسة تخزين timestamp وtimezone في JDBC قبل الاستخدام على الإنتاج.

أُضيفت اختبارات للحالات المذكورة. لا توجد واجهة HTTP جديدة لهذه القاعدة بعد، ومطابقتها على بيانات حقيقية لم تُختبر.

## موضع الكود بعد اعتماد المعمارية

- `backend/src/main/java/io/github/waleed875/memo/modules/notes/domain/DelayCalculator.java`
- `backend/src/main/java/io/github/waleed875/memo/modules/notes/domain/DelayClass.java`

الاختبارات المطابقة في `backend/src/test/java/io/github/waleed875/memo/modules/notes/domain/`، وتُفحص استقلالية نطاق الأعمال بواسطة ArchUnit.
