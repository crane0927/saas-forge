package io.saasforge.iam.application.client;

import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.security.SecureRandom;
import java.util.Base64;

/** 产生只在当前响应内保留的 256 位 Client Secret 及其不可逆摘要。 */
public final class ClientSecretIssuer {
    private static final int SECRET_BYTES = 32;
    private final SecureRandom random;

    public ClientSecretIssuer(SecureRandom random) {
        this.random = random;
    }

    public IssuedClientSecret issue() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedClientSecret(plaintext, ClientSecretDigest.fromPlaintext(plaintext));
    }

    public record IssuedClientSecret(String plaintext, Sha256Digest digest) {
    }
}
