package io.github.waleed875.memo.modules.notes.application;

import java.util.UUID;

/** One transaction for matching decision, incident (if applicable), and audit. */
public interface NoteDuplicateDecisionRepository {
    void record(NoteDuplicateDecisionCommand command, UUID actorId);
}
