package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal-only, single-attachment administrator upload request.
 * Does not accept note status, approval, reviewer or catalog fields.
 */
public record AdminNoteAttachmentUploadCommand(
    UUID noteId, LocalDateTime expectedUpdatedAt, NoteAttachment attachment
) {}
