package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RefreshTokenRow {
    private UUID id;
    private UUID familyId;
    private byte[] tokenDigest;
    private OffsetDateTime issuedAt;
    private OffsetDateTime consumedAt;

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
}
