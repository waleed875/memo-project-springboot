package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;

/**
 * Narrow internal command. Only admins can append one validated file; the
 * legacy public POST multipart endpoint is intentionally not exposed here.
 */
public final class AdminNoteAttachmentUploadService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final AdminNoteAttachmentUploadRepository repository;
    private final MasterDataWriteGate writeGate;

    public AdminNoteAttachmentUploadService(
        AdminNoteAttachmentUploadRepository repository, MasterDataWriteGate writeGate
    ) {
        this.repository = repository;
        this.writeGate = writeGate;
    }

    public AdminNoteAttachmentUploadRepository.UploadResult upload(
        UserProfile actor, AdminNoteAttachmentUploadCommand command
    ) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ex) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        writeGate.requireWritable();
        if (command == null || command.noteId() == null || EMPTY.equals(command.noteId())
            || command.expectedUpdatedAt() == null || command.attachment() == null) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة ونسخة التعديل والمرفق مطلوبة.");
        }
        // Content signature, extension and 10 MiB limits: ported C# policy.
        NoteAttachmentPolicy.validate(command.attachment());
        return repository.upload(
            command.noteId(), command.expectedUpdatedAt(), command.attachment(), actor.id());
    }
}
