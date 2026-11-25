package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.time.LocalDateTime;
import java.util.UUID;

/** Transactional port for the legacy single-file append and primary sync. */
public interface AdminNoteAttachmentUploadRepository {
    UploadResult upload(
        UUID noteId, LocalDateTime expectedUpdatedAt, NoteAttachment attachment,
        UUID actorId
    );

    record UploadResult(
        UUID attachmentId, LocalDateTime updatedAt, String contentType, long fileSize
    ) {}
}
