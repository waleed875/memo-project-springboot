package io.github.waleed875.memo.modules.identity.application;

import java.util.UUID;

/**
 * Atomic compare-and-swap update. Returns false if credentials or account
 * status changed since they were loaded.
 */
public interface UserPasswordWriter {
    boolean replaceHashIfCurrent(UUID userId, String expectedHash, String replacementHash);
}
