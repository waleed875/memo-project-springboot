package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteSearchServiceTest {
    static class Reader implements NoteSearchReader {
        int calls;
        NoteSearchCriteria last;
        @Override public PagedNotes search(NoteSearchCriteria c) {
            calls++;
            last = c;
            return PagedNotes.of(List.of(), 0, c.pageNumber(), c.pageSize());
        }
    }
    private NoteSearchCriteria criteria(UUID requestedCreator, LocalDate from, LocalDate to,
                                        int page, int size) {
        return new NoteSearchCriteria(null, null, null, null, null, requestedCreator, null,
            from, to, "ArrivalDate", null, null, null, null, null, page, size);
    }

    @Test void invalidSessionFailsBeforeRepositoryAccess() {
        Reader r = new Reader();
        NoteSearchService svc = new NoteSearchService(r);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> svc.search(null, criteria(null, null, null, 1, 25)));
        assertThrows(NoteAccessDenied.class, () -> svc.search(
            new UserProfile(UUID.randomUUID(), "bad", "Bad", "UNKNOWN"),
            criteria(null, null, null, 1, 25)));
        assertEquals(0, r.calls);
    }

    @Test void dataEntryCannotOverrideCreatedByEvenIfRequestSaysOtherwise() {
        UUID actorId = UUID.randomUUID(), foreign = UUID.randomUUID();
        Reader reader = new Reader();
        new NoteSearchService(reader).search(
            new UserProfile(actorId, "entry", "Entry", "DATA_ENTRY"),
            criteria(foreign, null, null, -50, 500));
        assertEquals(actorId, reader.last.createdBy());
        assertEquals(1, reader.last.pageNumber());
        assertEquals(200, reader.last.pageSize());
    }

    @Test void adminsMayFilterOtherCreatorsAndDefaultDatesAreWide() {
        UUID creator = UUID.randomUUID();
        Reader reader = new Reader();
        new NoteSearchService(reader).search(
            new UserProfile(UUID.randomUUID(), "admin", "Admin", "ADMIN"),
            criteria(creator, null, null, 2, 10));
        assertEquals(creator, reader.last.createdBy());
        assertEquals(NoteSearchService.DEFAULT_FROM, reader.last.from());
        assertEquals(NoteSearchService.DEFAULT_TO, reader.last.to());
    }

    @Test void reversedDateRangeIsRejectedWithoutDatabaseCall() {
        Reader reader = new Reader();
        NoteSearchService service = new NoteSearchService(reader);
        assertThrows(InvalidNoteSearch.class, () -> service.search(
            new UserProfile(UUID.randomUUID(), "admin", "Admin", "ADMIN"),
            criteria(null, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 1), 1, 25)));
        assertEquals(0, reader.calls);
    }
}
