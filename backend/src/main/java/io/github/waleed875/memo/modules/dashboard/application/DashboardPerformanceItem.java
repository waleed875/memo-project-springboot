package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/**
 * Original C# PerformanceItem field names and denominators.
 * LateRate: late/eligible-time; AccuracyRate: clean/quality-eligible.
 * Null rate means no eligible observations, NOT zero performance.
 */
public record DashboardPerformanceItem(
    UUID dimensionId, String label,
    long totalNotes, long eligibleCount, long lateCount, long onTimeCount,
    long sampleCount, long correctCount, long errorCount, long qualityEligibleCount,
    Double lateRate, Double accuracyRate
) {}
