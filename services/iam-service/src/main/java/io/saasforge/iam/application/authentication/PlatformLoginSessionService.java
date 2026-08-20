package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PlatformLoginSessionService {
    private static final Duration REFRESH_IDLE_LIFETIME = Duration.ofMinutes(30);

    private final PlatformRoleAssignmentRepository platformRoles;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final AccessTokenIssuanceRepository accessTokenIssuances;
    private final OutboxEventRepository outboxEvents;
    private final SessionStartedEventFactory eventFactory;

    public PlatformLoginSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            AccessTokenIssuanceRepository accessTokenIssuances,
            OutboxEventRepository outboxEvents,
            SessionStartedEventFactory eventFactory) {
        this.platformRoles = platformRoles;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.accessTokenIssuances = accessTokenIssuances;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
    }

    /** Role 复核、Family、Issuance 与 Outbox 必须共享这一事务边界。 */
    @Transactional
    public long start(
            UUID identityId,
            IssuedAccessToken accessToken,
            RefreshTokenMaterial refreshToken,
            String traceId) {
        if (!platformRoles.hasActiveAssignment(identityId, accessToken.issuedAt())) {
            throw new AccessContextUnavailableException();
        }
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, accessToken.issuedAt()),
                refreshToken.digest(), accessToken.issuedAt());
        accessTokenIssuances.create(new AccessTokenIssuance(
                accessToken.jti(), family.id(), identityId, null, null, accessToken.kid(),
                accessToken.issuedAt(), accessToken.expiresAt()));
        outboxEvents.append(eventFactory.create(family, accessToken.issuedAt(), traceId));
        long absoluteRemaining = Duration.between(accessToken.issuedAt(), family.absoluteExpiresAt()).getSeconds();
        return Math.min(REFRESH_IDLE_LIFETIME.getSeconds(), absoluteRemaining);
    }
}
