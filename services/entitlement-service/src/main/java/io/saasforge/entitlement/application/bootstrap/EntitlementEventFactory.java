package io.saasforge.entitlement.application.bootstrap;

import io.saasforge.entitlement.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class EntitlementEventFactory {
    private static final String SOURCE = "urn:saasforge:entitlement-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public EntitlementEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent quotaDefinition(
            QuotaDefinitionResult result, UUID actorIdentityId, Instant occurredAt, String traceId, boolean activated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quotaDefinitionId", result.id().toString());
        data.put("code", result.code());
        data.put("status", result.status().name());
        data.put("actorIdentityId", actorIdentityId.toString());
        String action = activated ? "activated" : "created";
        return event(result.id(), "com.saasforge.quota-definition." + action + ".v1",
                "quota-definition-" + action + ".v1.schema.json", data, occurredAt, traceId);
    }

    public OutboxEvent plan(
            PlanResult result, UUID actorIdentityId, Instant occurredAt, String traceId, boolean activated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", result.id().toString());
        data.put("code", result.code());
        data.put("status", result.status().name());
        data.put("actorIdentityId", actorIdentityId.toString());
        String action = activated ? "activated" : "created";
        return event(result.id(), "com.saasforge.plan." + action + ".v1",
                "plan-" + action + ".v1.schema.json", data, occurredAt, traceId);
    }

    private OutboxEvent event(
            UUID aggregateId,
            String type,
            String schema,
            Map<String, Object> data,
            Instant occurredAt,
            String traceId) {
        UUID eventId = ids.next();
        Instant time = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", type);
        snapshot.put("subject", aggregateId.toString());
        snapshot.put("time", time.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/" + schema);
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, aggregateId, time, topic, aggregateId.toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
