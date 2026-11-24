package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDeleteServiceTest {
    static class Recorder implements NoteDeleteRepository {
        int deletes;
        @Override public void delete(UUID id, UUID actor, LocalDateTime stamp) { deletes++; }
    }
    static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 24, 11, 0);
    private UserProfile user(String role) {
        return new UserProfile(UUID.randomUUID(), "test", "Test", role);
    }

    @Test void anonymousAndNonAdminCannotReachGateOrRepository() {
        Recorder r = new Recorder();
        int[] gateCalls = {0};
        NoteDeleteService service = new NoteDeleteService(r, () -> gateCalls[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.delete(null, UUID.randomUUID(), STAMP));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.delete(user("VIEWER"), UUID.randomUUID(), STAMP));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.delete(user("DATA_ENTRY"), UUID.randomUUID(), STAMP));
        assertEquals(0, gateCalls[0]);
        assertEquals(0, r.deletes);
    }

    @Test void closedWriteGateBlocksAdminBeforeDelete() {
        Recorder r = new Recorder();
        NoteDeleteService service = new NoteDeleteService(r,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.delete(user("ADMIN"), UUID.randomUUID(), STAMP));
        assertEquals(0, r.deletes);
    }

    @Test void validationRequiresVersionAndIdBeforeRepository() {
        Recorder r = new Recorder();
        NoteDeleteService service = new NoteDeleteService(r, () -> {});
        assertThrows(MasterDataOperationException.class,
            () -> service.delete(user("ADMIN"), UUID.randomUUID(), null));
        assertThrows(MasterDataOperationException.class,
            () -> service.delete(user("ADMIN"), null, STAMP));
        assertEquals(0, r.deletes);
        service.delete(user("ADMIN"), UUID.randomUUID(), STAMP);
        assertEquals(1, r.deletes);
    }
}
