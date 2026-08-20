package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class PlatformRoleAssignmentRow {
    private UUID id;
    private UUID identityId;
    private String roleKey;
    private OffsetDateTime assignedAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(OffsetDateTime assignedAt) { this.assignedAt = assignedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
