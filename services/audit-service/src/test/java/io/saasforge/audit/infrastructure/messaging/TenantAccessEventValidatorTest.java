package io.saasforge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantAccessEventValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantAccessEventValidator validator = new TenantAccessEventValidator(
            objectMapper,
            new TenantCreatedEventValidator(objectMapper, TenantCreatedEventValidatorTest.TOPIC),
            TenantCreatedEventValidatorTest.TOPIC);

    @Test
    void routesTenantCreatedToItsStrictValidator() {
        var result = validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                TenantCreatedEventValidatorTest.event("019535d9-0001-7000-8000-000000000001", ""));

        assertEquals("TENANT_CREATED", result.orElseThrow().action());
    }

    @Test
    void ignoresRegisteredTenantTypeOutsideThisConsumerSlice() {
        var result = validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registeredTenantSuspendedEvent());

        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsUnregisteredTypeWrongSourceTopicOrConsumerThroughPermanentErrorEntry() {
        String registered = registeredTenantSuspendedEvent();
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered.replace("com.saasforge.tenant.suspended.v1", "com.saasforge.tenant.other.v1")));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered.replace("urn:saasforge:tenant-access-service", "urn:saasforge:other")));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                "saasforge.prod.tenant-access-service.events",
                TenantCreatedEventValidatorTest.tenantId(),
                TenantCreatedEventValidator.CONSUMER_NAME,
                registered));
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                TenantCreatedEventValidatorTest.TOPIC,
                TenantCreatedEventValidatorTest.tenantId(),
                "audit-service.other",
                registered));
    }

    static String registeredTenantSuspendedEvent() {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000008",
                "source":"urn:saasforge:tenant-access-service","type":"com.saasforge.tenant.suspended.v1",
                "subject":"%s","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saasforge.io/contracts/events/tenant-suspended.v1.schema.json",
                "data":{"tenantId":"%s","actorIdentityId":"%s","revokedSessionCount":2}}
                """.formatted(
                        TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.tenantId(),
                        TenantCreatedEventValidatorTest.actorIdentityId()).replace("\n", "");
    }
}
