package io.github.waleed875.memo.modules.notes.application;
import java.util.Objects;
/** Immutable bytes: external callers cannot mutate a JDBC-returned value. */
public record DownloadedNoteAttachment(String fileName, byte[] bytes) {
    public DownloadedNoteAttachment {
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(bytes);
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
}
