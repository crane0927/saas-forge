package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RefreshTokenFamilyRow {
    private UUID id;
    private UUID identityId;
    private String familyPurpose;
    private UUID membershipId;
    private UUID tenantId;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime absoluteExpiresAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getFamilyPurpose() { return familyPurpose; }
    public void setFamilyPurpose(String familyPurpose) { this.familyPurpose = familyPurpose; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID membershipId) { this.membershipId = membershipId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getAbsoluteExpiresAt() { return absoluteExpiresAt; }
    public void setAbsoluteExpiresAt(OffsetDateTime absoluteExpiresAt) { this.absoluteExpiresAt = absoluteExpiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
