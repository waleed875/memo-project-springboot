package io.github.waleed875.memo.modules.notes.domain;

/** Plain domain-level business validation failure. */
public final class NoteSaveValidationException extends RuntimeException {
    public NoteSaveValidationException(String message) { super(message); }
}
