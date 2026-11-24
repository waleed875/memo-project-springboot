package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire-compatible projection of the legacy ReviewQueueItemModel.
 * No source attachments or reviewer-only content is included.
 */
public record NoteReviewQueueItem(
    UUID noteId,
    LocalDateTime updatedAt,
    String noteNumber,
    String companyName,
    String noteTypeName,
    String factoryName,
    String productionLineName,
    LocalDate arrivalAt,
    NoteStatus status,
    UUID createdBy,
    String createdByName,
    LocalDateTime createdAt,
    UUID reviewerId,
    String reviewerName,
    LocalDateTime reviewStartedAt
) {}

