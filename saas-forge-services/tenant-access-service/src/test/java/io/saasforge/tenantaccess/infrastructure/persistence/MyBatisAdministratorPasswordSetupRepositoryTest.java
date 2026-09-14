package io.saasforge.tenantaccess.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupException;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupWorkflow;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.AdministratorPasswordSetupMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.AdministratorPasswordSetupWorkflowRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MyBatisAdministratorPasswordSetupRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final UUID WORKFLOW_ID = id(41);
    private static final UUID TENANT_ID = id(42);
    private static final UUID ACTOR_ID = id(43);
    private static final UUID KEY = id(44);
    private static final UUID IDENTITY_ID = id(45);
    private static final UUID DELIVERY_ID = id(46);

    private AdministratorPasswordSetupMapper mapper;
    private MyBatisAdministratorPasswordSetupRepository repository;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(AdministratorPasswordSetupMapper.class);
        repository = new MyBatisAdministratorPasswordSetupRepository(mapper);
    }

    @Test
    void preparesNewWorkflowAndReplaysOnlyMatchingFingerprint() {
        AdministratorPasswordSetupWorkflow candidate = workflow(null, 0, null, null, "fingerprint");
        when(mapper.lockTenant(TENANT_ID)).thenReturn(new TenantRow(
                TENANT_ID, "Tenant", "ACTIVE", null, utc(NOW), utc(NOW)));
        when(mapper.findInitialAdministratorIdentityId(TENANT_ID)).thenReturn(IDENTITY_ID);
        when(mapper.insertWorkflow(any())).thenReturn(1);

        var prepared = repository.prepare(candidate, NOW);
        assertEquals(IDENTITY_ID, prepared.administratorIdentityId());
        assertEquals(DELIVERY_ID, prepared.deliveryRequestId());

        when(mapper.insertWorkflow(any())).thenReturn(0);
        when(mapper.findWorkflow(ACTOR_ID, KEY)).thenReturn(row("fingerprint", null, 0, null, null));
        assertEquals(WORKFLOW_ID, repository.prepare(candidate, NOW).workflowId());
        when(mapper.findWorkflow(ACTOR_ID, KEY)).thenReturn(row("different", null, 0, null, null));
        assertThrows(IdempotencyKeyReusedException.class, () -> repository.prepare(candidate, NOW));
        when(mapper.findWorkflow(ACTOR_ID, KEY)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> repository.prepare(candidate, NOW));
    }

    @Test
    void rejectsMissingTenantOrInitialAdministrator() {
        AdministratorPasswordSetupWorkflow candidate = workflow(null, 0, null, null, "fingerprint");
        AdministratorPasswordSetupException missingTenant = assertThrows(
                AdministratorPasswordSetupException.class, () -> repository.prepare(candidate, NOW));
        assertEquals("TENANT_NOT_FOUND", missingTenant.code());

        when(mapper.lockTenant(TENANT_ID)).thenReturn(new TenantRow(
                TENANT_ID, "Tenant", "ACTIVE", null, utc(NOW), utc(NOW)));
        AdministratorPasswordSetupException missingAdministrator = assertThrows(
                AdministratorPasswordSetupException.class, () -> repository.prepare(candidate, NOW));
        assertEquals("TENANT_INITIAL_ADMINISTRATOR_NOT_FOUND", missingAdministrator.code());
    }

    @Test
    void claimsAndRejectsMissingClaimedWorkflow() {
        assertTrue(repository.claim(WORKFLOW_ID, "worker", NOW, NOW.plusSeconds(30)).isEmpty());
        assertTrue(repository.claimNext("worker", NOW, NOW.plusSeconds(30)).isEmpty());

        when(mapper.claimWorkflow(eq(WORKFLOW_ID), eq("worker"), any(), any())).thenReturn(WORKFLOW_ID);
        when(mapper.lockWorkflow(WORKFLOW_ID)).thenReturn(row("fingerprint", null, 1, "worker", null));
        assertEquals("worker", repository.claim(WORKFLOW_ID, "worker", NOW, NOW.plusSeconds(30))
                .orElseThrow().leaseOwner());
        when(mapper.claimNextWorkflow(eq("worker"), any(), any())).thenReturn(WORKFLOW_ID);
        assertTrue(repository.claimNext("worker", NOW, NOW.plusSeconds(30)).isPresent());

        when(mapper.lockWorkflow(WORKFLOW_ID)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> repository.claim(WORKFLOW_ID, "worker", NOW, NOW.plusSeconds(30)));
    }

    @Test
    void persistsEveryLeaseOutcomeAndRejectsStaleLease() {
        AdministratorPasswordSetupWorkflow claimed = workflow(null, 1, "worker", null, "fingerprint");
        when(mapper.scheduleRetry(eq(WORKFLOW_ID), eq("worker"), eq(1), any(), eq("failure")))
                .thenReturn(1, 0);
        repository.scheduleRetry(claimed, NOW.plusSeconds(1), "failure");
        assertThrows(IllegalStateException.class,
                () -> repository.scheduleRetry(claimed, NOW.plusSeconds(1), "failure"));

        when(mapper.exhaustRecovery(eq(WORKFLOW_ID), eq("worker"), eq(1), any(), eq("failure")))
                .thenReturn(1, 0);
        repository.exhaustRecovery(claimed, NOW, "failure");
        assertThrows(IllegalStateException.class, () -> repository.exhaustRecovery(claimed, NOW, "failure"));

        when(mapper.completeOutcome(eq(WORKFLOW_ID), eq("worker"), eq(1), eq("SUCCESS"), any(), any()))
                .thenReturn(1, 0);
        repository.completeSuccess(claimed, NOW);
        assertThrows(IllegalStateException.class, () -> repository.completeSuccess(claimed, NOW));

        when(mapper.completeOutcome(eq(WORKFLOW_ID), eq("worker"), eq(1),
                eq("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED"), any(), any())).thenReturn(1, 0);
        repository.completeRecoveryRequired(claimed, NOW);
        assertThrows(IllegalStateException.class, () -> repository.completeRecoveryRequired(claimed, NOW));
    }

    private static AdministratorPasswordSetupWorkflow workflow(
            String outcome, int attempts, String leaseOwner, Instant exhaustedAt, String fingerprint) {
        return new AdministratorPasswordSetupWorkflow(
                WORKFLOW_ID, TENANT_ID, ACTOR_ID, KEY, fingerprint, IDENTITY_ID, DELIVERY_ID, null,
                outcome, NOW, attempts, NOW, leaseOwner,
                leaseOwner == null ? null : NOW.plusSeconds(30), exhaustedAt, null);
    }

    private static AdministratorPasswordSetupWorkflowRow row(
            String fingerprint, String outcome, int attempts, String leaseOwner, Instant exhaustedAt) {
        return new AdministratorPasswordSetupWorkflowRow(
                WORKFLOW_ID, TENANT_ID, ACTOR_ID, KEY, fingerprint, IDENTITY_ID, DELIVERY_ID, null,
                outcome, utc(NOW), null, null, attempts, utc(NOW), leaseOwner,
                leaseOwner == null ? null : utc(NOW.plusSeconds(30)), utc(exhaustedAt), null);
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static UUID id(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }
}
