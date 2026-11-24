package io.github.waleed875.memo.modules.notes.domain;

import java.util.UUID;

/** Effective note-type field metadata loaded from the database by a port. */
public record RequiredNoteField(
    UUID fieldId, String fieldNameAr, boolean isRequired, boolean isActive
) {}
