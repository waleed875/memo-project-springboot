package io.github.waleed875.memo.security;

import java.util.Locale;

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
