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

class TenantContextSwitchedEventValidatorTest {
    private static final String TOPIC = "saasforge.test.iam-service.events";
    private final TenantContextSwitchedEventValidator validator =
            new TenantContextSwitchedEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void mapsOnlyActorTenantFamilyAndMembershipMetadata() {
        var record = validator.validate(
                TOPIC, identityId(), SessionStartedEventValidator.CONSUMER_NAME,
                event("019535d9-0001-7000-8000-000000000001", ""));

        assertEquals(identityId(), record.actorIdentityId().toString());
        assertEquals(tenantId(), record.tenantId().toString());
        assertEquals("TENANT_CONTEXT_SWITCHED", record.action());
        assertEquals("REFRESH_TOKEN_FAMILY", record.resourceType());
        assertEquals(familyId(), record.resourceId().toString());
        assertEquals(
                "{\"previousMembershipId\":\"019535d9-0001-7000-8000-000000000004\","
                        + "\"targetMembershipId\":\"019535d9-0001-7000-8000-000000000005\"}",
                record.metadata());
        assertNull(record.traceId());
    }

    @ParameterizedTest
    @MethodSource("invalidContracts")
    void exposesContractSecurityAndMappingViolationsAsPermanentErrors(
            String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(topic, key, consumer, payload));
    }

    static Stream<Arguments> invalidContracts() {
        String valid = event("019535d9-0001-7000-8000-000000000001", "");
        return Stream.of(
                Arguments.of("saasforge.prod.iam-service.events", identityId(), consumer(), valid),
                Arguments.of(TOPIC, familyId(), consumer(), valid),
                Arguments.of(TOPIC, identityId(), "audit-service.other", valid),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "urn:saasforge:iam-service", "urn:saasforge:other")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "iam-tenant-context-switched.v1.schema.json", "other.v1.schema.json")),
                Arguments.of(TOPIC, identityId(), consumer(), event(
                        "019535d9-0001-7000-8000-000000000001", ",\"password\":\"secret\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "\"data\":{", "\"traceId\":\"00000000000000000000000000000000\",\"data\":{")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "019535d9-0001-7000-8000-000000000005",
                        "019535d9-0001-7000-8000-000000000004")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "\"subject\":\"019535d9-0001-7000-8000-000000000003\"",
                        "\"subject\":\"019535d9-0001-4000-8000-000000000003\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "2026-08-28T10:15:30Z", "2026-08-28T10:15:30+08:00")));
    }

    static String event(String eventId, String extraDataField) {
        return """
                {"specversion":"1.0","id":"%s",
                "source":"urn:saasforge:iam-service","type":"com.saasforge.iam.tenant-context-switched.v1",
                "subject":"019535d9-0001-7000-8000-000000000003","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saasforge.io/contracts/events/iam-tenant-context-switched.v1.schema.json",
                "data":{"identityId":"019535d9-0001-7000-8000-000000000002",
                "previousMembershipId":"019535d9-0001-7000-8000-000000000004",
                "membershipId":"019535d9-0001-7000-8000-000000000005",
                "tenantId":"019535d9-0001-7000-8000-000000000006"%s}}
                """.formatted(eventId, extraDataField).replace("\n", "");
    }

    static String identityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }

    static String familyId() {
        return "019535d9-0001-7000-8000-000000000003";
    }

    static String tenantId() {
        return "019535d9-0001-7000-8000-000000000006";
    }

    private static String consumer() {
        return SessionStartedEventValidator.CONSUMER_NAME;
    }
}
