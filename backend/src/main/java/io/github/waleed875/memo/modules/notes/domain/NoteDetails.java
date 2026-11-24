package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Original NoteModel JSON contract. A byte[] is serialized as base64, as
 * System.Text.Json does for the original AttachmentData property.
 * Protect every access with note ownership authorization, not only session.
 */
public record NoteDetails(
    UUID id,
    String noteNumber,
    UUID noteTypeId, String noteTypeName,
    UUID companyId, String companyName,
    UUID factoryId, String factoryName,
    UUID productionLineId, String productionLineName,
    LocalDate writingDate, LocalDate arrivalAt,
    String imagePath, String filePath,
    String attachmentFileName, String attachmentContentType, byte[] attachmentData,
    String notes, NoteStatus status,
    UUID createdBy, String createdByName, LocalDateTime createdAt,
    UUID updatedBy, LocalDateTime updatedAt,
    UUID approvedBy, LocalDateTime approvedAt, String approvedByName,
    UUID reviewerId, String reviewerName, LocalDateTime reviewStartedAt,
    String returnReason, String duplicateReason,
    boolean isLoot, boolean isInvoice,
    List<UUID> relatedNoteIds,
    List<NoteFieldValue> fieldValues,
    List<NoteReviewHistory> reviewHistory
) {
    public NoteDetails {
        relatedNoteIds = List.copyOf(relatedNoteIds);
        fieldValues = List.copyOf(fieldValues);
        reviewHistory = List.copyOf(reviewHistory);
    }
}
