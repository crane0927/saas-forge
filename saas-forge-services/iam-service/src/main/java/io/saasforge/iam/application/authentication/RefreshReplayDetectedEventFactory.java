package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class RefreshReplayDetectedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.refresh-replay-detected.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final String topic;

    public RefreshReplayDetectedEventFactory(
            ObjectMapper objectMapper, UuidV7Generator uuidV7Generator, String environment) {
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(RefreshTokenFamily family, int revokedAccessTokenCount, Instant at, String traceId) {
        UUID eventId = uuidV7Generator.next();
        Instant eventTime = at.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("familyId", family.id().toString());
        data.put("identityId", family.identityId().toString());
        data.put("purpose", family.purpose().name());
        data.put("result", "REFRESH_REPLAY_DETECTED");
        data.put("revokedAccessTokenCount", revokedAccessTokenCount);
        data.put("occurredAt", eventTime.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", family.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/iam-refresh-replay-detected.v1.schema.json");
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, eventTime, topic, family.identityId().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
