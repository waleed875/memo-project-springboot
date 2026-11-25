package io.github.waleed875.memo.modules.dashboard.application;

/** Numeric 0–6 states and Arabic labels from the original StatusTranslations.cs. */
public final class DashboardIndicatorLabels {
    private DashboardIndicatorLabels() {}
    public static String state(int code) {
        return switch (code) {
            case 0 -> "لم تتم المراجعة";
            case 1 -> "صحيح";
            case 2 -> "غير صحيح";
            case 3 -> "مفقود";
            case 4 -> "غير واضح";
            case 5 -> "غير مكتمل";
            case 6 -> "لا ينطبق";
            default -> "غير معروف";
        };
    }
}
