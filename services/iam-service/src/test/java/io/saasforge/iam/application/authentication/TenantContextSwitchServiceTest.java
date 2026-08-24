package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchClaim;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantContextSwitchServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final String REFRESH_TOKEN = "A".repeat(43);
    private static final UUID FAMILY_ID = uuidV7(1);
    private static final UUID IDENTITY_ID = uuidV7(2);
    private static final UUID CURRENT_MEMBERSHIP_ID = uuidV7(3);
    private static final UUID CURRENT_TENANT_ID = uuidV7(4);
    private static final UUID TARGET_MEMBERSHIP_ID = uuidV7(5);
    private static final UUID TARGET_TENANT_ID = uuidV7(6);
    private static final UUID IDEMPOTENCY_KEY = uuidV7(7);
    private static final UUID WORKFLOW_ID = uuidV7(8);

    private RefreshTokenFamilyRepository families;
    private TenantContextSwitchRepository workflows;
    private MembershipValidation memberships;
    private TenantContextSwitchTransaction transaction;
    private RefreshTokenIssuer refreshTokens;
    private TenantContextSwitchService service;
    private RefreshTokenFamily family;

    @BeforeEach
    void setUp() {
        families = mock(RefreshTokenFamilyRepository.class);
        workflows = mock(TenantContextSwitchRepository.class);
        memberships = mock(MembershipValidation.class);
        transaction = mock(TenantContextSwitchTransaction.class);
        refreshTokens = new RefreshTokenIssuer(new SecureRandom());
        service = new TenantContextSwitchService(
                families, workflows, memberships, refreshTokens, transaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
        family = RefreshTokenFamily.start(
                        IDENTITY_ID, RefreshTokenFamilyPurpose.USER_TENANT,
                        CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID, NOW.minusSeconds(10))
                .identifiedBy(FAMILY_ID);
        when(families.findUsableByTokenDigest(any(), eq(NOW))).thenReturn(Optional.of(family));
    }

    @Test
    void persistsBeforeSequentialValidationAndCompletesStableNoOp() {
        TenantContextSwitchWorkflow workflow = workflow(CURRENT_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(eq(FAMILY_ID), eq(0L), eq(IDEMPOTENCY_KEY),
                eq(CURRENT_MEMBERSHIP_ID), any(), eq(NOW)))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.CREATED, workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));

        assertDoesNotThrow(() -> service.switchContext(
                IDEMPOTENCY_KEY, REFRESH_TOKEN, CURRENT_MEMBERSHIP_ID));

        verify(workflows).claim(eq(FAMILY_ID), eq(0L), eq(IDEMPOTENCY_KEY),
                eq(CURRENT_MEMBERSHIP_ID), any(), eq(NOW));
        verify(memberships, times(2)).validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID);
        verify(transaction).complete(WORKFLOW_ID, TenantContextSwitchStatus.NO_OP, NOW);
        verify(families, never()).revokeForAuthorizationLoss(any(), any());
    }

    @Test
    void currentDenialTerminatesFamilyAndDoesNotInspectTarget() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.CREATED, workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        assertThrows(TenantContextSwitchAccessRejectedException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(transaction).rejectCurrent(eq(WORKFLOW_ID), any(), eq(NOW));
        verify(memberships, never()).validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID);
    }

    @Test
    void targetDenialPreservesCurrentSessionAndUsesReasonFreeFailure() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.CREATED, workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));
        when(memberships.validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID)).thenReturn(Optional.empty());

        assertThrows(TenantContextSwitchAccessRejectedException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        verify(transaction).complete(WORKFLOW_ID, TenantContextSwitchStatus.TARGET_REJECTED, NOW);
        verify(transaction, never()).rejectCurrent(any(), any(), any());
    }

    @Test
    void validatedRealChangeRemainsRecoverableAndReturnsPending() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.CREATED, workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(CURRENT_MEMBERSHIP_ID, CURRENT_TENANT_ID)));
        when(memberships.validate(IDENTITY_ID, TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(new ValidatedMembership(TARGET_MEMBERSHIP_ID, TARGET_TENANT_ID)));

        assertThrows(TenantContextSwitchPendingException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        verify(transaction, never()).complete(any(), any(), any());
        verify(transaction, never()).rejectCurrent(any(), any(), any());
    }

    @Test
    void tenantAccessFailureLeavesSessionAndWorkflowUnchanged() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.CREATED, workflow));
        when(memberships.validate(IDENTITY_ID, CURRENT_MEMBERSHIP_ID))
                .thenThrow(new TenantAccessUnavailableException(new IllegalStateException("outage")));

        assertThrows(TenantAccessUnavailableException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        verify(transaction, never()).complete(any(), any(), any());
        verify(transaction, never()).rejectCurrent(any(), any(), any());
    }

    @Test
    void wrongPurposeAndInvalidCookieFailBeforeTenantAccess() {
        RefreshTokenFamily platformFamily = RefreshTokenFamily.start(
                        IDENTITY_ID, RefreshTokenFamilyPurpose.USER_PLATFORM, null, null, NOW.minusSeconds(10))
                .identifiedBy(FAMILY_ID);
        when(families.findUsableByTokenDigest(any(), eq(NOW))).thenReturn(Optional.of(platformFamily));

        assertThrows(TenantContextSwitchSessionInvalidException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        assertThrows(TenantContextSwitchSessionInvalidException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, "invalid", TARGET_MEMBERSHIP_ID));
        verify(memberships, never()).validate(any(), any());
        verify(workflows, never()).claim(any(), any(Long.class), any(), any(), any(), any());
    }

    @Test
    void idempotencyAndFamilyConflictsDoNotCallTenantAccess() {
        TenantContextSwitchWorkflow workflow = workflow(TARGET_MEMBERSHIP_ID, TenantContextSwitchStatus.PENDING);
        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.TARGET_CONFLICT, workflow));
        assertThrows(TenantContextSwitchConflictException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));

        when(workflows.claim(any(), any(Long.class), any(), any(), any(), any()))
                .thenReturn(new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.FAMILY_IN_PROGRESS, workflow));
        assertThrows(TenantContextSwitchConflictException.class,
                () -> service.switchContext(IDEMPOTENCY_KEY, REFRESH_TOKEN, TARGET_MEMBERSHIP_ID));
        verify(memberships, never()).validate(any(), any());
    }

    private static TenantContextSwitchWorkflow workflow(
            UUID targetMembershipId, TenantContextSwitchStatus status) {
        Instant completedAt = status == TenantContextSwitchStatus.PENDING ? null : NOW;
        return new TenantContextSwitchWorkflow(
                WORKFLOW_ID, FAMILY_ID, IDEMPOTENCY_KEY, targetMembershipId,
                Sha256Digest.of(new byte[32]), 0, status, NOW, completedAt);
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString("0198c9d5-0f25-7000-8000-" + String.format("%012d", suffix));
    }
}
