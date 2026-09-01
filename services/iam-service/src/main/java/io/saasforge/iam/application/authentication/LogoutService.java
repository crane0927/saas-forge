package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

public final class LogoutService {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final PresentedAccessTokenVerifier accessTokens;
    private final AccessTokenIssuanceRepository issuances;
    private final RefreshTokenIssuer refreshTokens;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final RevocationIndex revocationIndex;
    private final LogoutTransaction transaction;
    private final Clock clock;

    public LogoutService(
            PresentedAccessTokenVerifier accessTokens,
            AccessTokenIssuanceRepository issuances,
            RefreshTokenIssuer refreshTokens,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            RevocationIndex revocationIndex,
            LogoutTransaction transaction,
            Clock clock) {
        this.accessTokens = accessTokens;
        this.issuances = issuances;
        this.refreshTokens = refreshTokens;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.revocationIndex = revocationIndex;
        this.transaction = transaction;
        this.clock = clock;
    }

    public void logout(
            BrowserSessionSlot sessionSlot,
            String refreshToken,
            String authorizationHeader,
            String traceId) {
        Instant now = clock.instant();
        try {
            requireMatchingPurpose(sessionSlot, refreshToken);
            Optional<AccessTokenIssuance> issuance = accessTokens.verify(authorizationHeader)
                    .flatMap(token -> issuances.findByJti(token.jti())
                            .filter(stored -> stored.kid().equals(token.kid()))
                            .filter(stored -> stored.expiresAt().equals(token.expiresAt()))
                            .filter(stored -> stored.expiresAt().plus(CLOCK_SKEW).isAfter(now)));
            // Redis 成功必须先于数据库事务；数据库后续失败时额外拒绝是安全的。
            issuance.ifPresent(value -> revocationIndex.revokeJti(value.jti(), value.expiresAt(), now));
            transaction.commit(refreshDigest(refreshToken), issuance, now, traceId);
        } catch (DataAccessException exception) {
            throw new LogoutUnavailableException(exception);
        }
    }

    private void requireMatchingPurpose(BrowserSessionSlot sessionSlot, String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        io.saasforge.iam.domain.shared.Sha256Digest digest;
        try {
            digest = refreshTokens.digest(refreshToken);
        } catch (ContextSelectionSessionInvalidException invalidRefreshToken) {
            // 登出对无效或已清除 Cookie 保持幂等。
            return;
        }
        refreshTokenFamilies.findByTokenDigest(digest)
                .filter(family -> !sessionSlot.accepts(family.purpose()))
                .ifPresent(family -> {
                    throw new BrowserRequestRejectedException();
                });
    }

    private Optional<Sha256Digest> refreshDigest(String refreshToken) {
        if (refreshToken == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(refreshTokens.digest(refreshToken));
        } catch (RuntimeException invalidRefreshToken) {
            return Optional.empty();
        }
    }
}
