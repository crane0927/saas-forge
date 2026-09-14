package io.saasforge.iam.application.authentication;

import java.util.UUID;

@FunctionalInterface
public interface UserTokenIssuanceFence {
    void assertIssuable(UUID membershipId, UUID tenantId);
}
