package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Internal administrator command to upsert submitted active indicator values.
 * Never carries editable note identity, dates, workflow status or owner fields.
 */
public record AdminNoteIndicatorEditCommand(
    UUID noteId,
    LocalDateTime expectedUpdatedAt,
    boolean markAllIndicatorsCorrect,
    List<IndicatorInput> fieldValues
) {
    public AdminNoteIndicatorEditCommand {
        fieldValues = fieldValues == null ? null : List.copyOf(fieldValues);
    }

    public record IndicatorInput(UUID fieldId, IndicatorValue value, String inactiveReason) {}
}
