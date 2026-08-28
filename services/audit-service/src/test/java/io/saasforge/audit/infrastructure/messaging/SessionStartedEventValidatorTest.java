package io.saasforge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class SessionStartedEventValidatorTest {
    private static final String TOPIC = "saasforge.test.iam-service.events";
    private final SessionStartedEventValidator validator =
            new SessionStartedEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void mapsOnlyNormalizedAndWhitelistedFields() {
        var record = validator.validate(
                TOPIC,
                "019535d9-0001-7000-8000-000000000002",
                SessionStartedEventValidator.CONSUMER_NAME,
                event("")).metadata();

        assertEquals(
                "{\"purpose\":\"USER_TENANT\",\"contextType\":\"TENANT\","
                        + "\"sessionOutcome\":\"ACCESS_TOKEN_ISSUED\"}",
                record);
        assertNull(validator.validate(
                TOPIC,
                "019535d9-0001-7000-8000-000000000002",
                SessionStartedEventValidator.CONSUMER_NAME,
                event("")).traceId());
    }

    @ParameterizedTest
    @MethodSource("invalidContracts")
    void rejectsContractAndSecurityBoundaryViolations(String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(topic, key, consumer, payload));
    }

    static Stream<Arguments> invalidContracts() {
        String valid = event("");
        return Stream.of(
                Arguments.of("saasforge.prod.iam-service.events", identityId(), consumer(), valid),
                Arguments.of(TOPIC, familyId(), consumer(), valid),
                Arguments.of(TOPIC, identityId(), "audit-service.other", valid),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "urn:saasforge:iam-service", "urn:saasforge:other")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "iam-session-started.v1.schema.json", "other.v1.schema.json")),
                Arguments.of(TOPIC, identityId(), consumer(), event(",\"password\":\"secret\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "\"occurredAt\":\"2026-08-28T10:15:30Z\"",
                        "\"occurredAt\":\"2026-08-28T10:15:31Z\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "019535d9-0001-7000-8000-000000000001", "019535d9-0001-4000-8000-000000000001")));
    }

    static String event(String extraDataField) {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000001",
                "source":"urn:saasforge:iam-service","type":"com.saasforge.iam.session.started.v1",
                "subject":"019535d9-0001-7000-8000-000000000003","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saasforge.io/contracts/events/iam-session-started.v1.schema.json",
                "data":{"familyId":"019535d9-0001-7000-8000-000000000003",
                "identityId":"019535d9-0001-7000-8000-000000000002","purpose":"USER_TENANT",
                "contextType":"TENANT","result":"ACCESS_TOKEN_ISSUED",
                "occurredAt":"2026-08-28T10:15:30Z"%s}}
                """.formatted(extraDataField).replace("\n", "");
    }

    private static String identityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }

    private static String familyId() {
        return "019535d9-0001-7000-8000-000000000003";
    }

    private static String consumer() {
        return SessionStartedEventValidator.CONSUMER_NAME;
    }
}
