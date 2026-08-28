package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OAuthClientManagementOperationRow {
    private UUID id;
    private UUID actorIdentityId;
    private UUID idempotencyKey;
    private String operationType;
    private UUID clientId;
    private byte[] requestFingerprint;
    private String outcome;
    private int httpStatus;
    private OffsetDateTime completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getActorIdentityId() { return actorIdentityId; }
    public void setActorIdentityId(UUID actorIdentityId) { this.actorIdentityId = actorIdentityId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public byte[] getRequestFingerprint() { return requestFingerprint == null ? null : requestFingerprint.clone(); }
    public void setRequestFingerprint(byte[] requestFingerprint) {
        this.requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
    }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
