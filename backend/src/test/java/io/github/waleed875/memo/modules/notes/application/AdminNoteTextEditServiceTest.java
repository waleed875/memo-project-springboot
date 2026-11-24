package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminNoteTextEditServiceTest {
    static class Recorder implements AdminNoteTextEditRepository {
        int writes;
        UUID seen;
        @Override public LocalDateTime updateText(AdminNoteTextEditCommand c, UUID actor) {
            writes++; seen = actor;
            return c.expectedUpdatedAt();
        }
    }
    static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "u", "User", role);
    }
    static AdminNoteTextEditCommand command() {
        return new AdminNoteTextEditCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-24T10:00:00"), "Updated");
    }

    @Test void unknownOrNonAdminDoesNotOpenWriteGateOrRepository() {
        Recorder r = new Recorder();
        int[] gates = {0};
        var service = new AdminNoteTextEditService(r, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.updateText(null, command()));
        for (String role : new String[]{"REVIEWER", "DATA_ENTRY", "VIEWER", "UNKNOWN"}) {
            assertThrows(MasterDataForbiddenException.class,
                () -> service.updateText(actor(role), command()));
        }
        assertEquals(0, gates[0]);
        assertEquals(0, r.writes);
    }

    @Test void readOnlyGateAndStaleCommandValidationAreFailClosed() {
        Recorder r = new Recorder();
        var service = new AdminNoteTextEditService(r,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class, () ->
            service.updateText(actor("ADMIN"), command()));
        var active = new AdminNoteTextEditService(r, () -> {});
        assertThrows(NoteSaveValidationException.class, () ->
            active.updateText(actor("ADMIN"), new AdminNoteTextEditCommand(UUID.randomUUID(), null, "x")));
        assertEquals(0, r.writes);
    }

    @Test void passesAuthenticatedAdminIdAndSupportsClearingOptionalText() {
        Recorder r = new Recorder();
        var service = new AdminNoteTextEditService(r, () -> {});
        UserProfile admin = actor("ADMIN");
        var cmd = new AdminNoteTextEditCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-24T10:00:00"), null);
        service.updateText(admin, cmd);
        assertEquals(admin.id(), r.seen);
        assertEquals(1, r.writes);
    }
}
