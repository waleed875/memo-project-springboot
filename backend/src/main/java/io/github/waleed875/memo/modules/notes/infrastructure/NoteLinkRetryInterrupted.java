package io.github.waleed875.memo.modules.notes.infrastructure;

/** Caller may handle interruption; never execute another database write. */
public final class NoteLinkRetryInterrupted extends RuntimeException {
    public NoteLinkRetryInterrupted(InterruptedException cause) {
        super("Link synchronization retry was interrupted", cause);
    }
}
