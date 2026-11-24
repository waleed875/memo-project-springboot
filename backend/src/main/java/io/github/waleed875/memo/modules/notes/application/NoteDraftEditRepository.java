package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Storage port: recheck ownership/status/version and update atomically. */
public interface NoteDraftEditRepository {
    EditResult edit(NoteDraftEditCommand command, UUID actor, LocalDate operationalToday);

    record EditResult(UUID noteId, NoteStatus status, LocalDateTime updatedAt) {}
}
