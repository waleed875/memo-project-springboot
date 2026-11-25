package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/** Transactional storage port; deletion + primary sync + audit are atomic. */
public interface AdminNoteAttachmentDeleteRepository {
    DeleteResult delete(
        UUID noteId, UUID attachmentId, LocalDateTime expectedUpdatedAt, UUID actorId
    );

    record DeleteResult(
        UUID attachmentId, String fileName, String nextPrimaryFileName,
        LocalDateTime updatedAt
    ) {}
}
