package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ReservedServiceClientReplacementRow {
    private UUID replacementRequestId;
    private String serviceKey;
    private UUID oldClientId;
    private UUID newClientId;
    private byte[] requestFingerprint;
    private OffsetDateTime completedAt;

    public UUID getReplacementRequestId() { return replacementRequestId; }
    public void setReplacementRequestId(UUID replacementRequestId) { this.replacementRequestId = replacementRequestId; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
    public UUID getOldClientId() { return oldClientId; }
    public void setOldClientId(UUID oldClientId) { this.oldClientId = oldClientId; }
    public UUID getNewClientId() { return newClientId; }
    public void setNewClientId(UUID newClientId) { this.newClientId = newClientId; }
    public byte[] getRequestFingerprint() { return requestFingerprint == null ? null : requestFingerprint.clone(); }
    public void setRequestFingerprint(byte[] requestFingerprint) {
        this.requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
    }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
