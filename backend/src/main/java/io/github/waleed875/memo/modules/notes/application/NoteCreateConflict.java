package io.github.waleed875.memo.modules.notes.application;

/** Duplicate, operation replay mismatch, or invalid catalog reference. */
public final class NoteCreateConflict extends RuntimeException {
    public NoteCreateConflict(String message) { super(message); }
}
