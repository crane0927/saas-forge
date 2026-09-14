package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class PlatformAdminCredentialResetFactRow {
    private UUID resetRequestId;
    private UUID identityId;
    private UUID credentialId;
    private UUID eventId;
    private OffsetDateTime resetAt;

    public UUID getResetRequestId() { return resetRequestId; }
    public void setResetRequestId(UUID resetRequestId) { this.resetRequestId = resetRequestId; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public UUID getCredentialId() { return credentialId; }
    public void setCredentialId(UUID credentialId) { this.credentialId = credentialId; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public OffsetDateTime getResetAt() { return resetAt; }
    public void setResetAt(OffsetDateTime resetAt) { this.resetAt = resetAt; }
}
