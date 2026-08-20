package io.saasforge.iam.application.authentication;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

public final class PasswordPolicy {
    private static final int MIN_CODE_POINTS = 12;
    private static final int MAX_CODE_POINTS = 128;
    private static final int MAX_UTF8_BYTES = 512;

    public String normalizeForChange(String password) {
        if (password == null) {
            throw new PasswordPolicyException("PASSWORD_TOO_SHORT", "密码至少需要 12 个 Unicode code point");
        }
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
        if (normalized.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
            throw new PasswordPolicyException("PASSWORD_WHITESPACE_NOT_ALLOWED", "密码不能包含 Unicode 空白字符");
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < MIN_CODE_POINTS) {
            throw new PasswordPolicyException("PASSWORD_TOO_SHORT", "密码至少需要 12 个 Unicode code point");
        }
        if (codePoints > MAX_CODE_POINTS || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new PasswordPolicyException("PASSWORD_TOO_LONG", "密码不能超过 128 个 Unicode code point 或 512 个 UTF-8 bytes");
        }
        return normalized;
    }
}
