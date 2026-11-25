package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminNoteAttachmentBatchUploadServiceTest {
    static final class Recorder implements AdminNoteAttachmentBatchUploadRepository {
        int calls;
        UUID actor;
        public BatchResult upload(AdminNoteAttachmentBatchUploadCommand cmd, UUID user) {
            calls++;
            actor = user;
            return new BatchResult(List.of(), cmd.expectedUpdatedAt());
        }
    }
    static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }
    static NoteAttachment pdf(String name) {
        return new NoteAttachment(name,
            ("%PDF-1.4\n" + name + "\n%%EOF").getBytes(StandardCharsets.US_ASCII));
    }
    static AdminNoteAttachmentBatchUploadCommand command(List<NoteAttachment> files) {
        return new AdminNoteAttachmentBatchUploadCommand(
            UUID.randomUUID(), LocalDateTime.parse("2026-11-24T09:00:00"), files);
    }

    @Test void anonymousOrNonAdminNeverReachesGateOrRepository() {
        Recorder r = new Recorder();
        int[] gates = {0};
        var service = new AdminNoteAttachmentBatchUploadService(r, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.upload(null, command(List.of(pdf("a.pdf")))));
        for (String role : List.of("VIEWER", "REVIEWER", "DATA_ENTRY", "UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,
                () -> service.upload(actor(role), command(List.of(pdf("a.pdf")))));
        }
        assertEquals(0, gates[0]);
        assertEquals(0, r.calls);
    }

    @Test void failClosedGateAndInvalidBatchPreventsPartialWrite() {
        Recorder r = new Recorder();
        var closed = new AdminNoteAttachmentBatchUploadService(r,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> closed.upload(actor("ADMIN"), command(List.of(pdf("a.pdf")))));
        var open = new AdminNoteAttachmentBatchUploadService(r, () -> {});
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(actor("ADMIN"), command(List.of())));
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(actor("ADMIN"), command(null)));
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(actor("ADMIN"), command(List.of(
                pdf("good.pdf"), new NoteAttachment("bad.pdf", new byte[]{1, 2, 3})))));
        assertThrows(NoteSaveValidationException.class,
            () -> open.upload(actor("ADMIN"), command(
                java.util.Collections.singletonList(null))));
        assertEquals(0, r.calls);
    }

    @Test void boundedBatchAndAuthenticatedAdminIdentity() {
        Recorder r = new Recorder();
        var service = new AdminNoteAttachmentBatchUploadService(r, () -> {});
        UserProfile admin = actor("ADMIN");
        var files = java.util.stream.IntStream.range(0, 20)
            .mapToObj(i -> pdf("file" + i + ".pdf")).toList();
        service.upload(admin, command(files));
        assertEquals(admin.id(), r.actor);
        assertEquals(1, r.calls);
        var tooMany = java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> pdf("file" + i + ".pdf")).toList();
        assertThrows(NoteSaveValidationException.class,
            () -> service.upload(admin, command(tooMany)));
        assertEquals(1, r.calls);
    }
}
