package io.github.waleed875.memo.modules.notes.application;

public final class InvalidNoteSearch extends RuntimeException {
    public InvalidNoteSearch(String message) {
        super(message);
    }
}
