package io.github.waleed875.memo.modules.notes.application;

import java.util.UUID;

/** Explicit server-side verification of the matching original is mandatory. */
public record NoteDuplicateResolution(
    NoteDuplicateClassification classification, UUID matchedNoteId
) {}
