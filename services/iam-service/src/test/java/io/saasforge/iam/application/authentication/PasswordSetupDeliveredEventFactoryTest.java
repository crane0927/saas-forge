package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PasswordSetupDeliveredEventFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00.123456Z");
    private static final UUID IDENTITY_ID = UUID.fromString("019535d9-0000-7000-8000-000000000021");
    private static final UUID REQUEST_ID = UUID.fromString("019535d9-0000-7000-8000-000000000022");
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void createsCompleteCloudEventWithOptionalTraceContext() {
        var factory = new PasswordSetupDeliveredEventFactory(
                new ObjectMapper(),
                new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom()),
                "test");

        var traced = factory.create(IDENTITY_ID, REQUEST_ID, NOW.plusSeconds(60), NOW, TRACE_ID);
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), traced.occurredAt());
        assertEquals("saasforge.test.iam-service.events", traced.topic());
        assertEquals(IDENTITY_ID.toString(), traced.orderingKey());
        assertEquals(TRACE_ID, traced.traceId());
        assertTrue(traced.eventSnapshot().contains(PasswordSetupDeliveredEventFactory.EVENT_TYPE));
        assertTrue(traced.eventSnapshot().contains(REQUEST_ID.toString()));
        assertTrue(traced.eventSnapshot().contains(TRACE_ID));

        var untraced = factory.create(IDENTITY_ID, REQUEST_ID, NOW.plusSeconds(60), NOW, null);
        assertFalse(untraced.eventSnapshot().contains("traceId"));
    }
}
