package io.github.waleed875.memo.modules.notes.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Supports explicit date / month formats understood by the old endpoint.
 * Invalid text is ignored as in ParseDateFlexible (not an authorization rule).
 * Locale-dependent .NET DateOnly.TryParse combinations need golden-master tests.
 */
public final class LegacySearchDateParser {
    private LegacySearchDateParser() {}

    public static LocalDate parse(String raw, boolean endOfPeriod) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();

        if (s.matches("\\d{4}[-/]\\d{1,2}")) {
            String[] parts = s.split("[-/]");
            return month(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), endOfPeriod);
        }
        if (s.matches("\\d{1,2}[-/]\\d{4}")) {
            String[] parts = s.split("[-/]");
            return month(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]), endOfPeriod);
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // Try additional explicit legacy spellings.
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("d/M/uuuu"));
        } catch (DateTimeParseException ignored) {
            // Try date-time string accepted by DateTime.TryParse in C#.
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDate month(int year, int month, boolean endOfPeriod) {
        try {
            YearMonth parsed = YearMonth.of(year, month);
            return endOfPeriod ? parsed.atEndOfMonth() : parsed.atDay(1);
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }
}
