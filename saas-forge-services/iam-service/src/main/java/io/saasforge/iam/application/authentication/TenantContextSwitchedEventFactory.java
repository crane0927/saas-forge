package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantContextSwitchedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.tenant-context-switched.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final String topic;

    public TenantContextSwitchedEventFactory(
            ObjectMapper objectMapper, UuidV7Generator uuidV7Generator, String environment) {
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(
            UUID familyId,
            UUID identityId,
            UUID previousMembershipId,
            UUID membershipId,
            UUID tenantId,
            Instant occurredAt,
            String traceId) {
        UUID eventId = uuidV7Generator.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("identityId", identityId.toString());
        data.put("previousMembershipId", previousMembershipId.toString());
        data.put("membershipId", membershipId.toString());
        data.put("tenantId", tenantId.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", familyId.toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/iam-tenant-context-switched.v1.schema.json");
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, eventTime, topic, identityId.toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
