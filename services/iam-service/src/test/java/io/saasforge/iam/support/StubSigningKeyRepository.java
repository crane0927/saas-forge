package io.saasforge.iam.support;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StubSigningKeyRepository implements SigningKeyRepository {

    private List<SigningKey> activeKeys = List.of();
    private List<SigningKey> publishedVerificationKeys = List.of();

    public void activeKeys(List<SigningKey> activeKeys) {
        this.activeKeys = List.copyOf(activeKeys);
    }

    public void publishedVerificationKeys(List<SigningKey> publishedVerificationKeys) {
        this.publishedVerificationKeys = List.copyOf(publishedVerificationKeys);
    }

    @Override
    public List<SigningKey> findActiveKeys() {
        return activeKeys;
    }

    @Override
    public List<SigningKey> findPublishedVerificationKeys() {
        return publishedVerificationKeys;
    }

    @Override
    public SigningKey savePublished(SigningKey key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SigningKey activate(UUID keyId, Instant at) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SigningKey retire(UUID keyId, Instant at) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SigningKey revoke(UUID keyId, Instant at) {
        throw new UnsupportedOperationException();
    }
}
