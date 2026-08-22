package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class PasswordSetupChallengeRow {
    private UUID id;
    private UUID identityId;
    private byte[] tokenDigest;
    private OffsetDateTime issuedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime invalidatedAt;
    private OffsetDateTime consumedAt;
    private UUID idempotencyKey;
    private byte[] requestFingerprint;
    private UUID credentialId;
    private Integer completedStatus;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public byte[] getTokenDigest() { return tokenDigest == null ? null : tokenDigest.clone(); }
    public void setTokenDigest(byte[] value) { tokenDigest = value == null ? null : value.clone(); }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime issuedAt) { this.issuedAt = issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(OffsetDateTime invalidatedAt) { this.invalidatedAt = invalidatedAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public byte[] getRequestFingerprint() { return requestFingerprint == null ? null : requestFingerprint.clone(); }
    public void setRequestFingerprint(byte[] value) { requestFingerprint = value == null ? null : value.clone(); }
    public UUID getCredentialId() { return credentialId; }
    public void setCredentialId(UUID credentialId) { this.credentialId = credentialId; }
    public Integer getCompletedStatus() { return completedStatus; }
    public void setCompletedStatus(Integer completedStatus) { this.completedStatus = completedStatus; }
}
