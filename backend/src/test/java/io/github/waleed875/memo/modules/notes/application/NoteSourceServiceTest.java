package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceType;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteSourceServiceTest {
    UUID owner = UUID.randomUUID(), note = UUID.randomUUID();

    class Metadata implements NoteDetailsReader {
        int calls;
        @Override public Optional<NoteOwnership> ownership(UUID id) {
            calls++;
            return Optional.of(new NoteOwnership(owner));
        }
        @Override public Optional<io.github.waleed875.memo.modules.notes.domain.NoteDetails>
        find(UUID id, UUID actor, boolean ownOnly) {
            throw new AssertionError("Never load attachment data to authorize source read");
        }
    }
    static class Store implements NoteSourceStore {
        int reads, writes, deletes;
        boolean ownOnly;
        @Override public Optional<NoteSourceView> read(UUID id, UUID actor, boolean scoped) {
            reads++; ownOnly = scoped; return Optional.empty();
        }
        @Override public void save(UUID id, UUID actor, boolean scoped, NoteSourceDraft draft) {
            writes++; ownOnly = scoped;
        }
        @Override public void remove(UUID id, UUID actor, boolean scoped) {
            deletes++; ownOnly = scoped;
        }
    }
    UserProfile user(UUID id, String role) {
        return new UserProfile(id, "u", "User", role);
    }
    NoteSourceDraft loot() {
        return new NoteSourceDraft(NoteSourceType.Loot,null,"غنائم",List.of(),List.of());
    }

    @Test void anonymousAndViewerNeverCallGateOrWriteStore() {
        var store = new Store();
        int[] gates = {0};
        var svc = new NoteSourceService(new Metadata(), store, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> svc.save(null, note, loot()));
        assertThrows(MasterDataForbiddenException.class,
            () -> svc.save(user(owner,"VIEWER"), note, loot()));
        assertThrows(MasterDataForbiddenException.class,
            () -> svc.remove(user(owner,"REVIEWER"), note));
        assertEquals(0, gates[0]);
        assertEquals(0, store.writes + store.deletes);
    }

    @Test void readChecksOwnershipWithoutLoadingWholeNote() {
        var store = new Store();
        var svc = new NoteSourceService(new Metadata(),store,()->{});
        assertThrows(NoteAccessDenied.class,
            () -> svc.read(user(UUID.randomUUID(),"DATA_ENTRY"),note));
        assertEquals(0, store.reads);
        assertTrue(svc.read(user(owner,"DATA_ENTRY"),note).isEmpty());
        assertTrue(store.ownOnly);
        svc.read(user(UUID.randomUUID(),"ADMIN"),note);
        assertFalse(store.ownOnly);
    }

    @Test void readOnlyGateAndValidationBlockMutation() {
        var store = new Store();
        var svc = new NoteSourceService(new Metadata(),store,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> svc.save(user(owner,"ADMIN"),note,loot()));
        assertEquals(0,store.writes);
        var editable = new NoteSourceService(new Metadata(),store,()->{});
        assertThrows(io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException.class,
            () -> editable.save(user(owner,"ADMIN"),note,
                new NoteSourceDraft(NoteSourceType.Exit,null,null,List.of(),List.of())));
        assertEquals(0,store.writes);
        editable.save(user(owner,"DATA_ENTRY"),note,loot());
        assertTrue(store.ownOnly);
        assertEquals(1,store.writes);
    }
}
