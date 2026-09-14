package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class UserSessionRevocationCandidateRow {
    private UUID familyId;
    private boolean revokeFamily;
    private UUID jti;
    private UUID identityId;
    private UUID membershipId;
    private UUID tenantId;
    private String kid;
    private OffsetDateTime issuedAt;
    private OffsetDateTime expiresAt;

    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID value) { familyId = value; }
    public boolean isRevokeFamily() { return revokeFamily; }
    public void setRevokeFamily(boolean value) { revokeFamily = value; }
    public UUID getJti() { return jti; }
    public void setJti(UUID value) { jti = value; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID value) { identityId = value; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID value) { membershipId = value; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID value) { tenantId = value; }
    public String getKid() { return kid; }
    public void setKid(String value) { kid = value; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime value) { issuedAt = value; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
}
