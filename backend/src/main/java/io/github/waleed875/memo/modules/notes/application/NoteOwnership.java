package io.github.waleed875.memo.modules.notes.application;

import java.util.UUID;

/** Minimal ownership metadata. Never includes attachments or field values. */
public record NoteOwnership(UUID createdBy) {}
