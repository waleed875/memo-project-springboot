package io.github.waleed875.memo.modules.notes.application;

/** Missing, deleted, stale, or no longer eligible review reservation. */
public final class NoteReviewConflict extends RuntimeException {
    public NoteReviewConflict(String message) { super(message); }
}

