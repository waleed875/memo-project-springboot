package io.github.waleed875.memo.modules.notes.domain;

import java.util.Objects;

/**
 * Immutable attachment value: copy bytes on input and output to prevent
 * mutation after fingerprinting and before transactional persistence.
 */
public final class NoteAttachment {
    private final String fileName;
    private final byte[] bytes;

    public NoteAttachment(String fileName, byte[] bytes) {
        this.fileName = fileName;
        this.bytes = bytes == null ? null : bytes.clone();
    }

    public String fileName() { return fileName; }
    public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    public int size() { return bytes == null ? 0 : bytes.length; }

    @Override
    public String toString() {
        return "NoteAttachment[fileName=" + fileName + ", size=" + size() + "]";
    }
}
