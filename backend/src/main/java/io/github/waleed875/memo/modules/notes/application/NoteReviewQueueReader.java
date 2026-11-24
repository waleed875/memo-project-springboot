package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteReviewQueueItem;
import java.util.List;
import java.util.UUID;

/** Read-only port: the reviewer is derived from the authenticated session. */
public interface NoteReviewQueueReader {
    List<NoteReviewQueueItem> findForReviewer(UUID reviewerId);
}

