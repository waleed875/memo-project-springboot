package io.github.waleed875.memo.modules.identity.application;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Minimal server-side session marker. Never store password hashes in the session. */
public record SessionProof(UUID userId, LocalDateTime securityStamp, Instant authenticatedAt)
    implements Serializable {}
