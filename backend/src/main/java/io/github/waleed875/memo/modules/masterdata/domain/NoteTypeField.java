package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/** A field definition as attached to a given note type. */
public record NoteTypeField(
    UUID id,
    UUID noteTypeId,
    UUID fieldId,
    String fieldCode,
    String fieldNameAr,
    int sortOrder,
    boolean isRequired,
    boolean isActive
) {}
