package io.github.waleed875.memo.security;

import java.util.UUID;

/**
 * Explicit server-side authorization decisions extracted from legacy behavior
 * with intentional security fixes. NOT YET wired into HTTP endpoints.
 * All decisions fail closed for null/unknown identities.
 */
public final class AuthorizationPolicy {
    public boolean canManageDatabaseConfiguration(MemoRole role) {
        return role == MemoRole.ADMIN;
    }

    public boolean canApplyMigrations(MemoRole role) {
        return role == MemoRole.ADMIN;
    }

    public boolean canPublishUpdates(MemoRole role) {
        return role == MemoRole.ADMIN;
    }

    public boolean canReadNote(MemoRole role, UUID actorId, UUID createdBy) {
        if (role == null || actorId == null || createdBy == null) {
            return false;
        }
        return switch (role) {
            case ADMIN, REVIEWER, VIEWER -> true;
            case DATA_ENTRY -> actorId.equals(createdBy);
        };
    }
}
