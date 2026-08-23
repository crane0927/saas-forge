package io.saasforge.tenantaccess.application.administrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InitializeTenantAdministratorServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");
    private static final UUID ACTOR = uuidV7(1);
    private static final UUID KEY = uuidV7(2);
    private static final UUID TENANT = uuidV7(3);
    private static final UUID IDENTITY = uuidV7(4);

    private final List<String> calls = new ArrayList<>();
    private final InMemoryWorkflows workflows = new InMemoryWorkflows();
    private IdentityCredentialDisposition disposition;
    private QuotaUnavailableException quotaFailure;
    private boolean deliveryFails;
    private InitializeTenantAdministratorService service;

    @BeforeEach
    void setUp() {
        disposition = IdentityCredentialDisposition.SETUP_ALLOWED;
        quotaFailure = null;
        deliveryFails = false;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new InitializeTenantAdministratorService(
                workflows,
                (requestId, email, displayName) -> {
                    calls.add("identity:" + requestId);
                    return new IdentityProvisioningGateway.Result(IDENTITY, disposition);
                },
                (tenantId, operationId) -> {
                    calls.add("quota:" + operationId);
                    if (quotaFailure != null) {
                        throw quotaFailure;
                    }
                },
                (requestId, identityId) -> {
                    calls.add("delivery:" + requestId);
                    if (deliveryFails) {
                        throw new RemoteWorkflowUnavailableException(new IllegalStateException("SMTP unavailable"));
                    }
                },
                new UuidV7Generator(clock, new SecureRandom()),
                clock);
    }

    @Test
    void persistsStableOperationsBeforeRemoteCallsAndReplaysSuccess() {
        TenantAdministratorInitializationResult first = initialize();
        TenantAdministratorInitializationResult replay = initialize();

        assertSame(first, replay);
        assertEquals(List.of(
                "prepare",
                "identity:" + workflows.workflow.identityRequestId(),
                "quota:" + workflows.workflow.consumeOperationId(),
                "activate",
                "delivery:" + workflows.workflow.passwordDeliveryRequestId(),
                "delivery-complete",
                "prepare"), calls);
    }

    @Test
    void passwordReadySkipsDeliveryAndRecoveryStopsBeforeQuota() {
        disposition = IdentityCredentialDisposition.PASSWORD_READY;
        initialize();
        assertEquals(List.of("prepare", "identity:" + workflows.workflow.identityRequestId(),
                "quota:" + workflows.workflow.consumeOperationId(), "activate"), calls);

        calls.clear();
        workflows.workflow = null;
        disposition = IdentityCredentialDisposition.RECOVERY_REQUIRED;
        TenantAdministratorInitializationException exception = assertThrows(
                TenantAdministratorInitializationException.class, this::initialize);
        assertEquals("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", exception.code());
        assertEquals(List.of("prepare", "identity:" + workflows.workflow.identityRequestId(), "failure"), calls);
    }

    @Test
    void quotaExhaustionIsStableAndDeliveryFailureDoesNotRollbackActivation() {
        quotaFailure = new QuotaUnavailableException("QUOTA_EXCEEDED", new IllegalStateException());
        TenantAdministratorInitializationException exhausted = assertThrows(
                TenantAdministratorInitializationException.class, this::initialize);
        assertEquals("QUOTA_EXCEEDED", exhausted.code());
        assertEquals("QUOTA_EXCEEDED", workflows.workflow.outcomeCode());

        calls.clear();
        workflows.workflow = null;
        quotaFailure = null;
        deliveryFails = true;
        TenantAdministratorInitializationResult result = initialize();
        assertEquals(TenantStatus.ACTIVE, result.status());
        assertEquals(List.of("prepare", "identity:" + workflows.workflow.identityRequestId(),
                "quota:" + workflows.workflow.consumeOperationId(), "activate",
                "delivery:" + workflows.workflow.passwordDeliveryRequestId()), calls);
    }

    private TenantAdministratorInitializationResult initialize() {
        return service.initialize(ACTOR, KEY, TENANT, "admin@example.com", "Admin", null);
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private final class InMemoryWorkflows implements TenantAdministratorInitializationRepository {
        private InitializationWorkflow workflow;

        @Override
        public InitializationWorkflow prepare(InitializationWorkflow candidate, Instant now) {
            calls.add("prepare");
            if (workflow == null) {
                workflow = candidate;
            }
            return workflow;
        }

        @Override
        public void completeFailure(UUID tenantId, UUID workflowId, String outcomeCode, Instant completedAt) {
            calls.add("failure");
            workflow = completed(workflow, outcomeCode, null);
        }

        @Override
        public TenantAdministratorInitializationResult activate(
                InitializationWorkflow current,
                UUID administratorIdentityId,
                IdentityCredentialDisposition credentialDisposition,
                Instant activatedAt) {
            calls.add("activate");
            TenantAdministratorInitializationResult result = new TenantAdministratorInitializationResult(
                    TENANT, "Acme", TenantStatus.ACTIVE, NOW.plusSeconds(3600), NOW.minusSeconds(60), NOW);
            workflow = completed(workflow, "SUCCESS", result);
            return result;
        }

        @Override
        public void completePasswordDelivery(UUID tenantId, UUID workflowId, Instant completedAt) {
            calls.add("delivery-complete");
        }

        private InitializationWorkflow completed(
                InitializationWorkflow current,
                String outcome,
                TenantAdministratorInitializationResult result) {
            return new InitializationWorkflow(
                    current.workflowId(), current.tenantId(), current.actorIdentityId(), current.idempotencyKey(),
                    current.requestFingerprint(), current.administratorEmail(), current.administratorDisplayName(),
                    current.identityRequestId(), current.consumeOperationId(), current.releaseOperationId(),
                    current.passwordDeliveryRequestId(), current.traceId(), outcome, result, current.createdAt());
        }
    }
}
