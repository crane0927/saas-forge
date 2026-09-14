package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class IdentityProvisioningFactRow {
    private UUID callerClientId;
    private UUID requestId;
    private byte[] requestFingerprint;
    private UUID identityId;
    private String credentialStatus;
    private OffsetDateTime ensuredAt;

    public UUID getCallerClientId() { return callerClientId; }
    public void setCallerClientId(UUID callerClientId) { this.callerClientId = callerClientId; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public byte[] getRequestFingerprint() { return requestFingerprint == null ? null : requestFingerprint.clone(); }
    public void setRequestFingerprint(byte[] requestFingerprint) {
        this.requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
    }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getCredentialStatus() { return credentialStatus; }
    public void setCredentialStatus(String credentialStatus) { this.credentialStatus = credentialStatus; }
    public OffsetDateTime getEnsuredAt() { return ensuredAt; }
    public void setEnsuredAt(OffsetDateTime ensuredAt) { this.ensuredAt = ensuredAt; }
}
