package io.github.waleed875.memo.sharedkernel.identity;

import java.util.Locale;

/**
 * Canonical user roles found in the legacy database. Shared by bounded contexts.
 * Keep the shared kernel small and free of Spring/persistence dependencies.
 */
public enum MemoRole {
    ADMIN,
    DATA_ENTRY,
    REVIEWER,
    VIEWER;

    public static MemoRole fromDatabase(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }
        return MemoRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
    }
}
