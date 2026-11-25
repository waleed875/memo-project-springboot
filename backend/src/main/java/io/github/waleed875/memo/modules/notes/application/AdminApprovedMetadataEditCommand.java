package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Restricted internal edit of an already approved memo's identifying fields.
 * Must never expose approval, reviewer, source, invoice or loot flags.
 */
public record AdminApprovedMetadataEditCommand(
    UUID noteId, LocalDateTime expectedUpdatedAt, NoteDraftMetadata metadata
) {}
