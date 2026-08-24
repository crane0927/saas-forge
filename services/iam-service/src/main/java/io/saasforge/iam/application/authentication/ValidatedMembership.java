package io.saasforge.iam.application.authentication;

import java.util.UUID;

public record ValidatedMembership(UUID membershipId, UUID tenantId) {
}
