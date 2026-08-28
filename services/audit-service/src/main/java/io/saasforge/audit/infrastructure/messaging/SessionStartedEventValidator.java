package io.saasforge.audit.infrastructure.messaging;

import io.saasforge.audit.application.SessionStartedAuditRecord;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 将已登记的 IAM Session Started v1 契约收窄为 Audit 可持久化的白名单模型。 */
@Component
public class SessionStartedEventValidator {
    public static final String CONSUMER_NAME = "audit-service.iam-session-events";
    public static final String EVENT_TYPE = "com.saasforge.iam.session.started.v1";
    public static final String SOURCE = "urn:saasforge:iam-service";
    public static final String DATASCHEMA =
            "https://saasforge.io/contracts/events/iam-session-started.v1.schema.json";

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "traceId", "data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA_FIELDS = Set.of(
            "familyId", "identityId", "purpose", "contextType", "result", "occurredAt");
    private static final Set<String> PURPOSES = Set.of(
            "USER_PLATFORM", "USER_TENANT", "USER_TENANT_SELECTION", "INITIAL_PASSWORD_CHANGE");
    private static final Set<String> CONTEXT_TYPES = Set.of("PLATFORM", "TENANT");
    private static final Set<String> SESSION_RESULTS = Set.of(
            "ACCESS_TOKEN_ISSUED", "CONTEXT_SELECTION_REQUIRED", "PASSWORD_CHANGE_REQUIRED");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");

    private final ObjectMapper objectMapper;
    private final String expectedTopic;

    public SessionStartedEventValidator(
            ObjectMapper objectMapper,
            @Value("${saasforge.audit.iam-session-topic}") String expectedTopic) {
        this.objectMapper = objectMapper;
        if (expectedTopic == null
                || !expectedTopic.matches("^saasforge\\.[a-z][a-z0-9-]*\\.iam-service\\.events$")) {
            throw new IllegalArgumentException("IAM Session Started Topic 配置不合法");
        }
        this.expectedTopic = expectedTopic;
    }

    public SessionStartedAuditRecord validate(
            String topic, String orderingKey, String consumerName, String payload) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("Session Started payload 不是合法 JSON", exception);
        }
        requireObjectWithAllowedFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "Envelope");
        requireEquals("1.0", text(envelope, "specversion"), "specversion");
        requireEquals(SOURCE, text(envelope, "source"), "source");
        requireEquals(EVENT_TYPE, text(envelope, "type"), "type");
        requireEquals("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        requireEquals(DATASCHEMA, text(envelope, "dataschema"), "dataschema");
        requireEquals(CONSUMER_NAME, consumerName, "allowed consumer");
        requireEquals(expectedTopic, topic, "topic");

        UUID eventId = uuidV7(text(envelope, "id"), "id");
        Instant eventTime = utcInstant(text(envelope, "time"), "time");
        String traceId = optionalText(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) {
            throw invalid("traceId");
        }

        JsonNode data = envelope.path("data");
        requireObjectWithAllowedFields(data, DATA_FIELDS, DATA_FIELDS, "data");
        UUID familyId = uuidV7(text(data, "familyId"), "data.familyId");
        UUID identityId = uuidV7(text(data, "identityId"), "data.identityId");
        requireEquals(familyId.toString(), text(envelope, "subject"), "subject");
        requireEquals(identityId.toString(), orderingKey, "ordering key");
        String purpose = allowedText(data, "purpose", PURPOSES);
        String contextType = allowedText(data, "contextType", CONTEXT_TYPES);
        String result = allowedText(data, "result", SESSION_RESULTS);
        Instant dataOccurredAt = utcInstant(text(data, "occurredAt"), "data.occurredAt");
        if (!eventTime.equals(dataOccurredAt)) {
            throw invalid("data.occurredAt");
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("purpose", purpose);
        metadata.put("contextType", contextType);
        metadata.put("sessionOutcome", result);
        return new SessionStartedAuditRecord(
                eventId, SOURCE, EVENT_TYPE, eventTime, traceId, identityId, familyId,
                objectMapper.writeValueAsString(metadata));
    }

    private void requireObjectWithAllowedFields(
            JsonNode node, Set<String> allowed, Set<String> required, String field) {
        if (!node.isObject()) {
            throw invalid(field);
        }
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) {
            throw invalid(field + " fields");
        }
    }

    private String allowedText(JsonNode object, String field, Set<String> allowed) {
        String value = text(object, field);
        if (!allowed.contains(value)) {
            throw invalid("data." + field);
        }
        return value;
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field);
        }
        return value.asText();
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field);
        }
        return value.asText();
    }

    private UUID uuidV7(String value, String field) {
        if (!UUID_V7.matcher(value).matches()) {
            throw invalid(field);
        }
        return UUID.fromString(value);
    }

    private Instant utcInstant(String value, String field) {
        if (!value.endsWith("Z")) {
            throw invalid(field);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidAuditEventException("Session Started 契约字段不合法: " + field, exception);
        }
    }

    private void requireEquals(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw invalid(field);
        }
    }

    private InvalidAuditEventException invalid(String field) {
        return new InvalidAuditEventException("Session Started 契约字段不合法: " + field);
    }
}
