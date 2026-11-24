package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NoteNumberNormalizerTest {
    @Test void stripsPunctuationAndUppercasesDisplayKeyLikeCSharp() {
        assertEquals("AB123", NoteNumberNormalizer.normalize(" ab-12 / 3 "));
        assertEquals("۱۲٣", NoteNumberNormalizer.normalize("۱۲ / ٣"));
        assertEquals("", NoteNumberNormalizer.normalize(" - / "));
        assertEquals("", NoteNumberNormalizer.normalize(null));
    }

    @Test void acceptsLatinArabicIndicAndPersianDigitsForNumericDuplicateKey() {
        assertEquals(123L, NoteNumberNormalizer.positiveNumericValue("١۲3").orElseThrow());
        assertEquals(123L, NoteNumberNormalizer.positiveNumericValue(" ۰۰۱۲۳ ").orElseThrow());
        assertEquals(7L, NoteNumberNormalizer.positiveNumericValue("007").orElseThrow());
        assertTrue(NoteNumberNormalizer.positiveNumericValue("0").isEmpty());
        assertTrue(NoteNumberNormalizer.positiveNumericValue("-5").isEmpty());
        assertTrue(NoteNumberNormalizer.positiveNumericValue("12/26").isEmpty());
        assertTrue(NoteNumberNormalizer.positiveNumericValue("9223372036854775808").isEmpty());
        assertTrue(NoteNumberNormalizer.positiveNumericValue("１２").isEmpty());
    }
}
