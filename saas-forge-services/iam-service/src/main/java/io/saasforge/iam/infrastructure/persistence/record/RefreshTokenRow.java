package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RefreshTokenRow {
    private UUID id;
    private UUID familyId;
    private byte[] tokenDigest;
    private OffsetDateTime issuedAt;
    private OffsetDateTime consumedAt;
    private byte[] rotationKeyDigest;
    private OffsetDateTime recoveryExpiresAt;
    private OffsetDateTime recoveredAt;
    private UUID successorTokenId;
    private UUID successorAccessJti;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID familyId) { this.familyId = familyId; }
    public byte[] getTokenDigest() { return tokenDigest == null ? null : tokenDigest.clone(); }
    public void setTokenDigest(byte[] tokenDigest) { this.tokenDigest = tokenDigest == null ? null : tokenDigest.clone(); }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime issuedAt) { this.issuedAt = issuedAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
    public byte[] getRotationKeyDigest() { return rotationKeyDigest == null ? null : rotationKeyDigest.clone(); }
    public void setRotationKeyDigest(byte[] value) { rotationKeyDigest = value == null ? null : value.clone(); }
    public OffsetDateTime getRecoveryExpiresAt() { return recoveryExpiresAt; }
    public void setRecoveryExpiresAt(OffsetDateTime recoveryExpiresAt) { this.recoveryExpiresAt = recoveryExpiresAt; }
    public OffsetDateTime getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(OffsetDateTime recoveredAt) { this.recoveredAt = recoveredAt; }
    public UUID getSuccessorTokenId() { return successorTokenId; }
    public void setSuccessorTokenId(UUID successorTokenId) { this.successorTokenId = successorTokenId; }
    public UUID getSuccessorAccessJti() { return successorAccessJti; }
    public void setSuccessorAccessJti(UUID successorAccessJti) { this.successorAccessJti = successorAccessJti; }
}
