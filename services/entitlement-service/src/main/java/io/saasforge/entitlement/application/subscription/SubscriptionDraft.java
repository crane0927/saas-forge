package io.saasforge.entitlement.application.subscription;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionDraft(UUID tenantId, UUID planId, Instant endsAt) { }
