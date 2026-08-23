package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantCreatedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.tenant.created.v1";
    private static final String SOURCE = "urn:saasforge:tenant-access-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public TenantCreatedEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(Tenant tenant, UUID actorIdentityId, Instant occurredAt, String traceId) {
        UUID eventId = ids.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenant.id().toString());
        data.put("status", tenant.status().name());
        data.put("actorIdentityId", actorIdentityId.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", tenant.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/tenant-created.v1.schema.json");
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, tenant.id(), eventTime, topic, tenant.id().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
