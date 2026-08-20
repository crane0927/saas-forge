package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class RefreshTokenIssuer {
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom;

    public RefreshTokenIssuer(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public RefreshTokenMaterial issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return new RefreshTokenMaterial(token, Sha256Digest.of(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
