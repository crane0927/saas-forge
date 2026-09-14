package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class PasswordSetupDeliveryRow {
    private UUID callerClientId;
    private UUID requestId;
    private UUID identityId;
    private String status;
    private UUID challengeId;
    private OffsetDateTime challengeExpiresAt;
    private OffsetDateTime completedAt;

    public UUID getCallerClientId() { return callerClientId; }
    public void setCallerClientId(UUID callerClientId) { this.callerClientId = callerClientId; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getChallengeId() { return challengeId; }
    public void setChallengeId(UUID challengeId) { this.challengeId = challengeId; }
    public OffsetDateTime getChallengeExpiresAt() { return challengeExpiresAt; }
    public void setChallengeExpiresAt(OffsetDateTime challengeExpiresAt) { this.challengeExpiresAt = challengeExpiresAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
