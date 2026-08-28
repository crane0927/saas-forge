package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OAuthClientRow {
    private UUID id;
    private String displayName;
    private String clientType;
    private String reservedServiceKey;
    private String[] allowedScopes;
    private String clientStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }
    public String getReservedServiceKey() { return reservedServiceKey; }
    public void setReservedServiceKey(String reservedServiceKey) { this.reservedServiceKey = reservedServiceKey; }
    public String[] getAllowedScopes() { return allowedScopes == null ? null : allowedScopes.clone(); }
    public void setAllowedScopes(String[] allowedScopes) { this.allowedScopes = allowedScopes == null ? null : allowedScopes.clone(); }
    public String getClientStatus() { return clientStatus; }
    public void setClientStatus(String clientStatus) { this.clientStatus = clientStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
