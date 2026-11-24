package io.github.waleed875.memo.modules.identity.application;

import io.github.waleed875.memo.modules.identity.domain.PasswordPolicy;
import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Framework-free identity use cases.
 *
 * Authorization remains fail-closed: active accounts are reloaded for each
 * request and a session is revoked after role, password stamp or status change.
 */
public final class IdentityApplicationService {
    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(8);

    private final UserAccountLookup accounts;
    private final PasswordVerifier passwords;
    private final PasswordHasher hasher;
    private final UserPasswordWriter passwordWriter;
    private final SecurityEventAudit audit;
    private final Clock clock;

    public IdentityApplicationService(
        UserAccountLookup accounts,
        PasswordVerifier passwords,
        PasswordHasher hasher,
        UserPasswordWriter passwordWriter,
        SecurityEventAudit audit,
        Clock clock
    ) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.hasher = hasher;
        this.passwordWriter = passwordWriter;
        this.audit = audit;
        this.clock = clock;
    }

    public LoginAttempt login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 128
            || password == null || password.isBlank() || password.length() > 512) {
            audit.record(null, "LOGIN_FAILURE", "فشل تسجيل الدخول.");
            return LoginAttempt.invalid();
        }

        Optional<UserAccount> found = accounts.findActiveByUsername(username.trim());
        if (found.isEmpty()) {
            audit.record(null, "LOGIN_FAILURE", "فشل تسجيل الدخول.");
            return LoginAttempt.invalid();
        }

        UserAccount account = found.get();
        if (account.passwordHash() == null || account.passwordHash().isBlank()
            || account.role() == null || account.updatedAt() == null
            || !passwords.matches(password, account.passwordHash())) {
            audit.record(account.id(), "LOGIN_FAILURE", "فشل تسجيل الدخول.");
            return LoginAttempt.invalid();
        }

        UserProfile profile = UserProfile.from(account);
        if (account.mustChangePassword()) {
            audit.record(account.id(), "LOGIN_MUST_CHANGE_PASSWORD", "يلزم تغيير كلمة المرور.");
            return LoginAttempt.mustChange(profile);
        }

        audit.record(account.id(), "LOGIN", "تسجيل دخول إلى النظام.");
        SessionProof proof = new SessionProof(account.id(), account.role(),
            account.updatedAt(), clock.instant());
        return LoginAttempt.authenticated(profile, proof);
    }

    public Optional<UserProfile> current(SessionProof proof) {
        if (proof == null || proof.userId() == null || proof.role() == null
            || proof.securityStamp() == null || proof.authenticatedAt() == null) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (now.isBefore(proof.authenticatedAt())
            || now.isAfter(proof.authenticatedAt().plus(ABSOLUTE_SESSION_LIFETIME))) {
            return Optional.empty();
        }

        return accounts.findActiveById(proof.userId())
            .filter(a -> !a.mustChangePassword()
                && a.updatedAt() != null
                && a.updatedAt().equals(proof.securityStamp())
                && a.role() == proof.role())
            .map(UserProfile::from);
    }

    public PasswordChangeOutcome changePassword(String username, String currentPassword, String newPassword) {
        if (username == null || username.isBlank() || username.length() > 128
            || currentPassword == null || currentPassword.isBlank()
            || currentPassword.length() > 512) {
            audit.record(null, "PASSWORD_CHANGE_FAILURE", "تعذر تغيير كلمة المرور.");
            return PasswordChangeOutcome.INVALID_CREDENTIALS;
        }
        if (!PasswordPolicy.validNewPassword(newPassword)) {
            return PasswordChangeOutcome.INVALID_NEW_PASSWORD;
        }

        Optional<UserAccount> found = accounts.findActiveByUsername(username.trim());
        if (found.isEmpty()) {
            audit.record(null, "PASSWORD_CHANGE_FAILURE", "تعذر تغيير كلمة المرور.");
            return PasswordChangeOutcome.INVALID_CREDENTIALS;
        }

        UserAccount account = found.get();
        if (account.id() == null || account.passwordHash() == null
            || !passwords.matches(currentPassword, account.passwordHash())) {
            audit.record(account.id(), "PASSWORD_CHANGE_FAILURE", "تعذر تغيير كلمة المرور.");
            return PasswordChangeOutcome.INVALID_CREDENTIALS;
        }

        String replacementHash = hasher.hash(newPassword);
        if (!passwordWriter.replaceHashIfCurrent(account.id(), account.passwordHash(), replacementHash)) {
            audit.record(account.id(), "PASSWORD_CHANGE_FAILURE", "تغيّر الحساب أثناء العملية.");
            return PasswordChangeOutcome.INVALID_CREDENTIALS;
        }

        audit.record(account.id(), "PASSWORD_CHANGED", "تم تغيير كلمة المرور.");
        return PasswordChangeOutcome.SUCCESS;
    }

    public void logout(SessionProof proof) {
        if (proof != null && proof.userId() != null) {
            audit.record(proof.userId(), "LOGOUT", "تسجيل خروج من النظام.");
        }
    }
}
