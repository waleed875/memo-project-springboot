package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/**
 * The original service returns the missing/unreviewed count as both
 * affectedNoteCount and missingOrUnreviewedCount. Retain this observed
 * contract until a separately approved change is possible.
 */
public record NoteTypeFieldImpactPreview(
    UUID noteTypeId,
    UUID fieldId,
    int affectedNoteCount,
    int existingValueCount,
    int missingOrUnreviewedCount,
    boolean isNewField
) {}
