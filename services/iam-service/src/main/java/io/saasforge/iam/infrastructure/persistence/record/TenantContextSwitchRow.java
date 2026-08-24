package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class TenantContextSwitchRow {
    private UUID id;
    private UUID familyId;
    private UUID idempotencyKey;
    private UUID targetMembershipId;
    private byte[] targetFingerprint;
    private long expectedContextVersion;
    private String switchStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID familyId) { this.familyId = familyId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getTargetMembershipId() { return targetMembershipId; }
    public void setTargetMembershipId(UUID targetMembershipId) { this.targetMembershipId = targetMembershipId; }
    public byte[] getTargetFingerprint() { return targetFingerprint == null ? null : targetFingerprint.clone(); }
    public void setTargetFingerprint(byte[] targetFingerprint) {
        this.targetFingerprint = targetFingerprint == null ? null : targetFingerprint.clone();
    }
    public long getExpectedContextVersion() { return expectedContextVersion; }
    public void setExpectedContextVersion(long expectedContextVersion) {
        this.expectedContextVersion = expectedContextVersion;
    }
    public String getSwitchStatus() { return switchStatus; }
    public void setSwitchStatus(String switchStatus) { this.switchStatus = switchStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
