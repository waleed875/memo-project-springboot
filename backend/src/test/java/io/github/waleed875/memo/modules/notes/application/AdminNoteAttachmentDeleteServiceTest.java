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

class AdminNoteAttachmentDeleteServiceTest {
    static class Recorder implements AdminNoteAttachmentDeleteRepository {
        int calls;
        UUID actor;
        @Override
        public DeleteResult delete(UUID note, UUID file, LocalDateTime version, UUID user) {
            calls++; actor = user;
            return new DeleteResult(file, "deleted.pdf", null, version);
        }
    }
    private static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }
    private static AdminNoteAttachmentDeleteCommand command() {
        return new AdminNoteAttachmentDeleteCommand(
            UUID.randomUUID(), UUID.randomUUID(),
            LocalDateTime.parse("2026-11-24T11:00:00"));
    }

    @Test void onlyValidAdminMayReachWriteGate() {
        Recorder r = new Recorder();
        int[] gates = {0};
        var service = new AdminNoteAttachmentDeleteService(r, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.delete(null, command()));
        for (String role : new String[]{"REVIEWER", "DATA_ENTRY", "VIEWER", "UNKNOWN"}) {
            assertThrows(MasterDataForbiddenException.class,
                () -> service.delete(actor(role), command()));
        }
        assertEquals(0, gates[0]);
        assertEquals(0, r.calls);
    }

    @Test void readonlyOrMalformedCommandNeverCallsRepository() {
        Recorder r = new Recorder();
        var readonly = new AdminNoteAttachmentDeleteService(
            r, () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> readonly.delete(actor("ADMIN"), command()));
        var service = new AdminNoteAttachmentDeleteService(r, () -> {});
        assertThrows(NoteSaveValidationException.class, () -> service.delete(
            actor("ADMIN"), new AdminNoteAttachmentDeleteCommand(
                UUID.randomUUID(), null, LocalDateTime.now())));
        assertThrows(NoteSaveValidationException.class, () -> service.delete(
            actor("ADMIN"), new AdminNoteAttachmentDeleteCommand(
                UUID.randomUUID(), UUID.randomUUID(), null)));
        assertEquals(0, r.calls);
    }

    @Test void derivesActorIdentityFromAuthenticatedUser() {
        Recorder r = new Recorder();
        var service = new AdminNoteAttachmentDeleteService(r, () -> {});
        UserProfile admin = actor("ADMIN");
        var cmd = command();
        assertEquals(cmd.attachmentId(), service.delete(admin, cmd).attachmentId());
        assertEquals(admin.id(), r.actor);
        assertEquals(1, r.calls);
    }
}
