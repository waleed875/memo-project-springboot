package io.github.waleed875.memo.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {
    private final AuthorizationPolicy policy = new AuthorizationPolicy();

    @Test
    void setupMigrationsAndUpdatePublishingRequireAdmin() {
        for (MemoRole role : MemoRole.values()) {
            boolean expected = role == MemoRole.ADMIN;
            assertEquals(expected, policy.canManageDatabaseConfiguration(role));
            assertEquals(expected, policy.canApplyMigrations(role));
            assertEquals(expected, policy.canPublishUpdates(role));
        }
        assertFalse(policy.canManageDatabaseConfiguration(null));
        assertFalse(policy.canApplyMigrations(null));
        assertFalse(policy.canPublishUpdates(null));
    }

    @Test
    void dataEntryCannotReadAnotherUsersNote() {
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(policy.canReadNote(MemoRole.DATA_ENTRY, actor, actor));
        assertFalse(policy.canReadNote(MemoRole.DATA_ENTRY, actor, other));
        assertFalse(policy.canReadNote(MemoRole.DATA_ENTRY, null, other));
        assertFalse(policy.canReadNote(null, actor, other));
    }

    @Test
    void readPermissionsRespectKnownRolesAndIdentity() {
        UUID actor = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(policy.canReadNote(MemoRole.ADMIN, actor, owner));
        assertTrue(policy.canReadNote(MemoRole.REVIEWER, actor, owner));
        assertTrue(policy.canReadNote(MemoRole.VIEWER, actor, owner));
        assertFalse(policy.canReadNote(MemoRole.ADMIN, actor, null));
    }

    @Test
    void unsupportedDatabaseRoleFailsClosed() {
        assertEquals(MemoRole.DATA_ENTRY, MemoRole.fromDatabase(" data_entry "));
        assertThrows(IllegalArgumentException.class, () -> MemoRole.fromDatabase("SUPERVISOR"));
        assertThrows(IllegalArgumentException.class, () -> MemoRole.fromDatabase(null));
    }
}
