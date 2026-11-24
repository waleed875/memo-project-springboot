package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

/** Transactional repository port for a narrow administrator indicator edit. */
public interface AdminNoteIndicatorEditRepository {
    LocalDateTime updateIndicators(
        AdminNoteIndicatorEditCommand command, UUID actorId, LocalDate operationalToday
    );
}
