package io.saasforge.iam.support;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Duration;
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
    public java.util.Optional<SigningKey> findById(UUID keyId) {
        return java.util.stream.Stream.concat(activeKeys.stream(), publishedVerificationKeys.stream())
                .filter(key -> key.id().equals(keyId))
                .findFirst();
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
    public SigningKey prepareActiveForIssuance(Duration tokenTtl) {
        if (activeKeys.size() != 1) {
            throw new IllegalStateException("签发时必须恰好存在一个 ACTIVE Signing Key");
        }
        SigningKey prepared = activeKeys.get(0).recordIssuedTokenTtl(tokenTtl);
        activeKeys = List.of(prepared);
        return prepared;
    }

    @Override
    public SigningKey retire(UUID keyId, Instant at) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SigningKey revoke(UUID keyId, Instant at) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SigningKey revoke(UUID keyId, UUID replacementKeyId, Instant at) {
        throw new UnsupportedOperationException();
    }
}
