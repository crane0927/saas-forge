package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public final class SessionStartedEventFactory {
    public static final String EVENT_TYPE = "com.saasforge.iam.session.started.v1";
    private static final String SOURCE = "urn:saasforge:iam-service";

    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final String topic;

    public SessionStartedEventFactory(ObjectMapper objectMapper, UuidV7Generator uuidV7Generator, String environment) {
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.topic = "saasforge." + environment + ".iam-service.events";
    }

    public OutboxEvent create(RefreshTokenFamily family, Instant occurredAt, String traceId) {
        var eventId = uuidV7Generator.next();
        Instant eventTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("familyId", family.id().toString());
        data.put("identityId", family.identityId().toString());
        data.put("purpose", family.purpose().name());
        data.put("contextType", "PLATFORM");
        data.put("result", "ACCESS_TOKEN_ISSUED");
        data.put("occurredAt", eventTime.toString());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("specversion", "1.0");
        snapshot.put("id", eventId.toString());
        snapshot.put("source", SOURCE);
        snapshot.put("type", EVENT_TYPE);
        snapshot.put("subject", family.id().toString());
        snapshot.put("time", eventTime.toString());
        snapshot.put("datacontenttype", "application/json");
        snapshot.put("dataschema", "https://saasforge.io/contracts/events/iam-session-started.v1.schema.json");
        if (traceId != null) {
            snapshot.put("traceId", traceId);
        }
        snapshot.put("data", data);
        return new OutboxEvent(eventId, eventTime, topic, family.identityId().toString(), traceId,
                objectMapper.writeValueAsString(snapshot));
    }
}
