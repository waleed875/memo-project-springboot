package io.github.waleed875.memo.modules.identity.infrastructure;

import io.github.waleed875.memo.modules.identity.application.PasswordVerifier;
import io.github.waleed875.memo.modules.identity.application.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Verifies existing $2a/$2b/$2y BCrypt hashes without changing them. */
@Component
public final class BcryptPasswordVerifier implements PasswordVerifier, PasswordHasher {
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return delegate.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
