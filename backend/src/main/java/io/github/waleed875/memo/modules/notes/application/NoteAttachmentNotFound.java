package io.github.waleed875.memo.modules.notes.application;

/** Requested attachment is absent or belongs to another memo. */
public final class NoteAttachmentNotFound extends RuntimeException {
    public NoteAttachmentNotFound() {
        super("المرفق غير موجود أو تم حذفه مسبقاً.");
    }
}
