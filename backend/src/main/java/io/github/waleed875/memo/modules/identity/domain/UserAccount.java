package io.github.waleed875.memo.modules.identity.domain;

import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Authentication snapshot from auth.users/auth.roles.
 * A password hash is never sent to the HTTP layer or stored in the session.
 */
public record UserAccount(
    UUID id,
    String username,
    String fullName,
    String passwordHash,
    MemoRole role,
    boolean mustChangePassword,
    LocalDateTime updatedAt
) {}
