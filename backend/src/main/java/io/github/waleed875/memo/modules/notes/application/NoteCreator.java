package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDate;
import java.util.UUID;

/** The entire persistence operation runs inside ONE transaction in the adapter. */
public interface NoteCreator {
    UUID create(NoteCreateCommand command, UUID actorId, LocalDate operationalToday);
}
