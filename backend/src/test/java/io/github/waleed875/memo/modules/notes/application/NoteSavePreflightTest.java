package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteSavePreflightTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-11-24T22:00:00Z"), ZoneId.of("Asia/Damascus"));
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIELD = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID EXISTING = UUID.randomUUID();
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 24, 10, 0);

    static class Stub implements NoteSaveCatalog {
        int reads;
        NoteStatus status = NoteStatus.DRAFT;
        UUID createdBy = OWNER;
        @Override public List<RequiredNoteField> activeFields(UUID id) {
            reads++;
            return List.of(new RequiredNoteField(FIELD, "المؤشر", true, true));
        }
        @Override public Optional<ExistingNote> existing(UUID id) {
            reads++;
            return Optional.of(new ExistingNote(id, createdBy, status, STAMP));
        }
    }

    private UserProfile user(String role, UUID id) {
        return new UserProfile(id, "user", "User", role);
    }
    private NoteSaveCandidate candidate(String number, boolean draft) {
        return new NoteSaveCandidate(number, COMPANY, TYPE, null, null,
            LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24),
            draft, false, List.of(new NoteSaveCandidate.IndicatorInput(
                FIELD, IndicatorValue.Correct)));
    }

    @Test void anonymousAndViewerNeverReachGateOrCatalog() {
        Stub s = new Stub();
        int[] gateCalls = {0};
        var service = new NoteSavePreflight(s, () -> gateCalls[0]++, CLOCK);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.check(null, null, null, candidate("123", false)));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.check(user("VIEWER", OWNER), null, null, candidate("123", false)));
        assertEquals(0, gateCalls[0]);
        assertEquals(0, s.reads);
    }

    @Test void localReadOnlyBlocksBeforeCatalog() {
        Stub s = new Stub();
        var service = new NoteSavePreflight(s,
            () -> { throw new MasterDataReadOnlyException(); }, CLOCK);
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.check(user("ADMIN", OWNER), null, null, candidate("123", false)));
        assertEquals(0, s.reads);
    }

    @Test void dataEntryCannotPreflightForeignNotes() {
        Stub s = new Stub();
        var service = new NoteSavePreflight(s, () -> {}, CLOCK);
        assertThrows(NoteAccessDenied.class, () ->
            service.check(user("DATA_ENTRY", UUID.randomUUID()),
                EXISTING, STAMP, candidate("123", false)));
        assertEquals(1, s.reads);
    }

    @Test void cannotEditWithStaleTimestampOrSubmitReviewedNotesAsDataEntry() {
        Stub s = new Stub();
        var service = new NoteSavePreflight(s, () -> {}, CLOCK);
        assertThrows(NoteDeleteConflict.class, () ->
            service.check(user("ADMIN", OWNER), EXISTING,
                STAMP.minusDays(1), candidate("123", false)));
        s.status = NoteStatus.UNDER_REVIEW;
        assertThrows(NoteSaveValidationException.class, () ->
            service.check(user("DATA_ENTRY", OWNER), EXISTING,
                STAMP, candidate("123", false)));
    }

    @Test void correctSubmissionGetsNumericKeyStatusAndDamascusBusinessDate() {
        Stub s = new Stub();
        var service = new NoteSavePreflight(s, () -> {}, CLOCK);
        var result = service.check(
            user("DATA_ENTRY", OWNER), null, null, candidate(" ١۲٣ ", false));
        assertEquals(123L, result.numericNoteNumber());
        assertEquals(NoteStatus.COMPLETE, result.computedStatus());
        assertEquals(LocalDate.of(2026, 11, 25), result.operationalDate());
        assertEquals(1, s.reads);
    }

    @Test void nonNumericNewNoteIsRejectedUnderMigration062() {
        Stub s = new Stub();
        var service = new NoteSavePreflight(s, () -> {}, CLOCK);
        assertThrows(NoteSaveValidationException.class, () ->
            service.check(user("ADMIN", OWNER), null, null, candidate("AB/123", true)));
    }
}
