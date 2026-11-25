package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Atomic append-only batch with the original multi-file primary COALESCE semantics. */
public interface AdminNoteAttachmentBatchUploadRepository {
    BatchResult upload(AdminNoteAttachmentBatchUploadCommand command, UUID actorId);

    record UploadedFile(
        UUID id, String fileName, String contentType, long fileSize
    ) {}

    record BatchResult(
        List<UploadedFile> uploaded, LocalDateTime updatedAt
    ) {
        public BatchResult {
            uploaded = List.copyOf(uploaded);
        }
    }
}
