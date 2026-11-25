package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;

/**
 * Framework-independent administrator-only delete use case.
 * Does not expose the legacy public HTTP endpoint or accept actor IDs in
 * commands; SessionUserResolver supplies a DB-revalidated user.
 */
public final class AdminNoteAttachmentDeleteService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final AdminNoteAttachmentDeleteRepository repository;
    private final MasterDataWriteGate writeGate;

    public AdminNoteAttachmentDeleteService(
        AdminNoteAttachmentDeleteRepository repository, MasterDataWriteGate writeGate
    ) {
        this.repository = repository;
        this.writeGate = writeGate;
    }

    public AdminNoteAttachmentDeleteRepository.DeleteResult delete(
        UserProfile actor, AdminNoteAttachmentDeleteCommand command
    ) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException invalid) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        writeGate.requireWritable();
        if (command == null || command.noteId() == null
            || command.attachmentId() == null
            || EMPTY.equals(command.noteId()) || EMPTY.equals(command.attachmentId())
            || command.expectedUpdatedAt() == null) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة والمرفق ونسخة التعديل مطلوبة.");
        }
        return repository.delete(
            command.noteId(), command.attachmentId(), command.expectedUpdatedAt(), actor.id());
    }
}
