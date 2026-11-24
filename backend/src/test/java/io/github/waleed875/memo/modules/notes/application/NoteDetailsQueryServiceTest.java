package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteDetails;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDetailsQueryServiceTest {
    static class CountingReader implements NoteDetailsReader {
        int ownerReads, fullReads;
        UUID owner;
        CountingReader(UUID owner) { this.owner = owner; }
        @Override public Optional<NoteOwnership> ownership(UUID noteId) {
            ownerReads++;
            return Optional.ofNullable(owner).map(NoteOwnership::new);
        }
        @Override public Optional<NoteDetails> find(UUID id, UUID actor, boolean ownOnly) {
            fullReads++;
            return Optional.empty();
        }
    }
    static UserProfile profile(UUID id, String role) {
        return new UserProfile(id, "test", "Test User", role);
    }

    @Test void unauthenticatedRequestCannotFetchOwnershipOrContent() {
        CountingReader reader = new CountingReader(UUID.randomUUID());
        var service = new NoteDetailsQueryService(reader, new NoteReadPolicy());
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.find(null, UUID.randomUUID()));
        assertEquals(0, reader.ownerReads);
        assertEquals(0, reader.fullReads);
    }

    @Test void foreignDataEntryNeverLoadsAttachmentsOrFullRecord() {
        UUID owner = UUID.randomUUID();
        CountingReader reader = new CountingReader(owner);
        var service = new NoteDetailsQueryService(reader, new NoteReadPolicy());
        assertThrows(NoteAccessDenied.class, () -> service.find(
            profile(UUID.randomUUID(), "DATA_ENTRY"), UUID.randomUUID()));
        assertEquals(1, reader.ownerReads);
        assertEquals(0, reader.fullReads);
    }

    @Test void elevatedRoleCanLoadOwnedByOtherUserAndDataEntryOwnsOnly() {
        UUID owner = UUID.randomUUID();
        CountingReader reader = new CountingReader(owner);
        var service = new NoteDetailsQueryService(reader, new NoteReadPolicy());
        service.find(profile(owner, "DATA_ENTRY"), UUID.randomUUID());
        service.find(profile(UUID.randomUUID(), "VIEWER"), UUID.randomUUID());
        assertEquals(2, reader.fullReads);
    }

    @Test void missingNoteAndUnknownRoleFailClosed() {
        CountingReader missing = new CountingReader(null);
        var service = new NoteDetailsQueryService(missing, new NoteReadPolicy());
        assertTrue(service.find(profile(UUID.randomUUID(), "VIEWER"), UUID.randomUUID()).isEmpty());
        assertEquals(0, missing.fullReads);
        CountingReader known = new CountingReader(UUID.randomUUID());
        var knownService = new NoteDetailsQueryService(known, new NoteReadPolicy());
        assertThrows(NoteAccessDenied.class, () -> knownService.find(
            profile(UUID.randomUUID(), "UNKNOWN"), UUID.randomUUID()));
        assertEquals(0, known.ownerReads);
    }
}
