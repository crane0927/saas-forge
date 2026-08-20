package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OAuthClientRow {
    private UUID id;
    private String displayName;
    private String[] allowedScopes;
    private String clientStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String[] getAllowedScopes() { return allowedScopes == null ? null : allowedScopes.clone(); }
    public void setAllowedScopes(String[] allowedScopes) { this.allowedScopes = allowedScopes == null ? null : allowedScopes.clone(); }
    public String getClientStatus() { return clientStatus; }
    public void setClientStatus(String clientStatus) { this.clientStatus = clientStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
