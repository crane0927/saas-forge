package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

public final class RefreshTokenIssuer {
    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_VALUE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private final SecureRandom secureRandom;

    public RefreshTokenIssuer(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public RefreshTokenMaterial issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshTokenMaterial(token, digest(token));
    }

    public Sha256Digest digest(String token) {
        if (token == null || !TOKEN_VALUE.matcher(token).matches()) {
            throw new ContextSelectionSessionInvalidException();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Sha256Digest.of(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
