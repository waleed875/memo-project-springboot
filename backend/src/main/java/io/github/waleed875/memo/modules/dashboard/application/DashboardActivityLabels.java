package io.github.waleed875.memo.modules.dashboard.application;

/** C# StatusTranslations.ClassificationLabel and BacklogBucketLabel. */
public final class DashboardActivityLabels {
    private DashboardActivityLabels() {}
    public static String duplicate(String classification) {
        return switch (classification) {
            case "COMPANY_RESEND" -> "إعادة إرسال من الشركة";
            case "COMPANY_DUPLICATE_NUMBER" -> "أرقام مكررة";
            default -> "غير مصنف";
        };
    }
    public static String backlog(String code) {
        return switch (code) {
            case "lt-1-day" -> "أقل من يوم";
            case "1-3-days" -> "1-3 أيام";
            case "4-7-days" -> "4-7 أيام";
            default -> "أكثر من 7 أيام";
        };
    }
}
