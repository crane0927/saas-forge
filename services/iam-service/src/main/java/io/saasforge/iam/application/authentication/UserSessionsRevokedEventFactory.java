package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class UserSessionsRevokedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.sessions-revoked.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final String topic;

    public UserSessionsRevokedEventFactory(
            ObjectMapper objectMapper, UuidV7Generator uuidV7Generator, String environment) {
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(
            UUID revocationRequestId, RevocationFenceTarget target, long revokedFamilyCount, Instant occurredAt) {
        UUID eventId = uuidV7Generator.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revocationRequestId", revocationRequestId.toString());
        data.put("scope", target.type().name());
        if (target.membershipId() != null) {
            data.put("membershipId", target.membershipId().toString());
        } else {
            data.put("tenantId", target.tenantId().toString());
        }
        data.put("revokedSessionCount", revokedFamilyCount);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", eventId.toString());
        event.put("source", SOURCE);
        event.put("type", EVENT_TYPE);
        event.put("subject", revocationRequestId.toString());
        event.put("time", eventTime.toString());
        event.put("datacontenttype", "application/json");
        event.put("data", data);
        return new OutboxEvent(eventId, eventTime, topic, revocationRequestId.toString(), null,
                objectMapper.writeValueAsString(event));
    }
}
