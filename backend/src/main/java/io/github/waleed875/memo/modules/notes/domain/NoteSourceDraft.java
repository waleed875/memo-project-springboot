package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Immutable source command, never includes requester ID or database actor. */
public record NoteSourceDraft(
    NoteSourceType sourceType,
    String otherDescription,
    String lootDescription,
    List<ExitReference> exitReferences,
    List<Invoice> invoices
) {
    public NoteSourceDraft {
        exitReferences = exitReferences == null ? List.of() : List.copyOf(exitReferences);
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
    }

    public record ExitReference(UUID exitNoteId, int referenceOrder) {}
    public record Invoice(
        String invoiceNumber, LocalDate invoiceDate, String destination,
        NoteAttachment attachment
    ) {}
}
