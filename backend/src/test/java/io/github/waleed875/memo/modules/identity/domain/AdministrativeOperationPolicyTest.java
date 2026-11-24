package io.github.waleed875.memo.modules.identity.domain;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import org.junit.jupiter.api.Test;

class AdministrativeOperationPolicyTest {
    private final AdministrativeOperationPolicy policy = new AdministrativeOperationPolicy();

    @Test
    void privilegedActionsRequireAdmin() {
        for (MemoRole role : MemoRole.values()) {
            boolean allowed = role == MemoRole.ADMIN;
            assertEquals(allowed, policy.canManageDatabaseConfiguration(role));
            assertEquals(allowed, policy.canApplyMigrations(role));
            assertEquals(allowed, policy.canPublishUpdates(role));
        }
        assertFalse(policy.canManageDatabaseConfiguration(null));
        assertFalse(policy.canApplyMigrations(null));
        assertFalse(policy.canPublishUpdates(null));
    }
}
