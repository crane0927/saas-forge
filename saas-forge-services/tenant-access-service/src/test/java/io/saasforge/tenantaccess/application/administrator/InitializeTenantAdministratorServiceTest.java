package io.saasforge.tenantaccess.application.administrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private boolean identityTemporarilyUnavailable;
    private boolean quotaTemporarilyUnavailable;
    private boolean releaseTemporarilyUnavailable;
    private boolean activationFails;
    private boolean deliveryFails;
    private InitializeTenantAdministratorService service;

    @BeforeEach
    void setUp() {
        disposition = IdentityCredentialDisposition.SETUP_ALLOWED;
        quotaFailure = null;
        identityTemporarilyUnavailable = false;
        quotaTemporarilyUnavailable = false;
        releaseTemporarilyUnavailable = false;
        activationFails = false;
        deliveryFails = false;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new InitializeTenantAdministratorService(
                workflows,
                (requestId, email, displayName) -> {
                    calls.add("identity:" + requestId);
                    if (identityTemporarilyUnavailable) {
                        throw new RemoteWorkflowUnavailableException(new IllegalStateException("IAM unavailable"));
                    }
                    return new IdentityProvisioningGateway.Result(IDENTITY, disposition);
                },
                new InitializationQuotaGateway() {
                    @Override
                    public void consume(UUID tenantId, UUID operationId) {
                        calls.add("quota:" + operationId);
                        if (quotaFailure != null) {
                            throw quotaFailure;
                        }
                        if (quotaTemporarilyUnavailable) {
                            throw new RemoteWorkflowUnavailableException(
                                    new IllegalStateException("Entitlement unavailable"));
                        }
                    }

                    @Override
                    public void release(UUID tenantId, UUID operationId) {
                        calls.add("release:" + operationId);
                        if (releaseTemporarilyUnavailable) {
                            throw new RemoteWorkflowUnavailableException(
                                    new IllegalStateException("Entitlement unavailable"));
                        }
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
        assertEquals(1, calls.stream().filter(call -> call.startsWith("identity:")).count());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("quota:")).count());
        assertEquals(1, calls.stream().filter("activate"::equals).count());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("delivery:")).count());
    }

    @Test
    void passwordReadySkipsDeliveryAndRecoveryStopsBeforeQuota() {
        disposition = IdentityCredentialDisposition.PASSWORD_READY;
        initialize();
        assertEquals(0, calls.stream().filter(call -> call.startsWith("delivery:")).count());

        calls.clear();
        workflows.workflow = null;
        disposition = IdentityCredentialDisposition.RECOVERY_REQUIRED;
        TenantAdministratorInitializationException exception = assertThrows(
                TenantAdministratorInitializationException.class, this::initialize);
        assertEquals("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", exception.code());
        assertEquals(0, calls.stream().filter(call -> call.startsWith("quota:")).count());
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
        assertEquals(1, calls.stream().filter("activate"::equals).count());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("delivery:")).count());

        deliveryFails = false;
        service.recoverNext();
        assertEquals(2, calls.stream().filter(call -> call.startsWith("delivery:")).count());
        assertEquals(0, calls.stream().filter(call -> call.startsWith("release:")).count());
        assertFalse(workflows.workflow.passwordDeliveryPending());
    }

    @Test
    void workerContinuesSameStableWorkflowAfterIamAndEntitlementRecover() {
        identityTemporarilyUnavailable = true;
        assertThrows(RemoteWorkflowUnavailableException.class, this::initialize);
        UUID identityRequestId = workflows.workflow.identityRequestId();
        UUID consumeOperationId = workflows.workflow.consumeOperationId();
        assertEquals(InitializationWorkflowState.PREPARED, workflows.workflow.state());

        identityTemporarilyUnavailable = false;
        quotaTemporarilyUnavailable = true;
        service.recoverNext();
        assertEquals(InitializationWorkflowState.IDENTITY_READY, workflows.workflow.state());

        quotaTemporarilyUnavailable = false;
        service.recoverNext();
        assertEquals("SUCCESS", workflows.workflow.outcomeCode());
        assertEquals(2, calls.stream().filter(call -> call.equals("identity:" + identityRequestId)).count());
        assertEquals(2, calls.stream().filter(call -> call.equals("quota:" + consumeOperationId)).count());
    }

    @Test
    void compensationReplayUsesStableReleaseOperationAndRequiresNewAttempt() {
        activationFails = true;
        releaseTemporarilyUnavailable = true;

        TenantAdministratorInitializationException compensating = assertThrows(
                TenantAdministratorInitializationException.class, this::initialize);
        assertEquals("TENANT_ADMIN_INITIALIZATION_COMPENSATING", compensating.code());
        UUID releaseOperationId = workflows.workflow.releaseOperationId();
        assertEquals(1, calls.stream().filter(call -> call.equals("release:" + releaseOperationId)).count());

        releaseTemporarilyUnavailable = false;
        service.recoverNext();
        assertEquals(2, calls.stream().filter(call -> call.equals("release:" + releaseOperationId)).count());
        TenantAdministratorInitializationException retryRequired = assertThrows(
                TenantAdministratorInitializationException.class, this::initialize);
        assertEquals("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", retryRequired.code());
    }

    @Test
    void exhaustedAutomaticRecoveryRemainsDiagnosticAndExplicitlyRecoverable() {
        identityTemporarilyUnavailable = true;
        assertThrows(RemoteWorkflowUnavailableException.class, this::initialize);
        for (int attempt = 2; attempt <= 10; attempt++) {
            assertTrue(service.recoverNext());
        }

        assertTrue(workflows.recoveryExhausted);
        assertEquals(10, workflows.workflow.attemptCount());
        assertEquals(InitializationWorkflowState.PREPARED, workflows.workflow.state());
        assertNull(workflows.workflow.outcomeCode());
        assertEquals("RemoteWorkflowUnavailableException", workflows.lastFailure);
        assertFalse(service.recoverNext());

        identityTemporarilyUnavailable = false;
        assertEquals(TenantStatus.ACTIVE, initialize().status());
        assertFalse(workflows.recoveryExhausted);
    }

    @Test
    void exhaustedPasswordDeliveryKeepsStableSuccessAndCanBeExplicitlyRecovered() {
        deliveryFails = true;
        assertEquals(TenantStatus.ACTIVE, initialize().status());
        for (int attempt = 2; attempt <= 10; attempt++) {
            assertTrue(service.recoverNext());
        }

        assertTrue(workflows.recoveryExhausted);
        assertEquals("SUCCESS", workflows.workflow.outcomeCode());
        assertTrue(workflows.workflow.passwordDeliveryPending());
        assertFalse(service.recoverNext());

        deliveryFails = false;
        assertEquals(TenantStatus.ACTIVE, initialize().status());
        assertFalse(workflows.recoveryExhausted);
        assertFalse(workflows.workflow.passwordDeliveryPending());
        assertEquals(0, calls.stream().filter(call -> call.startsWith("release:")).count());
    }

    private TenantAdministratorInitializationResult initialize() {
        return service.initialize(ACTOR, KEY, TENANT, "admin@example.com", "Admin", null);
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private final class InMemoryWorkflows implements TenantAdministratorInitializationRepository {
        private InitializationWorkflow workflow;
        private boolean recoveryExhausted;
        private String lastFailure;

        @Override
        public InitializationWorkflow prepare(InitializationWorkflow candidate, Instant now) {
            calls.add("prepare");
            if (workflow == null) {
                workflow = candidate;
            }
            return workflow;
        }

        @Override
        public Optional<InitializationWorkflow> claim(
                UUID workflowId, String claimant, Instant now, Instant claimedUntil) {
            calls.add("claim");
            if (workflow.leaseOwner() != null) {
                return Optional.empty();
            }
            recoveryExhausted = false;
            workflow = copy(workflow, workflow.state(), workflow.outcomeCode(), workflow.result(),
                    workflow.administratorIdentityId(), workflow.credentialDisposition(),
                    workflow.passwordDeliveryPending(), workflow.attemptCount() + 1, claimant, claimedUntil);
            return Optional.of(workflow);
        }

        @Override
        public Optional<InitializationWorkflow> claimNext(String claimant, Instant now, Instant claimedUntil) {
            return workflow == null || recoveryExhausted
                    ? Optional.empty() : claim(workflow.workflowId(), claimant, now, claimedUntil);
        }

        @Override
        public InitializationWorkflow completeIdentity(
                InitializationWorkflow current,
                UUID administratorIdentityId,
                IdentityCredentialDisposition credentialDisposition,
                Instant completedAt) {
            workflow = copy(current, InitializationWorkflowState.IDENTITY_READY, null, null,
                    administratorIdentityId, credentialDisposition, false,
                    current.attemptCount(), current.leaseOwner(), current.leaseUntil());
            return workflow;
        }

        @Override
        public InitializationWorkflow completeQuotaConsumption(InitializationWorkflow current, Instant completedAt) {
            return transition(current, InitializationWorkflowState.QUOTA_CONSUMED);
        }

        @Override
        public InitializationWorkflow beginActivation(InitializationWorkflow current, Instant startedAt) {
            return transition(current, InitializationWorkflowState.ACTIVATING);
        }

        @Override
        public InitializationWorkflow beginCompensation(InitializationWorkflow current, Instant startedAt) {
            return transition(current, InitializationWorkflowState.COMPENSATING);
        }

        @Override
        public void scheduleRetry(InitializationWorkflow current, Instant retryAt, String failureSummary) {
            lastFailure = failureSummary;
            workflow = copy(workflow, workflow.state(), workflow.outcomeCode(), workflow.result(),
                    workflow.administratorIdentityId(), workflow.credentialDisposition(),
                    workflow.passwordDeliveryPending(), workflow.attemptCount(), null, null);
        }

        @Override
        public void exhaustRecovery(InitializationWorkflow current, Instant exhaustedAt, String failureSummary) {
            recoveryExhausted = true;
            lastFailure = failureSummary;
            workflow = copy(workflow, workflow.state(), workflow.outcomeCode(), workflow.result(),
                    workflow.administratorIdentityId(), workflow.credentialDisposition(),
                    workflow.passwordDeliveryPending(), workflow.attemptCount(), null, null);
        }

        @Override
        public void completeCompensation(InitializationWorkflow current, Instant completedAt) {
            workflow = copy(current, InitializationWorkflowState.FAILED,
                    "TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", null,
                    current.administratorIdentityId(), current.credentialDisposition(), false,
                    current.attemptCount(), null, null);
        }

        @Override
        public void completeFailure(InitializationWorkflow current, String outcomeCode, Instant completedAt) {
            calls.add("failure");
            workflow = copy(current, InitializationWorkflowState.FAILED, outcomeCode, null,
                    current.administratorIdentityId(), current.credentialDisposition(), false,
                    current.attemptCount(), null, null);
        }

        @Override
        public TenantAdministratorInitializationResult activate(
                InitializationWorkflow current,
                UUID administratorIdentityId,
                IdentityCredentialDisposition credentialDisposition,
                Instant activatedAt) {
            calls.add("activate");
            if (activationFails) {
                throw new IllegalStateException("local activation commit failed");
            }
            TenantAdministratorInitializationResult result = new TenantAdministratorInitializationResult(
                    TENANT, "Acme", TenantStatus.ACTIVE, NOW.plusSeconds(3600), NOW.minusSeconds(60), NOW);
            workflow = copy(current, InitializationWorkflowState.SUCCEEDED, "SUCCESS", result,
                    administratorIdentityId, credentialDisposition,
                    credentialDisposition == IdentityCredentialDisposition.SETUP_ALLOWED,
                    current.attemptCount(), credentialDisposition == IdentityCredentialDisposition.SETUP_ALLOWED
                            ? current.leaseOwner() : null,
                    credentialDisposition == IdentityCredentialDisposition.SETUP_ALLOWED
                            ? current.leaseUntil() : null);
            return result;
        }

        @Override
        public void completePasswordDelivery(InitializationWorkflow current, Instant completedAt) {
            calls.add("delivery-complete");
            workflow = copy(workflow, InitializationWorkflowState.SUCCEEDED, "SUCCESS", workflow.result(),
                    workflow.administratorIdentityId(), workflow.credentialDisposition(), false,
                    workflow.attemptCount(), null, null);
        }

        private InitializationWorkflow transition(
                InitializationWorkflow current, InitializationWorkflowState state) {
            workflow = copy(current, state, current.outcomeCode(), current.result(),
                    current.administratorIdentityId(), current.credentialDisposition(),
                    current.passwordDeliveryPending(), current.attemptCount(),
                    current.leaseOwner(), current.leaseUntil());
            return workflow;
        }

        private InitializationWorkflow copy(
                InitializationWorkflow current,
                InitializationWorkflowState state,
                String outcome,
                TenantAdministratorInitializationResult result,
                UUID administratorIdentityId,
                IdentityCredentialDisposition credentialDisposition,
                boolean deliveryPending,
                int attemptCount,
                String leaseOwner,
                Instant leaseUntil) {
            return new InitializationWorkflow(
                    current.workflowId(), current.tenantId(), current.actorIdentityId(), current.idempotencyKey(),
                    current.requestFingerprint(), current.administratorEmail(), current.administratorDisplayName(),
                    current.identityRequestId(), current.consumeOperationId(), current.releaseOperationId(),
                    current.passwordDeliveryRequestId(), current.traceId(), outcome, result, current.createdAt(),
                    state, administratorIdentityId, credentialDisposition, deliveryPending, attemptCount,
                    current.nextAttemptAt(), leaseOwner, leaseUntil,
                    recoveryExhausted ? NOW : null, lastFailure);
        }
    }
}
