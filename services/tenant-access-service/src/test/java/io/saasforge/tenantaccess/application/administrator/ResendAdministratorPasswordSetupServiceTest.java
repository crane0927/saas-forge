package io.saasforge.tenantaccess.application.administrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResendAdministratorPasswordSetupServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");
    private static final UUID ACTOR = uuidV7(1);
    private static final UUID KEY = uuidV7(2);
    private static final UUID TENANT = uuidV7(3);
    private static final UUID IDENTITY = uuidV7(4);

    private final List<String> calls = new ArrayList<>();
    private final InMemoryWorkflows workflows = new InMemoryWorkflows();
    private boolean unavailable;
    private boolean recoveryRequired;
    private ResendAdministratorPasswordSetupService service;

    @BeforeEach
    void setUp() {
        unavailable = false;
        recoveryRequired = false;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ResendAdministratorPasswordSetupService(
                workflows,
                (requestId, identityId) -> {
                    calls.add("delivery:" + requestId + ":" + identityId);
                    if (recoveryRequired) {
                        throw new IdentityCredentialRecoveryRequiredException();
                    }
                    if (unavailable) {
                        throw new RemoteWorkflowUnavailableException(new IllegalStateException("SMTP uncertain"));
                    }
                },
                new UuidV7Generator(clock, new SecureRandom()),
                clock,
                new InitializationRecoveryPolicy(
                        Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 10),
                "test-worker");
    }

    @Test
    void persistsStableRequestBeforeDeliveryAndReplaysCompletedResult() {
        service.resend(ACTOR, KEY, TENANT, null);
        UUID requestId = workflows.workflow.deliveryRequestId();

        service.resend(ACTOR, KEY, TENANT, null);

        assertEquals("SUCCESS", workflows.workflow.outcomeCode());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("delivery:")).count());
        assertEquals("delivery:" + requestId + ":" + IDENTITY,
                calls.stream().filter(call -> call.startsWith("delivery:")).findFirst().orElseThrow());
    }

    @Test
    void uncertainDeliveryReturnsPendingAndWorkerRetriesSameRequestId() {
        unavailable = true;
        AdministratorPasswordSetupException pending = assertThrows(
                AdministratorPasswordSetupException.class,
                () -> service.resend(ACTOR, KEY, TENANT, null));
        UUID requestId = workflows.workflow.deliveryRequestId();

        assertEquals("PASSWORD_SETUP_DELIVERY_PENDING", pending.code());
        assertEquals(1, pending.retryAfterSeconds());
        assertFalse(workflows.workflow.completed());
        assertEquals("RemoteWorkflowUnavailableException", workflows.workflow.lastFailure());
        assertFalse(workflows.workflow.lastFailure().contains("SMTP"));

        unavailable = false;
        assertTrue(service.recoverNext());
        assertEquals("SUCCESS", workflows.workflow.outcomeCode());
        assertEquals(2, calls.stream().filter(call -> call.contains(requestId.toString())).count());
    }

    @Test
    void credentialRecoveryConflictIsStableAndNeverRetried() {
        recoveryRequired = true;
        AdministratorPasswordSetupException first = assertThrows(
                AdministratorPasswordSetupException.class,
                () -> service.resend(ACTOR, KEY, TENANT, null));
        AdministratorPasswordSetupException replay = assertThrows(
                AdministratorPasswordSetupException.class,
                () -> service.resend(ACTOR, KEY, TENANT, null));

        assertEquals("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", first.code());
        assertEquals(first.code(), replay.code());
        assertEquals(1, calls.stream().filter(call -> call.startsWith("delivery:")).count());
        assertFalse(service.recoverNext());
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private final class InMemoryWorkflows implements AdministratorPasswordSetupRepository {
        private AdministratorPasswordSetupWorkflow workflow;

        @Override
        public AdministratorPasswordSetupWorkflow prepare(
                AdministratorPasswordSetupWorkflow candidate, Instant now) {
            calls.add("prepare");
            if (workflow == null) {
                workflow = copy(candidate, IDENTITY, null, 0, null, null, null, null);
            }
            return workflow;
        }

        @Override
        public Optional<AdministratorPasswordSetupWorkflow> claim(
                UUID workflowId, String claimant, Instant now, Instant claimedUntil) {
            if (workflow.completed() || workflow.leaseOwner() != null) {
                return Optional.empty();
            }
            workflow = copy(workflow, IDENTITY, null, workflow.attemptCount() + 1,
                    claimant, claimedUntil, null, workflow.lastFailure());
            return Optional.of(workflow);
        }

        @Override
        public Optional<AdministratorPasswordSetupWorkflow> claimNext(
                String claimant, Instant now, Instant claimedUntil) {
            return workflow == null || workflow.completed() || workflow.recoveryExhaustedAt() != null
                    ? Optional.empty() : claim(workflow.workflowId(), claimant, now, claimedUntil);
        }

        @Override
        public void scheduleRetry(
                AdministratorPasswordSetupWorkflow current, Instant retryAt, String failureSummary) {
            workflow = copy(current, IDENTITY, null, current.attemptCount(),
                    null, null, null, failureSummary);
        }

        @Override
        public void exhaustRecovery(
                AdministratorPasswordSetupWorkflow current, Instant exhaustedAt, String failureSummary) {
            workflow = copy(current, IDENTITY, null, current.attemptCount(),
                    null, null, exhaustedAt, failureSummary);
        }

        @Override
        public void completeSuccess(AdministratorPasswordSetupWorkflow current, Instant completedAt) {
            workflow = copy(current, IDENTITY, "SUCCESS", current.attemptCount(),
                    null, null, null, null);
        }

        @Override
        public void completeRecoveryRequired(
                AdministratorPasswordSetupWorkflow current, Instant completedAt) {
            workflow = copy(current, IDENTITY, "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED",
                    current.attemptCount(), null, null, null, null);
        }

        private AdministratorPasswordSetupWorkflow copy(
                AdministratorPasswordSetupWorkflow source,
                UUID identityId,
                String outcomeCode,
                int attemptCount,
                String leaseOwner,
                Instant leaseUntil,
                Instant recoveryExhaustedAt,
                String lastFailure) {
            return new AdministratorPasswordSetupWorkflow(
                    source.workflowId(), source.tenantId(), source.actorIdentityId(), source.idempotencyKey(),
                    source.requestFingerprint(), identityId, source.deliveryRequestId(), source.traceId(),
                    outcomeCode, source.createdAt(), attemptCount, source.nextAttemptAt(), leaseOwner, leaseUntil,
                    recoveryExhaustedAt, lastFailure);
        }
    }
}
