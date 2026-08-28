package io.saasforge.iam.application.client;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientManagementOperation;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class ClientSecretIssuanceRecoveredEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.client-secret.issuance-recovered.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator ids;
    private final String topic;

    public ClientSecretIssuanceRecoveredEventFactory(
            ObjectMapper objectMapper, UuidV7Generator ids, String environment) {
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(
            OAuthClient client,
            OAuthClientManagementOperation operation,
            UUID actorIdentityId,
            Instant occurredAt,
            String traceId) {
        UUID eventId = ids.next();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientId", client.id().toString());
        data.put("operationId", operation.id().toString());
        data.put("originalOperationId", operation.originalOperationId().toString());
        data.put("result", "CLIENT_SECRET_ISSUANCE_RECOVERED");
        data.put("actorType", "IDENTITY");
        data.put("actorIdentityId", actorIdentityId.toString());
        data.put("occurredAt", occurredAt.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", client.id().toString());
        snapshot.put("time", occurredAt.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema",
                "https://saasforge.io/contracts/events/iam-client-secret-issuance-recovered.v1.schema.json");
        if (traceId != null) snapshot.put("traceId", traceId);
        snapshot.put("data", data);
        return new OutboxEvent(eventId, occurredAt, topic, client.id().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
