package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Legacy /api/notes/{id}/source metadata shape. Never returns file bytes. */
public record NoteSourceView(
    UUID id, UUID noteId, NoteSourceType sourceType,
    String otherDescription, String lootDescription, LocalDateTime updatedAt,
    List<ExitReferenceView> exitReferences, List<InvoiceView> invoices
) {
    public NoteSourceView {
        exitReferences = List.copyOf(exitReferences);
        invoices = List.copyOf(invoices);
    }
    public record ExitReferenceView(
        UUID exitNoteId, int referenceOrder, String noteNumber
    ) {}
    public record InvoiceView(
        UUID id, String invoiceNumber, LocalDate invoiceDate, String destination,
        String attachmentFileName, boolean hasAttachment
    ) {}
}
