package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class TenantLifecycleRow {
    private UUID workflowId;
    private UUID tenantId;
    private UUID actorIdentityId;
    private UUID idempotencyKey;
    private String requestFingerprint;
    private String lifecycleAction;
    private UUID revocationRequestId;
    private UUID releaseRequestId;
    private String workflowStatus;
    private boolean fenceEstablished;
    private boolean revocationCallStarted;
    private long revokedFamilyCount;
    private long revokedJtiCount;
    private int attemptCount;
    private OffsetDateTime nextAttemptAt;
    private String leaseOwner;
    private OffsetDateTime leaseUntil;
    private long fencingToken;
    private OffsetDateTime recoveryStartedAt;
    private OffsetDateTime iamRecoveryConfirmedAt;
    private OffsetDateTime completedAt;
    private String responseBody;

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID value) { workflowId = value; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID value) { tenantId = value; }
    public UUID getActorIdentityId() { return actorIdentityId; }
    public void setActorIdentityId(UUID value) { actorIdentityId = value; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID value) { idempotencyKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public String getLifecycleAction() { return lifecycleAction; }
    public void setLifecycleAction(String value) { lifecycleAction = value; }
    public UUID getRevocationRequestId() { return revocationRequestId; }
    public void setRevocationRequestId(UUID value) { revocationRequestId = value; }
    public UUID getReleaseRequestId() { return releaseRequestId; }
    public void setReleaseRequestId(UUID value) { releaseRequestId = value; }
    public String getWorkflowStatus() { return workflowStatus; }
    public void setWorkflowStatus(String value) { workflowStatus = value; }
    public boolean isFenceEstablished() { return fenceEstablished; }
    public void setFenceEstablished(boolean value) { fenceEstablished = value; }
    public boolean isRevocationCallStarted() { return revocationCallStarted; }
    public void setRevocationCallStarted(boolean value) { revocationCallStarted = value; }
    public long getRevokedFamilyCount() { return revokedFamilyCount; }
    public void setRevokedFamilyCount(long value) { revokedFamilyCount = value; }
    public long getRevokedJtiCount() { return revokedJtiCount; }
    public void setRevokedJtiCount(long value) { revokedJtiCount = value; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int value) { attemptCount = value; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime value) { leaseUntil = value; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long value) { fencingToken = value; }
    public OffsetDateTime getRecoveryStartedAt() { return recoveryStartedAt; }
    public void setRecoveryStartedAt(OffsetDateTime value) { recoveryStartedAt = value; }
    public OffsetDateTime getIamRecoveryConfirmedAt() { return iamRecoveryConfirmedAt; }
    public void setIamRecoveryConfirmedAt(OffsetDateTime value) { iamRecoveryConfirmedAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String value) { responseBody = value; }
}
