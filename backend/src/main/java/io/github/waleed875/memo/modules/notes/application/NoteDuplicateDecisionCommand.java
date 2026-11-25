package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDate;
import java.util.UUID;

/** Does NOT create a memo; only records resend or entry-duplicate decisions. */
public record NoteDuplicateDecisionCommand(
    NoteDuplicateClassification classification,
    UUID matchedNoteId,
    String noteNumber,
    UUID companyId,
    UUID noteTypeId,
    UUID factoryId,
    UUID productionLineId,
    LocalDate writingDate,
    boolean isInvoice
) {}
