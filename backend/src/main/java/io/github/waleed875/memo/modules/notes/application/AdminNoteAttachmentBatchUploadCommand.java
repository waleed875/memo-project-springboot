package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Internal-only multi-file command; no public multipart endpoint is enabled.
 * Copy the collection at the trust boundary; individual byte arrays are copied
 * by the existing immutable NoteAttachment value type.
 */
public record AdminNoteAttachmentBatchUploadCommand(
    UUID noteId, LocalDateTime expectedUpdatedAt, List<NoteAttachment> attachments
) {
    public AdminNoteAttachmentBatchUploadCommand {
        attachments = attachments == null ? null
            : Collections.unmodifiableList(new ArrayList<>(attachments));
    }
}
