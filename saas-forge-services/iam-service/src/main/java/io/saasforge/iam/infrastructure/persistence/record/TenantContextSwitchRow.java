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
    private Integer resultHttpStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime refreshedAt;
    private int attemptCount;
    private OffsetDateTime nextAttemptAt;
    private String leaseOwner;
    private OffsetDateTime leaseUntil;
    private OffsetDateTime recoveryExhaustedAt;
    private String lastFailure;

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
    public Integer getResultHttpStatus() { return resultHttpStatus; }
    public void setResultHttpStatus(Integer resultHttpStatus) { this.resultHttpStatus = resultHttpStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(OffsetDateTime refreshedAt) { this.refreshedAt = refreshedAt; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public OffsetDateTime getRecoveryExhaustedAt() { return recoveryExhaustedAt; }
    public void setRecoveryExhaustedAt(OffsetDateTime recoveryExhaustedAt) {
        this.recoveryExhaustedAt = recoveryExhaustedAt;
    }
    public String getLastFailure() { return lastFailure; }
    public void setLastFailure(String lastFailure) { this.lastFailure = lastFailure; }
}
