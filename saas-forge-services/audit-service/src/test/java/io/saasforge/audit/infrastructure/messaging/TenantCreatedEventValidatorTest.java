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

class TenantCreatedEventValidatorTest {
    static final String TOPIC = "saasforge.test.tenant-access-service.events";
    private final TenantCreatedEventValidator validator =
            new TenantCreatedEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void mapsOnlyConfirmedTenantCreationFields() {
        var record = validator.validate(TOPIC, tenantId(), TenantCreatedEventValidator.CONSUMER_NAME,
                event("019535d9-0001-7000-8000-000000000001", ""));

        assertEquals(actorIdentityId(), record.actorIdentityId().toString());
        assertEquals(tenantId(), record.tenantId().toString());
        assertEquals("TENANT_CREATED", record.action());
        assertEquals("TENANT", record.resourceType());
        assertEquals(tenantId(), record.resourceId().toString());
        assertEquals("{\"initialStatus\":\"PENDING\"}", record.metadata());
        assertNull(record.traceId());
    }

    @ParameterizedTest
    @MethodSource("invalidContracts")
    void exposesContractSecurityAndAuthorizationViolationsAsPermanentErrors(
            String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(topic, key, consumer, payload));
    }

    static Stream<Arguments> invalidContracts() {
        String valid = event("019535d9-0001-7000-8000-000000000001", "");
        return Stream.of(
                Arguments.of("saasforge.prod.tenant-access-service.events", tenantId(), consumer(), valid),
                Arguments.of(TOPIC, actorIdentityId(), consumer(), valid),
                Arguments.of(TOPIC, tenantId(), "audit-service.other", valid),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "urn:saasforge:tenant-access-service", "urn:saasforge:other")),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "tenant-created.v1.schema.json", "other.v1.schema.json")),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "\"status\":\"PENDING\"", "\"status\":\"ACTIVE\"")),
                Arguments.of(TOPIC, tenantId(), consumer(), event(
                        "019535d9-0001-7000-8000-000000000001", ",\"clientSecret\":\"secret\"")),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "\"data\":{", "\"traceId\":\"00000000000000000000000000000000\",\"data\":{")),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "\"subject\":\"" + tenantId() + "\"",
                        "\"subject\":\"019535d9-0001-7000-8000-000000000009\"")),
                Arguments.of(TOPIC, tenantId(), consumer(), valid.replace(
                        "2026-08-28T10:15:30Z", "2026-08-28T10:15:30+08:00")));
    }

    static String event(String eventId, String extraDataField) {
        return """
                {"specversion":"1.0","id":"%s",
                "source":"urn:saasforge:tenant-access-service","type":"com.saasforge.tenant.created.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saasforge.io/contracts/events/tenant-created.v1.schema.json",
                "data":{"tenantId":"%s","status":"PENDING","actorIdentityId":"%s"%s}}
                """.formatted(eventId, tenantId(), tenantId(), actorIdentityId(), extraDataField).replace("\n", "");
    }

    static String tenantId() {
        return "019535d9-0001-7000-8000-000000000006";
    }

    static String actorIdentityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }

    private static String consumer() {
        return TenantCreatedEventValidator.CONSUMER_NAME;
    }
}
