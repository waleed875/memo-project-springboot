package io.github.waleed875.memo.modules.notes.application;

/** Note missing, already deleted, or has a different optimistic lock timestamp. */
public final class NoteDeleteConflict extends RuntimeException {
    public NoteDeleteConflict(String message) { super(message); }
}
