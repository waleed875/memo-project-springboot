package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Legacy NoteDuplicateModel JSON response contract. */
public record NoteDuplicate(
    UUID noteId, String noteNumber,
    UUID companyId, String companyName,
    UUID factoryId, String factoryName,
    UUID productionLineId, String productionLineName,
    UUID noteTypeId, String noteTypeName,
    LocalDate writingDate, LocalDate arrivalAt, LocalDateTime createdAt,
    String attachmentFileName, String attachmentContentType
) {}
