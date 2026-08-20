package io.saasforge.iam.application.signing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InMemoryJwtSigningFake implements JwtSigningPort {

    private int invocationCount;
    private String keyVersionRef;
    private JwtSigningAlgorithm algorithm;
    private RuntimeException failure;

    @Override
    public byte[] sign(String kmsKeyVersionRef, JwtSigningAlgorithm algorithm, JwsSigningInput signingInput) {
        invocationCount++;
        keyVersionRef = kmsKeyVersionRef;
        this.algorithm = algorithm;
        if (failure != null) {
            throw failure;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(kmsKeyVersionRef.getBytes(StandardCharsets.UTF_8));
            digest.update(signingInput.bytes());
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    int invocationCount() {
        return invocationCount;
    }

    String keyVersionRef() {
        return keyVersionRef;
    }

    JwtSigningAlgorithm algorithm() {
        return algorithm;
    }
}
