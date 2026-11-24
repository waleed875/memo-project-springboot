package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Search parameters corresponding to the legacy NoteSearchFilterModel.
 * The requested creator may be overridden by the authenticated actor.
 */
public record NoteSearchCriteria(
    String noteNumber,
    UUID companyId,
    UUID factoryId,
    UUID productionLineId,
    UUID noteTypeId,
    UUID createdBy,
    NoteStatus status,
    LocalDate from,
    LocalDate to,
    String dateBasis,
    Boolean hasWritingDate,
    Boolean unlinkedOnly,
    Boolean isLoot,
    Boolean isInvoice,
    LocalDate entryCutoffDate,
    int pageNumber,
    int pageSize
) {}
