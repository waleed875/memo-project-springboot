package io.github.waleed875.memo.modules.dashboard.application;

import java.time.YearMonth;
import java.util.Map;

/** Labels reproduced from pinned original StatusTranslations.cs. */
public final class DashboardDistributionLabels {
    private DashboardDistributionLabels() {}
    private static final String[] MONTHS = {
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    };
    private static final Map<String,String> STATUSES = Map.of(
        "DRAFT", "مسودة",
        "COMPLETE", "مكتملة",
        "HAS_ERRORS", "تحتوي أخطاء",
        "NEEDS_REVIEW", "تحتاج مراجعة",
        "UNDER_REVIEW", "قيد التدقيق",
        "RETURNED_FOR_CORRECTION", "معادة للتصحيح",
        "APPROVED", "معتمدة"
    );
    public static String month(YearMonth value) {
        return MONTHS[value.getMonthValue() - 1] + " " + value.getYear();
    }
    public static String status(String value) {
        return STATUSES.getOrDefault(value, "غير معروف");
    }
}
