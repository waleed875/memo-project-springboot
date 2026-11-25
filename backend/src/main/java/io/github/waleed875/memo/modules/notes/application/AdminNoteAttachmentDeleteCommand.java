package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal administrator command: the attachment ID is always scoped to
 * its owning note, and an exact memo revision is mandatory.
 */
public record AdminNoteAttachmentDeleteCommand(
    UUID noteId, UUID attachmentId, LocalDateTime expectedUpdatedAt
) {}
