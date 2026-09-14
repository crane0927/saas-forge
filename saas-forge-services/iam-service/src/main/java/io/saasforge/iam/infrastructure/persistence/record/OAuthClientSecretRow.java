package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OAuthClientSecretRow {
    private UUID id;
    private UUID clientId;
    private byte[] secretDigest;
    private OffsetDateTime createdAt;
    private OffsetDateTime validUntil;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public byte[] getSecretDigest() { return secretDigest == null ? null : secretDigest.clone(); }
    public void setSecretDigest(byte[] secretDigest) { this.secretDigest = secretDigest == null ? null : secretDigest.clone(); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
