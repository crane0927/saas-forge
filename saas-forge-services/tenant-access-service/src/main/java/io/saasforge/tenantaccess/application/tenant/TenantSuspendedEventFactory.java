package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantSuspendedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.tenant.suspended.v1";
    private static final String SOURCE = "urn:saasforge:tenant-access-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public TenantSuspendedEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(
            Tenant tenant, UUID actorIdentityId, long revokedSessionCount, Instant occurredAt, String traceId) {
        UUID eventId = ids.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenant.id().toString());
        data.put("actorIdentityId", actorIdentityId.toString());
        // 对外只保留既有 Session 计数，不暴露 IAM 的 jti 级明细。
        data.put("revokedSessionCount", revokedSessionCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", tenant.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/tenant-suspended.v1.schema.json");
        if (traceId != null) snapshot.put("traceId", traceId);
        snapshot.put("data", data);
        return new OutboxEvent(eventId, tenant.id(), eventTime, topic, tenant.id().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
