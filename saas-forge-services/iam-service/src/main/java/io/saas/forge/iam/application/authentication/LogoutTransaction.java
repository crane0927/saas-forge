package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshTokenConsumption;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class LogoutTransaction {
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final OutboxEventRepository outboxEvents;
    private final SessionRevokedEventFactory eventFactory;

    public LogoutTransaction(
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            OutboxEventRepository outboxEvents,
            SessionRevokedEventFactory eventFactory) {
        this.families = families;
        this.issuances = issuances;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
    }

    /** Family 撤销、jti 持久事实和 session.revoked Outbox 必须原子提交。 */
    @Transactional
    public void commit(
            Optional<Sha256Digest> refreshDigest,
            Optional<AccessTokenIssuance> issuance,
            Instant revokedAt,
            String traceId) {
        RefreshTokenConsumption familyResult = refreshDigest
                .map(digest -> families.logout(digest, revokedAt))
                .orElseGet(() -> new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null));
        boolean accessTokenRevoked = issuance
                .map(value -> issuances.revoke(value.jti(), revokedAt, "CURRENT_SESSION_LOGOUT"))
                .orElse(false);
        if (familyResult.status() == RefreshTokenConsumption.Status.CONSUMED) {
            outboxEvents.append(eventFactory.create(
                    familyResult.family(), accessTokenRevoked, revokedAt, traceId));
        }
    }
}
