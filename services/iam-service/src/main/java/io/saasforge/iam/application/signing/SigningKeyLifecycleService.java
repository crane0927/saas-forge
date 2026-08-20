package io.saasforge.iam.application.signing;

import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 运维侧 Signing Key 发布、轮换、退役和紧急撤销用例。 */
public final class SigningKeyLifecycleService {
    private final SigningKeyRepository signingKeys;
    private final AccessTokenIssuanceRepository issuances;
    private final RevocationIndex revocationIndex;
    private final SigningKeyRevocationTransaction revocationTransaction;
    private final Clock clock;

    public SigningKeyLifecycleService(
            SigningKeyRepository signingKeys,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            SigningKeyRevocationTransaction revocationTransaction,
            Clock clock) {
        this.signingKeys = signingKeys;
        this.issuances = issuances;
        this.revocationIndex = revocationIndex;
        this.revocationTransaction = revocationTransaction;
        this.clock = clock;
    }

    public SigningKey publish(
            String kid, String keyVersionReference, String publicJwkModulus, String publicJwkExponent) {
        return signingKeys.savePublished(SigningKey.publish(
                kid, keyVersionReference, publicJwkModulus, publicJwkExponent, clock.instant()));
    }

    public SigningKey activate(UUID keyId) {
        return signingKeys.activate(keyId, clock.instant());
    }

    public SigningKey retire(UUID keyId) {
        return signingKeys.retire(keyId, clock.instant());
    }

    /**
     * Redis 必须先原子写入 kid 与所有已知未过期 jti；数据库失败只会造成额外拒绝，不能反向放行。
     * 撤销 ACTIVE key 时 replacementKeyId 必填，以保证数据库提交前后都恰好一个 ACTIVE key。
     */
    public SigningKey emergencyRevoke(UUID keyId, UUID replacementKeyId) {
        Instant now = clock.instant();
        SigningKey target = signingKeys.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Signing Key 不存在"));
        validateReplacement(target, replacementKeyId, now);
        List<AccessTokenIssuance> activeIssuances = issuances.findUnexpiredByKid(target.kid(), now);
        Instant rejectUntil = now.plus(target.maxIssuedTokenTtl()).plus(SigningKey.VALIDATION_CLOCK_SKEW);
        for (AccessTokenIssuance issuance : activeIssuances) {
            Instant issuanceRejectUntil = issuance.expiresAt().plus(SigningKey.VALIDATION_CLOCK_SKEW);
            if (issuanceRejectUntil.isAfter(rejectUntil)) {
                rejectUntil = issuanceRejectUntil;
            }
        }
        revocationIndex.revokeSigningKey(target.kid(), rejectUntil, activeIssuances, now);
        return revocationTransaction.commit(keyId, replacementKeyId, now);
    }

    private void validateReplacement(SigningKey target, UUID replacementKeyId, Instant now) {
        if (target.status() != SigningKeyStatus.ACTIVE) {
            if (replacementKeyId != null) {
                throw new IllegalArgumentException("只有撤销 ACTIVE Signing Key 才能指定替代 key");
            }
            return;
        }
        if (replacementKeyId == null || replacementKeyId.equals(target.id())) {
            throw new IllegalStateException("撤销 ACTIVE Signing Key 必须提供替代 key");
        }
        signingKeys.findById(replacementKeyId)
                .orElseThrow(() -> new IllegalArgumentException("替代 Signing Key 不存在"))
                .activate(now);
    }
}
