package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/**
 * Command input/output in the legacy NoteTypeFieldDefinitionModel shape.
 * A null ID creates a definition; a null fieldId creates/reuses the field code.
 */
public record NoteTypeFieldDefinition(
    UUID id, UUID noteTypeId, UUID fieldId, String fieldCode,
    String fieldNameAr, String description, int sortOrder,
    boolean isRequired, boolean isActive
) {}
