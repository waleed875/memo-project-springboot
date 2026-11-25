package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Atomic link set synchronization, subject to original DB trigger contract. */
public interface AdminNoteLinkSyncRepository {
    SyncResult sync(AdminNoteLinkSyncCommand command, UUID actorId);

    record SyncResult(
        UUID noteId, List<UUID> relatedNoteIds, int added, int removed,
        LocalDateTime updatedAt
    ) {
        public SyncResult {
            relatedNoteIds = List.copyOf(relatedNoteIds);
        }
    }
}
