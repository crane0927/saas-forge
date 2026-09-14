package io.saas.forge.tenantaccess.application.membership;

import java.util.UUID;

public record ValidatedMembership(UUID membershipId, UUID tenantId) {
}
