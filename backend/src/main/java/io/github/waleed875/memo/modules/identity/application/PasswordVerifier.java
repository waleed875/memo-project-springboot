package io.github.waleed875.memo.modules.identity.application;

/** Password verification port; backed by BCrypt in infrastructure. */
public interface PasswordVerifier {
    boolean matches(String rawPassword, String storedHash);
}
