package io.github.waleed875.memo.modules.dashboard.application;
/** Invalid internal summary filter; mapped to HTTP 400. */
public final class InvalidDashboardSummaryRequest extends RuntimeException {
    public InvalidDashboardSummaryRequest(String message) { super(message); }
}
