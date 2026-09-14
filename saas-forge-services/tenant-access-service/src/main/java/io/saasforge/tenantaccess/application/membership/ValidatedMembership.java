package io.saasforge.tenantaccess.application.membership;

import java.util.UUID;

public record ValidatedMembership(UUID membershipId, UUID tenantId) {
}
