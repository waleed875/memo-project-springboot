package io.github.waleed875.memo.modules.notes.domain;

import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;

/**
 * Explicit per-note authorization: data entry users can view only their own notes.
 * Re-use this decision for attachments to avoid bypasses at download/upload endpoints.
 * This is a pure policy; no HTTP route currently uses it.
 */
public final class NoteReadPolicy {
    public boolean canRead(MemoRole role, UUID actorId, UUID createdBy) {
        if (role == null || actorId == null || createdBy == null) {
            return false;
        }
        return switch (role) {
            case ADMIN, REVIEWER, VIEWER -> true;
            case DATA_ENTRY -> actorId.equals(createdBy);
        };
    }
}
