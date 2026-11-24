package io.github.waleed875.memo.modules.notes.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Matches C# NoteDetailsModel used for paged search, not full NoteModel. */
public record NoteSearchItem(
    UUID noteId,
    String noteNumber,
    String noteTypeName,
    String companyName,
    UUID factoryId,
    String factoryName,
    UUID productionLineId,
    String productionLineName,
    LocalDate writingDate,
    LocalDate arrivalAt,
    NoteStatus status,
    String imagePath,
    UUID createdBy,
    String createdByName,
    LocalDateTime createdAt,
    LocalDateTime reviewStartedAt,
    LocalDateTime approvedAt,
    String approvedByName,
    LocalDateTime updatedAt,
    int totalFields,
    int activeCount,
    int inactiveCount,
    int unknownCount,
    BigDecimal accuracyPercentage,
    BigDecimal errorPercentage,
    boolean isLoot,
    boolean isInvoice,
    int linkCount
) {}
