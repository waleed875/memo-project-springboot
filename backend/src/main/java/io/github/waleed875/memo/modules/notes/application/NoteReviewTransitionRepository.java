package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.UUID;

/** Storage port for transactional review state changes. */
public interface NoteReviewTransitionRepository {
    void start(UUID noteId, UUID actorId, LocalDateTime expectedUpdatedAt);
    void returnForCorrection(UUID noteId, UUID actorId, String reason, LocalDateTime expectedUpdatedAt);
    void release(UUID noteId, UUID actorId, String reason);
}

