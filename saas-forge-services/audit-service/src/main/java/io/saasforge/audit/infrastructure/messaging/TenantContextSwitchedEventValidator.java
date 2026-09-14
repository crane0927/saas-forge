package io.saasforge.audit.infrastructure.messaging;

import io.saasforge.audit.application.AuditRecord;
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

/** 将已登记的 IAM Tenant Context Switched v1 契约收窄为 Audit 可持久化的白名单模型。 */
@Component
public class TenantContextSwitchedEventValidator {
    public static final String EVENT_TYPE = "com.saasforge.iam.tenant-context-switched.v1";
    public static final String SOURCE = "urn:saasforge:iam-service";
    public static final String DATASCHEMA =
            "https://saasforge.io/contracts/events/iam-tenant-context-switched.v1.schema.json";

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "traceId", "data");
    private static final Set<String> REQUIRED_ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");
    private static final Set<String> DATA_FIELDS = Set.of(
            "identityId", "previousMembershipId", "membershipId", "tenantId");
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");

    private final ObjectMapper objectMapper;
    private final String expectedTopic;

    public TenantContextSwitchedEventValidator(
            ObjectMapper objectMapper,
            @Value("${saasforge.audit.iam-session-topic}") String expectedTopic) {
        this.objectMapper = objectMapper;
        if (expectedTopic == null
                || !expectedTopic.matches("^saasforge\\.[a-z][a-z0-9-]*\\.iam-service\\.events$")) {
            throw new IllegalArgumentException("IAM Tenant Context Switched Topic 配置不合法");
        }
        this.expectedTopic = expectedTopic;
    }

    public AuditRecord validate(String topic, String orderingKey, String consumerName, String payload) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("Tenant Context Switched payload 不是合法 JSON", exception);
        }
        requireObjectWithAllowedFields(envelope, ENVELOPE_FIELDS, REQUIRED_ENVELOPE_FIELDS, "Envelope");
        requireEquals("1.0", text(envelope, "specversion"), "specversion");
        requireEquals(SOURCE, text(envelope, "source"), "source");
        requireEquals(EVENT_TYPE, text(envelope, "type"), "type");
        requireEquals("application/json", text(envelope, "datacontenttype"), "datacontenttype");
        requireEquals(DATASCHEMA, text(envelope, "dataschema"), "dataschema");
        requireEquals(SessionStartedEventValidator.CONSUMER_NAME, consumerName, "allowed consumer");
        requireEquals(expectedTopic, topic, "topic");

        UUID eventId = uuidV7(text(envelope, "id"), "id");
        UUID familyId = uuidV7(text(envelope, "subject"), "subject");
        Instant eventTime = utcInstant(text(envelope, "time"), "time");
        String traceId = optionalText(envelope, "traceId");
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) {
            throw invalid("traceId");
        }

        JsonNode data = envelope.path("data");
        requireObjectWithAllowedFields(data, DATA_FIELDS, DATA_FIELDS, "data");
        UUID identityId = uuidV7(text(data, "identityId"), "data.identityId");
        UUID previousMembershipId = uuidV7(
                text(data, "previousMembershipId"), "data.previousMembershipId");
        UUID membershipId = uuidV7(text(data, "membershipId"), "data.membershipId");
        UUID tenantId = uuidV7(text(data, "tenantId"), "data.tenantId");
        requireEquals(identityId.toString(), orderingKey, "ordering key");
        if (previousMembershipId.equals(membershipId)) {
            throw invalid("data.membershipId unchanged");
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("previousMembershipId", previousMembershipId.toString());
        metadata.put("targetMembershipId", membershipId.toString());
        return new AuditRecord(
                eventId, SOURCE, EVENT_TYPE, eventTime, traceId, identityId, tenantId,
                "TENANT_CONTEXT_SWITCHED", "REFRESH_TOKEN_FAMILY", familyId,
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
            throw new InvalidAuditEventException(
                    "Tenant Context Switched 契约字段不合法: " + field, exception);
        }
    }

    private void requireEquals(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw invalid(field);
        }
    }

    private InvalidAuditEventException invalid(String field) {
        return new InvalidAuditEventException("Tenant Context Switched 契约字段不合法: " + field);
    }
}
