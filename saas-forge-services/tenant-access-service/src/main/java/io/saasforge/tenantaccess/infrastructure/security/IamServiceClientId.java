package io.saasforge.tenantaccess.infrastructure.security;

import java.util.UUID;

/** Tenant Access 允许调用 Membership Validation 的保留 IAM Client ID。 */
public record IamServiceClientId(UUID value) {
    public IamServiceClientId {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException("IAM Service Client ID 必须是 UUIDv7");
        }
    }
}
