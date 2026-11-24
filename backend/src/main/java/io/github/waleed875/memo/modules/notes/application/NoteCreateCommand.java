package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import java.util.UUID;

/**
 * Explicit, restricted operation-ID create contract for internal use only.
 *
 * This slice supports notes, factory/line, dates and indicator values.
 * Attachments, note links, source documents, loot, invoices and duplicate
 * resolution are deliberately NOT represented rather than silently ignored.
 * No legacy POST/PUT HTTP route is enabled by this model.
 */
public record NoteCreateCommand(
    UUID operationId,
    UUID noteId,
    NoteSaveCandidate candidate,
    String notes
) {}
