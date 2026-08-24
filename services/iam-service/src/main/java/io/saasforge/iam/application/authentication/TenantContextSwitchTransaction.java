package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class TenantContextSwitchTransaction {
    private final TenantContextSwitchRepository workflows;
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final RevocationIndex revocationIndex;
    private final OutboxEventRepository outboxEvents;
    private final TenantContextSwitchedEventFactory eventFactory;

    public TenantContextSwitchTransaction(
            TenantContextSwitchRepository workflows,
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            TenantContextSwitchedEventFactory eventFactory) {
        this.workflows = workflows;
        this.families = families;
        this.issuances = issuances;
        this.revocationIndex = revocationIndex;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
    }

    @Transactional
    public void rejectCurrent(UUID workflowId, Sha256Digest refreshTokenDigest, Instant rejectedAt) {
        RefreshTokenFamily family = families.findByTokenDigest(refreshTokenDigest)
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        revokeFamily(family.id(), rejectedAt, "MEMBERSHIP_AUTHORIZATION_LOST");
        workflows.complete(workflowId, TenantContextSwitchStatus.CURRENT_REJECTED, rejectedAt);
    }

    /** Redis 安全索引先于数据库成功；数据库回滚时保留 Redis 的额外拒绝。 */
    @Transactional
    public void switchContext(
            UUID workflowId,
            RefreshTokenFamily originalFamily,
            long expectedContextVersion,
            UUID targetMembershipId,
            UUID targetTenantId,
            Instant switchedAt,
            String traceId) {
        RefreshTokenFamily locked = families.lockById(originalFamily.id())
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        if (locked.contextVersion() != expectedContextVersion) {
            throw new IllegalStateException("Tenant Context Switch 的 Family Context 已变化");
        }
        List<AccessTokenIssuance> active = issuances.findUnexpiredByFamilyId(locked.id(), switchedAt);
        indexAccessTokens(active, switchedAt);
        RefreshTokenFamilyContextChange contextChange = families.switchTenantContext(
                locked.id(), expectedContextVersion, targetMembershipId, targetTenantId);
        if (contextChange.status() != RefreshTokenFamilyContextChange.Status.CHANGED) {
            throw new IllegalStateException("Tenant Context Switch 的 Family Context 已变化");
        }
        persistAccessTokenRevocations(active, switchedAt, "TENANT_CONTEXT_SWITCHED");
        workflows.markAwaitingRefresh(workflowId, expectedContextVersion, switchedAt);
        outboxEvents.append(eventFactory.create(
                originalFamily.id(), originalFamily.identityId(), originalFamily.membershipId(),
                targetMembershipId, targetTenantId, switchedAt, traceId));
    }

    @Transactional
    public void rejectPostSwitchRefresh(UUID familyId, long contextVersion, Instant rejectedAt) {
        revokeFamily(familyId, rejectedAt, "POST_SWITCH_MEMBERSHIP_AUTHORIZATION_LOST");
        workflows.completePostSwitchRefresh(familyId, contextVersion, false, rejectedAt);
    }

    @Transactional
    public void complete(UUID workflowId, TenantContextSwitchStatus status, Instant completedAt) {
        workflows.complete(workflowId, status, completedAt);
    }

    private void revokeFamily(UUID familyId, Instant at, String reason) {
        RefreshTokenFamily locked = families.lockById(familyId)
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        List<AccessTokenIssuance> active = issuances.findUnexpiredByFamilyId(locked.id(), at);
        revokeAccessTokens(active, at, reason);
        families.revokeById(locked.id(), at);
    }

    private void revokeAccessTokens(List<AccessTokenIssuance> active, Instant at, String reason) {
        indexAccessTokens(active, at);
        persistAccessTokenRevocations(active, at, reason);
    }

    private void indexAccessTokens(List<AccessTokenIssuance> active, Instant at) {
        for (AccessTokenIssuance issuance : active) {
            revocationIndex.revokeJti(issuance.jti(), issuance.expiresAt(), at);
        }
    }

    private void persistAccessTokenRevocations(List<AccessTokenIssuance> active, Instant at, String reason) {
        for (AccessTokenIssuance issuance : active) {
            issuances.revoke(issuance.jti(), at, reason);
        }
    }
}
