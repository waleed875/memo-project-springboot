package io.github.waleed875.memo.modules.identity.domain;

/**
 * Existing rule from AuditMemoSystem.Core.Validation.PasswordPolicy:
 * new passwords must contain at least 8 UTF-16 code units.
 * Maximum 512 is an intentional compatibility security fix because the
 * legacy login API rejects passwords longer than 512 characters.
 */
public final class PasswordPolicy {
    public static final int MINIMUM_LENGTH = 8;
    public static final int MAXIMUM_LENGTH = 512;

    private PasswordPolicy() {}

    public static boolean validNewPassword(String value) {
        return value != null && !value.isEmpty()
            && value.length() >= MINIMUM_LENGTH
            && value.length() <= MAXIMUM_LENGTH;
    }
}
