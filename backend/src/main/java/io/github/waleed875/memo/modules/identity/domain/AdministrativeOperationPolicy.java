package io.github.waleed875.memo.modules.identity.domain;

import io.github.waleed875.memo.sharedkernel.identity.MemoRole;

/**
 * Pure authorization rules for privileged system operations.
 * These decisions MUST be enforced at the application boundary when APIs are implemented.
 */
public final class AdministrativeOperationPolicy {
    public boolean canManageDatabaseConfiguration(MemoRole role) {
        return role == MemoRole.ADMIN;
    }

    public boolean canApplyMigrations(MemoRole role) {
        return role == MemoRole.ADMIN;
    }

    public boolean canPublishUpdates(MemoRole role) {
        return role == MemoRole.ADMIN;
    }
}
