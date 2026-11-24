package io.github.waleed875.memo.modules.identity.application;

import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Framework-free authentication use case.
 *
 * Validates the active account on every session lookup rather than trusting
 * role data stored in a session. Absolute session lifetime matches the
 * legacy eight-hour limit. Security stamp is auth.users.updated_at.
 */
public final class IdentityApplicationService {
    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(8);

    private final UserAccountLookup accounts;
    private final PasswordVerifier passwords;
    private final Clock clock;

    public IdentityApplicationService(UserAccountLookup accounts, PasswordVerifier passwords, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.clock = clock;
    }

    public LoginAttempt login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 128
            || password == null || password.isBlank() || password.length() > 512) {
            return LoginAttempt.invalid();
        }

        Optional<UserAccount> found = accounts.findActiveByUsername(username.trim());
        if (found.isEmpty()) return LoginAttempt.invalid();

        UserAccount account = found.get();
        if (account.passwordHash() == null || account.passwordHash().isBlank()
            || account.role() == null || account.updatedAt() == null
            || !passwords.matches(password, account.passwordHash())) {
            return LoginAttempt.invalid();
        }

        UserProfile profile = UserProfile.from(account);
        if (account.mustChangePassword()) {
            return LoginAttempt.mustChange(profile);
        }

        SessionProof proof = new SessionProof(account.id(), account.updatedAt(), clock.instant());
        return LoginAttempt.authenticated(profile, proof);
    }

    public Optional<UserProfile> current(SessionProof proof) {
        if (proof == null || proof.userId() == null || proof.securityStamp() == null
            || proof.authenticatedAt() == null) return Optional.empty();

        Instant now = clock.instant();
        if (now.isBefore(proof.authenticatedAt())
            || now.isAfter(proof.authenticatedAt().plus(ABSOLUTE_SESSION_LIFETIME))) {
            return Optional.empty();
        }

        return accounts.findActiveById(proof.userId())
            .filter(a -> !a.mustChangePassword() && a.updatedAt() != null
                && a.updatedAt().equals(proof.securityStamp()) && a.role() != null)
            .map(UserProfile::from);
    }
}
