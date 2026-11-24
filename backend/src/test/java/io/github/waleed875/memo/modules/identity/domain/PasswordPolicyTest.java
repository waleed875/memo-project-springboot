package io.github.waleed875.memo.modules.identity.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test void enforcesLegacyMinimumAndLoginCompatibleMaximum() {
        assertFalse(PasswordPolicy.validNewPassword(null));
        assertFalse(PasswordPolicy.validNewPassword(""));
        assertFalse(PasswordPolicy.validNewPassword("1234567"));
        assertTrue(PasswordPolicy.validNewPassword("12345678"));
        assertTrue(PasswordPolicy.validNewPassword("x".repeat(512)));
        assertFalse(PasswordPolicy.validNewPassword("x".repeat(513)));
    }
}
