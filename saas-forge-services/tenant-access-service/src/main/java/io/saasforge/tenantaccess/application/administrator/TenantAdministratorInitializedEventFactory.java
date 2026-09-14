package io.saasforge.tenantaccess.application.administrator;

import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TenantAdministratorInitializedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.tenant.administrator-initialized.v1";
    private static final String SOURCE = "urn:saasforge:tenant-access-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public TenantAdministratorInitializedEventFactory(ObjectMapper objectMapper, UuidV7Generator ids, String topic) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = topic;
    }

    public OutboxEvent create(
            UUID tenantId,
            UUID membershipId,
            UUID identityId,
            UUID roleId,
            UUID actorIdentityId,
            Instant occurredAt,
            String traceId) {
        UUID eventId = ids.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenantId.toString());
        data.put("membershipId", membershipId.toString());
        data.put("identityId", identityId.toString());
        data.put("roleId", roleId.toString());
        data.put("status", "ACTIVE");
        data.put("actorIdentityId", actorIdentityId.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", tenantId.toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, tenantId, eventTime, topic, tenantId.toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
