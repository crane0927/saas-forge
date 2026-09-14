package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** 生成 256 位 Password Setup Token，并只向持久化边界提供 SHA-256 摘要。 */
public final class PasswordSetupChallengeIssuer {
    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_VALUE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private final SecureRandom secureRandom;

    public PasswordSetupChallengeIssuer(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public Material issue() {
        byte[] random = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new Material(token, digest(token));
    }

    public Sha256Digest digest(String token) {
        if (token == null || !TOKEN_VALUE.matcher(token).matches()) {
            throw new PasswordSetupTokenInvalidException();
        }
        try {
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    public record Material(String token, Sha256Digest digest) {
        @Override
        public String toString() {
            return "Material[token=[redacted], digest=[redacted]]";
        }
    }
}
