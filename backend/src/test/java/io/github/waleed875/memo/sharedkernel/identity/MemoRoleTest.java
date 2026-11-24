package io.github.waleed875.memo.sharedkernel.identity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MemoRoleTest {
    @Test
    void parsesKnownDatabaseRolesAndRejectsUnknown() {
        assertEquals(MemoRole.DATA_ENTRY, MemoRole.fromDatabase(" data_entry "));
        assertEquals(MemoRole.ADMIN, MemoRole.fromDatabase("admin"));
        assertThrows(IllegalArgumentException.class, () -> MemoRole.fromDatabase("SUPERVISOR"));
        assertThrows(IllegalArgumentException.class, () -> MemoRole.fromDatabase(null));
        assertThrows(IllegalArgumentException.class, () -> MemoRole.fromDatabase(" "));
    }
}
