package io.github.waleed875.memo.modules.identity.application;

/** Separate port for creating new password hashes. */
public interface PasswordHasher {
    String hash(String rawPassword);
}
