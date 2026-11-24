package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteReadPolicyTest {
    private final NoteReadPolicy policy = new NoteReadPolicy();

    @Test
    void dataEntryMayReadOwnNotesOnly() {
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(policy.canRead(MemoRole.DATA_ENTRY, actor, actor));
        assertFalse(policy.canRead(MemoRole.DATA_ENTRY, actor, other));
        assertFalse(policy.canRead(MemoRole.DATA_ENTRY, null, other));
        assertFalse(policy.canRead(null, actor, other));
    }

    @Test
    void elevatedReadRolesNeedAValidIdentityAndOwner() {
        UUID actor = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(policy.canRead(MemoRole.ADMIN, actor, owner));
        assertTrue(policy.canRead(MemoRole.REVIEWER, actor, owner));
        assertTrue(policy.canRead(MemoRole.VIEWER, actor, owner));
        // The original C# service checks only DATA_ENTRY ownership.
        assertTrue(policy.canRead(MemoRole.ADMIN, actor, null));
        assertTrue(policy.canRead(MemoRole.REVIEWER, actor, null));
        assertFalse(policy.canRead(MemoRole.DATA_ENTRY, actor, null));
        assertFalse(policy.canRead(MemoRole.ADMIN, null, owner));
    }
}
