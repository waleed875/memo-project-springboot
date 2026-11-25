package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Optional complete metadata replacement for an owner's DRAFT/RETURNED memo.
 * No workflow status, review, source, LOOT or invoice flags are client-controlled.
 * A null patch in NoteDraftEditCommand preserves all existing metadata.
 */
public record NoteDraftMetadata(
    String noteNumber,
    UUID companyId,
    UUID noteTypeId,
    UUID factoryId,
    UUID productionLineId,
    LocalDate writingDate,
    LocalDate arrivalAt
) {}
