package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;

/**
 * Restrictive admin-only internal slice: validate the WHOLE request before any
 * persistence, unlike the legacy multipart handler which may partially commit.
 */
public final class AdminNoteAttachmentBatchUploadService {
    public static final int MAX_FILES = 20;
    public static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
    private static final UUID ZERO = new UUID(0L, 0L);
    private final AdminNoteAttachmentBatchUploadRepository repository;
    private final MasterDataWriteGate writeGate;

    public AdminNoteAttachmentBatchUploadService(
        AdminNoteAttachmentBatchUploadRepository repository, MasterDataWriteGate writeGate
    ) {
        this.repository = repository;
        this.writeGate = writeGate;
    }

    public AdminNoteAttachmentBatchUploadRepository.BatchResult upload(
        UserProfile actor, AdminNoteAttachmentBatchUploadCommand command
    ) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ignored) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        writeGate.requireWritable();
        if (command == null || command.noteId() == null || ZERO.equals(command.noteId())
            || command.expectedUpdatedAt() == null || command.attachments() == null
            || command.attachments().isEmpty() || command.attachments().size() > MAX_FILES) {
            throw new NoteSaveValidationException(
                "يجب تحديد مذكرة ونسخة تعديل صالحة و1 إلى 20 مرفقاً.");
        }
        long total = 0;
        for (NoteAttachment file : command.attachments()) {
            if (file == null) {
                throw new NoteSaveValidationException("أحد المرفقات غير صالح.");
            }
            NoteAttachmentPolicy.validate(file);
            total += file.size();
            if (total > MAX_TOTAL_BYTES) {
                throw new NoteSaveValidationException(
                    "يتجاوز مجموع أحجام المرفقات الحد المسموح (40 ميغابايت).");
            }
        }
        return repository.upload(command, actor.id());
    }
}
