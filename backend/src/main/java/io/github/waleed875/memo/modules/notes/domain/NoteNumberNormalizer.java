package io.github.waleed875.memo.modules.notes.domain;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Port of C# NoteNumberNormalizer, including Arabic-Indic and Persian digits.
 * Normalized display keys intentionally differ from numeric duplicate keys.
 */
public final class NoteNumberNormalizer {
    private static final String ARABIC = "٠١٢٣٤٥٦٧٨٩";
    private static final String PERSIAN = "۰۱۲۳۴۵۶۷۸۹";

    private NoteNumberNormalizer() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(Character::isLetterOrDigit).forEach(cp ->
            result.appendCodePoint(cp));
        return result.toString().toUpperCase(Locale.ROOT);
    }

    public static OptionalLong positiveNumericValue(String value) {
        if (value == null || value.isBlank()) return OptionalLong.empty();
        String trimmed = value.trim();
        StringBuilder ascii = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char digit = trimmed.charAt(i);
            int ar = ARABIC.indexOf(digit);
            int fa = PERSIAN.indexOf(digit);
            if (ar >= 0) ascii.append((char) ('0' + ar));
            else if (fa >= 0) ascii.append((char) ('0' + fa));
            else if (digit >= '0' && digit <= '9') ascii.append(digit);
            else return OptionalLong.empty();
        }
        try {
            long number = Long.parseLong(ascii.toString());
            return number > 0 ? OptionalLong.of(number) : OptionalLong.empty();
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }
}
