package io.github.waleed875.memo.modules.notes.application;

/** Stale revision or invalid current note state for restricted draft edits. */
public final class NoteDraftEditConflict extends RuntimeException {
    public NoteDraftEditConflict(String message) { super(message); }
}
