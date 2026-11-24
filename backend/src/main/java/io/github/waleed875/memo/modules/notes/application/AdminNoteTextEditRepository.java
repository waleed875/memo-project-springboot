package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AdminNoteTextEditRepository {
    LocalDateTime updateText(AdminNoteTextEditCommand command, UUID actorId);
}
