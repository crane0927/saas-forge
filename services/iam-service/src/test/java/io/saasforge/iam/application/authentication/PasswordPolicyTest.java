package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void normalizesNfcWithoutTrimmingFoldingOrTruncating() {
        assertEquals("Caf\u00e9Secure12", policy.normalizeForChange("Cafe\u0301Secure12"));
    }

    @Test
    void rejectsUnicodeWhitespaceIncludingNoBreakSpace() {
        assertCode("PASSWORD_WHITESPACE_NOT_ALLOWED", "Secure12345\u00a0x");
        assertCode("PASSWORD_WHITESPACE_NOT_ALLOWED", "Secure12345\u3000x");
        assertCode("PASSWORD_WHITESPACE_NOT_ALLOWED", "Secure12345\tx");
    }

    @Test
    void enforcesCodePointBoundaries() {
        assertEquals(12, policy.normalizeForChange("12345678901x").codePointCount(0, 12));
        String maximum = "\ud83d\ude00".repeat(128);
        assertEquals(maximum, policy.normalizeForChange(maximum));
        assertCode("PASSWORD_TOO_SHORT", "12345678901");
        assertCode("PASSWORD_TOO_LONG", "x".repeat(129));
    }

    private void assertCode(String code, String password) {
        PasswordPolicyException exception = assertThrows(
                PasswordPolicyException.class, () -> policy.normalizeForChange(password));
        assertEquals(code, exception.code());
    }
}
