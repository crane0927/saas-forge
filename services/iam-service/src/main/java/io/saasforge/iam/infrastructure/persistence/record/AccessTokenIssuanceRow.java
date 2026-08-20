package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class AccessTokenIssuanceRow {
    private UUID jti;
    private UUID familyId;
    private UUID identityId;
    private UUID membershipId;
    private UUID tenantId;
    private String kid;
    private OffsetDateTime issuedAt;
    private OffsetDateTime expiresAt;

    public UUID getJti() { return jti; }
    public void setJti(UUID jti) { this.jti = jti; }
    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID familyId) { this.familyId = familyId; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID membershipId) { this.membershipId = membershipId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime issuedAt) { this.issuedAt = issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
