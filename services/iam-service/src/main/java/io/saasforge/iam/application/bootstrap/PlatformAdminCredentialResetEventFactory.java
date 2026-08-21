package io.saasforge.iam.application.bootstrap;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class PlatformAdminCredentialResetEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.platform-admin-initial-credential-reset.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final String topic;

    public PlatformAdminCredentialResetEventFactory(
            ObjectMapper objectMapper, UuidV7Generator uuidV7Generator, String environment) {
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(
            UUID resetRequestId,
            UUID identityId,
            PasswordCredential credential,
            Instant occurredAt,
            String traceId) {
        UUID eventId = uuidV7Generator.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("identityId", identityId.toString());
        data.put("resetRequestId", resetRequestId.toString());
        data.put("credentialId", credential.id().toString());
        data.put("initialCredentialExpiresAt", credential.expiresAt().toString());
        data.put("result", "PLATFORM_ADMIN_INITIAL_CREDENTIAL_RESET");
        data.put("actor", "SECURITY_OPERATIONS");
        data.put("occurredAt", eventTime.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", identityId.toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema",
                "https://saasforge.io/contracts/events/iam-platform-admin-initial-credential-reset.v1.schema.json");
        snapshot.put("traceId", traceId);
        snapshot.put("data", data);
        return new OutboxEvent(eventId, eventTime, topic, identityId.toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
