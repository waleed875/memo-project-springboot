package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Restricted owner edit. Null attachment preserves existing media.
 * Null expected+desired link lists preserve existing links; explicit empty
 * desired list with explicit prior snapshot clears links.
 * Does not expose number/company/type/dates/flags/source/workflow overrides.
 */
public record NoteDraftEditCommand(
    UUID noteId,
    LocalDateTime expectedUpdatedAt,
    String notes,
    boolean saveAsDraft,
    boolean markAllIndicatorsCorrect,
    List<IndicatorInput> fieldValues,
    NoteAttachment attachment,
    List<UUID> expectedRelatedNoteIds,
    List<UUID> relatedNoteIds
) {
    public NoteDraftEditCommand {
        fieldValues = fieldValues == null ? null : List.copyOf(fieldValues);
        expectedRelatedNoteIds = expectedRelatedNoteIds == null ? null
            : Collections.unmodifiableList(new ArrayList<>(expectedRelatedNoteIds));
        relatedNoteIds = relatedNoteIds == null ? null
            : Collections.unmodifiableList(new ArrayList<>(relatedNoteIds));
    }

    /** Existing internal six-argument commands remain source-compatible. */
    public NoteDraftEditCommand(
        UUID noteId, LocalDateTime expectedUpdatedAt, String notes,
        boolean saveAsDraft, boolean markAllIndicatorsCorrect, List<IndicatorInput> fieldValues
    ) {
        this(noteId, expectedUpdatedAt, notes, saveAsDraft,
            markAllIndicatorsCorrect, fieldValues, null, null, null);
    }

    public boolean synchronizesLinks() {
        return expectedRelatedNoteIds != null && relatedNoteIds != null;
    }

    public record IndicatorInput(UUID fieldId, IndicatorValue value, String inactiveReason) {}
}
