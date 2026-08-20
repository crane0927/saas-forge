package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import java.text.Normalizer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** 使用固定 Argon2id 参数验证 NFC 密码，并为未知主体执行同成本 dummy 验证。 */
public final class PasswordVerifier {
    private static final String DUMMY_PASSWORD = "saas-forge-dummy-password-not-a-credential";

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private final String dummyHash = encoder.encode(DUMMY_PASSWORD);

    public boolean matches(String presentedPassword, Argon2idPasswordHash hash) {
        return encoder.matches(normalize(presentedPassword), hash.encoded());
    }

    public void dummyMatches(String presentedPassword) {
        encoder.matches(normalize(presentedPassword), dummyHash);
    }

    public Argon2idPasswordHash hash(String normalizedPassword) {
        return Argon2idPasswordHash.of(encoder.encode(normalizedPassword));
    }

    private static String normalize(String password) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return Normalizer.normalize(password, Normalizer.Form.NFC);
    }
}
