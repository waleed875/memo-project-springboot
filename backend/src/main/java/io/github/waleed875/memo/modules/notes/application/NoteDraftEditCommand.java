package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Deliberately restricted internal edit command. It cannot mutate a note's
 * number, company, type, dates, media, flags, sources, or counterpart links.
 * This is NOT the public legacy SaveNoteRequest.
 */
public record NoteDraftEditCommand(
    UUID noteId,
    LocalDateTime expectedUpdatedAt,
    String notes,
    boolean saveAsDraft,
    boolean markAllIndicatorsCorrect,
    List<IndicatorInput> fieldValues
) {
    public NoteDraftEditCommand {
        fieldValues = fieldValues == null ? null : List.copyOf(fieldValues);
    }

    public record IndicatorInput(UUID fieldId, IndicatorValue value, String inactiveReason) {}
}
