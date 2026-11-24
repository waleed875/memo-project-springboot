package io.github.waleed875.memo.modules.notes.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LegacySearchDateParserTest {
    @Test void monthRangesMatchTheLegacyEndpoint() {
        assertEquals(LocalDate.of(2026, 2, 1), LegacySearchDateParser.parse("2026-02", false));
        assertEquals(LocalDate.of(2026, 2, 28), LegacySearchDateParser.parse("2026/2", true));
        assertEquals(LocalDate.of(2024, 2, 29), LegacySearchDateParser.parse("02/2024", true));
        assertEquals(LocalDate.of(2026, 11, 24), LegacySearchDateParser.parse("2026-11-24", false));
        assertEquals(LocalDate.of(2026, 11, 24), LegacySearchDateParser.parse("2026-11-24T10:11:12", true));
    }
    @Test void malformedRangesAreIgnoredLikeTheLegacyParser() {
        assertNull(LegacySearchDateParser.parse("2026-13", false));
        assertNull(LegacySearchDateParser.parse("not-date", false));
        assertNull(LegacySearchDateParser.parse(null, false));
    }
}
