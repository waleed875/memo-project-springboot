package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One atomic transaction: optimistic soft-delete, unlink, audit and rollback.
 * Implementations must not remove links before the expected timestamp matches.
 */
public interface NoteDeleteRepository {
    void delete(UUID noteId, UUID actorId, LocalDateTime expectedUpdatedAt);
}
