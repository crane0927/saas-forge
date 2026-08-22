package io.saasforge.iam.domain.identity;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/** 一次性 Password Setup Challenge 及其稳定成功兑换事实。 */
public record PasswordSetupChallenge(
        UUID id,
        UUID identityId,
        Sha256Digest tokenDigest,
        Instant issuedAt,
        Instant expiresAt,
        Instant invalidatedAt,
        Instant consumedAt,
        UUID idempotencyKey,
        Sha256Digest requestFingerprint,
        UUID credentialId,
        Integer completedStatus) {

    public PasswordSetupChallenge {
        if (id == null || id.version() != 7 || identityId == null || tokenDigest == null
                || issuedAt == null || expiresAt == null || !expiresAt.equals(issuedAt.plusSeconds(86_400))) {
            throw new IllegalArgumentException("Password Setup Challenge 必要字段不合法");
        }
        boolean completed = consumedAt != null;
        if (completed != (idempotencyKey != null && requestFingerprint != null
                && credentialId != null && Integer.valueOf(204).equals(completedStatus))) {
            throw new IllegalArgumentException("Password Setup Challenge 完成事实不完整");
        }
        if (idempotencyKey != null && idempotencyKey.version() != 7) {
            throw new IllegalArgumentException("Password Setup 幂等键必须是 UUIDv7");
        }
        if (invalidatedAt != null && consumedAt != null) {
            throw new IllegalArgumentException("Password Setup Challenge 不能同时失效和消费");
        }
        if ((invalidatedAt != null && invalidatedAt.isBefore(issuedAt))
                || (consumedAt != null && consumedAt.isBefore(issuedAt))) {
            throw new IllegalArgumentException("Password Setup Challenge 状态时间不能早于签发时间");
        }
    }

    public boolean canRedeemAt(Instant at) {
        return invalidatedAt == null && consumedAt == null && expiresAt.isAfter(at);
    }

    public boolean isSuccessfulReplay(UUID key) {
        return consumedAt != null && idempotencyKey.equals(key) && completedStatus == 204;
    }
}
