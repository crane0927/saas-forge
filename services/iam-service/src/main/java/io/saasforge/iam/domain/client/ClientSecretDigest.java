package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** 校验并摘要 256 位无填充 Base64url Client Secret。 */
public final class ClientSecretDigest {
    private static final Pattern ENCODED_SECRET = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private ClientSecretDigest() {
    }

    public static Sha256Digest fromPlaintext(String secret) {
        if (secret == null || !ENCODED_SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("Client Secret 必须是 256 位无填充 Base64url");
        }
        try {
            if (Base64.getUrlDecoder().decode(secret).length != 32) {
                throw new IllegalArgumentException("Client Secret 必须是 256 位无填充 Base64url");
            }
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Client Secret 必须是 256 位无填充 Base64url", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
