package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Restricted internal replacement of a memo's legacy counterpart links.
 * An explicit empty list means clear. Null means invalid command (never clear).
 */
public record AdminNoteLinkSyncCommand(
    UUID noteId, LocalDateTime expectedUpdatedAt, List<UUID> relatedNoteIds
) {
    public AdminNoteLinkSyncCommand {
        relatedNoteIds = relatedNoteIds == null ? null
            : Collections.unmodifiableList(new ArrayList<>(relatedNoteIds));
    }
}
