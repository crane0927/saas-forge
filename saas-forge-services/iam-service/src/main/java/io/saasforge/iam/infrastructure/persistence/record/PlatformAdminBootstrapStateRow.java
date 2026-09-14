package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class PlatformAdminBootstrapStateRow {
    private UUID factIdentityId;
    private UUID factCredentialId;
    private UUID factRoleAssignmentId;
    private UUID factEventId;
    private OffsetDateTime initializedAt;
    private String normalizedEmail;
    private String displayName;
    private OffsetDateTime identityCreatedAt;
    private String credentialType;
    private String passwordHash;
    private OffsetDateTime credentialIssuedAt;
    private OffsetDateTime credentialExpiresAt;
    private OffsetDateTime credentialInvalidatedAt;
    private String roleKey;
    private OffsetDateTime roleAssignedAt;
    private OffsetDateTime roleRevokedAt;
    private Integer identityCredentialCount;
    private Integer identityRoleAssignmentCount;

    public UUID getFactIdentityId() { return factIdentityId; }
    public void setFactIdentityId(UUID factIdentityId) { this.factIdentityId = factIdentityId; }
    public UUID getFactCredentialId() { return factCredentialId; }
    public void setFactCredentialId(UUID factCredentialId) { this.factCredentialId = factCredentialId; }
    public UUID getFactRoleAssignmentId() { return factRoleAssignmentId; }
    public void setFactRoleAssignmentId(UUID factRoleAssignmentId) { this.factRoleAssignmentId = factRoleAssignmentId; }
    public UUID getFactEventId() { return factEventId; }
    public void setFactEventId(UUID factEventId) { this.factEventId = factEventId; }
    public OffsetDateTime getInitializedAt() { return initializedAt; }
    public void setInitializedAt(OffsetDateTime initializedAt) { this.initializedAt = initializedAt; }
    public String getNormalizedEmail() { return normalizedEmail; }
    public void setNormalizedEmail(String normalizedEmail) { this.normalizedEmail = normalizedEmail; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public OffsetDateTime getIdentityCreatedAt() { return identityCreatedAt; }
    public void setIdentityCreatedAt(OffsetDateTime identityCreatedAt) { this.identityCreatedAt = identityCreatedAt; }
    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public OffsetDateTime getCredentialIssuedAt() { return credentialIssuedAt; }
    public void setCredentialIssuedAt(OffsetDateTime credentialIssuedAt) { this.credentialIssuedAt = credentialIssuedAt; }
    public OffsetDateTime getCredentialExpiresAt() { return credentialExpiresAt; }
    public void setCredentialExpiresAt(OffsetDateTime credentialExpiresAt) { this.credentialExpiresAt = credentialExpiresAt; }
    public OffsetDateTime getCredentialInvalidatedAt() { return credentialInvalidatedAt; }
    public void setCredentialInvalidatedAt(OffsetDateTime credentialInvalidatedAt) { this.credentialInvalidatedAt = credentialInvalidatedAt; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public OffsetDateTime getRoleAssignedAt() { return roleAssignedAt; }
    public void setRoleAssignedAt(OffsetDateTime roleAssignedAt) { this.roleAssignedAt = roleAssignedAt; }
    public OffsetDateTime getRoleRevokedAt() { return roleRevokedAt; }
    public void setRoleRevokedAt(OffsetDateTime roleRevokedAt) { this.roleRevokedAt = roleRevokedAt; }
    public Integer getIdentityCredentialCount() { return identityCredentialCount; }
    public void setIdentityCredentialCount(Integer identityCredentialCount) {
        this.identityCredentialCount = identityCredentialCount;
    }
    public Integer getIdentityRoleAssignmentCount() { return identityRoleAssignmentCount; }
    public void setIdentityRoleAssignmentCount(Integer identityRoleAssignmentCount) {
        this.identityRoleAssignmentCount = identityRoleAssignmentCount;
    }
}
