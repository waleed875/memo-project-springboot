package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminNoteAttachmentUploadServiceTest {
    static class Recorder implements AdminNoteAttachmentUploadRepository {
        int writes;
        UUID actor;
        public UploadResult upload(UUID id, LocalDateTime version, NoteAttachment file, UUID user) {
            writes++;
            actor = user;
            return new UploadResult(UUID.randomUUID(), version, "application/pdf", file.size());
        }
    }

    static UserProfile profile(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }

    static NoteAttachment pdf() {
        return new NoteAttachment("proof.pdf",
            "%PDF-1.4\n1 0 obj\nendobj\n%%EOF\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static AdminNoteAttachmentUploadCommand command(NoteAttachment attachment) {
        return new AdminNoteAttachmentUploadCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-24T11:00:00"), attachment);
    }

    @Test void rejectsUnauthenticatedNonAdminAndInvalidRoleBeforeGate() {
        var repo = new Recorder();
        int[] gates = {0};
        var service = new AdminNoteAttachmentUploadService(repo, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.upload(null, command(pdf())));
        for (String role : new String[]{"REVIEWER", "DATA_ENTRY", "VIEWER", "UNKNOWN"}) {
            assertThrows(MasterDataForbiddenException.class,
                () -> service.upload(profile(role), command(pdf())));
        }
        assertEquals(0, repo.writes);
        assertEquals(0, gates[0]);
    }

    @Test void readOnlyAndMalformedFileFailBeforeDatabase() {
        var repo = new Recorder();
        var closed = new AdminNoteAttachmentUploadService(repo,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> closed.upload(profile("ADMIN"), command(pdf())));
        var open = new AdminNoteAttachmentUploadService(repo, () -> {});
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(profile("ADMIN"), command(new NoteAttachment("../bad.pdf",
                "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))));
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(profile("ADMIN"), command(new NoteAttachment("empty.pdf", new byte[0]))));
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(profile("ADMIN"),
                new AdminNoteAttachmentUploadCommand(UUID.randomUUID(), null, pdf())));
        assertEquals(0, repo.writes);
    }

    @Test void usesSessionAdminIdentityForValidFile() {
        var repo = new Recorder();
        var service = new AdminNoteAttachmentUploadService(repo, () -> {});
        UserProfile admin = profile("ADMIN");
        var result = service.upload(admin, command(pdf()));
        assertEquals(admin.id(), repo.actor);
        assertEquals("application/pdf", result.contentType());
        assertEquals(1, repo.writes);
    }
}
