package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal-only administrator command. All note attributes except the free
 * notes text remain untouched, including approval and active review state.
 */
public record AdminNoteTextEditCommand(
    UUID noteId, LocalDateTime expectedUpdatedAt, String notes
) {}
