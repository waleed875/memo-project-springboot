package io.github.waleed875.memo.modules.notes.domain;

import java.util.UUID;

public record NoteFieldValue(
    UUID id, UUID noteId, UUID fieldId,
    String fieldName, IndicatorValue fieldValue, String inactiveReason
) {}
