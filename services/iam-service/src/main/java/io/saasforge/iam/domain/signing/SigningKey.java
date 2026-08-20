package io.saasforge.iam.domain.signing;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** KMS/HSM 私钥的公开元数据及其 JWKS 生命周期。 */
public final class SigningKey {

    private static final Duration PUBLICATION_WINDOW = Duration.ofMinutes(5);
    private static final Duration RETIREMENT_GRACE_PERIOD = Duration.ofMinutes(30);

    private final UUID id;
    private final String kid;
    private final String keyVersionReference;
    private final String publicJwkModulus;
    private final String publicJwkExponent;
    private final SigningKeyStatus status;
    private final Instant publishedAt;
    private final Instant activatedAt;
    private final Instant retireAfter;
    private final Instant retiredAt;
    private final Instant revokedAt;

    private SigningKey(
            UUID id,
            String kid,
            String keyVersionReference,
            String publicJwkModulus,
            String publicJwkExponent,
            SigningKeyStatus status,
            Instant publishedAt,
            Instant activatedAt,
            Instant retireAfter,
            Instant retiredAt,
            Instant revokedAt) {
        this.id = id;
        this.kid = requireText(kid, "kid 不能为空");
        this.keyVersionReference = requireText(keyVersionReference, "KMS Key Version 引用不能为空");
        this.publicJwkModulus = requireText(publicJwkModulus, "RSA JWK modulus 不能为空");
        this.publicJwkExponent = requireText(publicJwkExponent, "RSA JWK exponent 不能为空");
        if (status == null) {
            throw new IllegalArgumentException("Signing Key 状态不能为空");
        }
        if ((status == SigningKeyStatus.PUBLISHED || status == SigningKeyStatus.ACTIVE || status == SigningKeyStatus.RETIRING
                || status == SigningKeyStatus.RETIRED) && publishedAt == null) {
            throw new IllegalArgumentException("可发布的 Signing Key 必须具有发布时间");
        }
        if (activatedAt != null && publishedAt == null) {
            throw new IllegalArgumentException("Signing Key 激活前必须发布");
        }
        if (retireAfter != null && activatedAt == null) {
            throw new IllegalArgumentException("Signing Key 退役窗口必须从激活后开始");
        }
        if (retiredAt != null && retireAfter == null) {
            throw new IllegalArgumentException("Signing Key 退役前必须进入 RETIRING");
        }
        if ((status == SigningKeyStatus.REVOKED) != (revokedAt != null)) {
            throw new IllegalArgumentException("Signing Key 撤销状态不一致");
        }
        this.status = status;
        this.publishedAt = publishedAt;
        this.activatedAt = activatedAt;
        this.retireAfter = retireAfter;
        this.retiredAt = retiredAt;
        this.revokedAt = revokedAt;
    }

    public static SigningKey publish(
            String kid,
            String keyVersionReference,
            String publicJwkModulus,
            String publicJwkExponent,
            Instant publishedAt) {
        return new SigningKey(null, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                SigningKeyStatus.PUBLISHED, publishedAt, null, null, null, null);
    }

    public static SigningKey restore(
            UUID id,
            String kid,
            String keyVersionReference,
            String publicJwkModulus,
            String publicJwkExponent,
            SigningKeyStatus status,
            Instant publishedAt,
            Instant activatedAt,
            Instant retireAfter,
            Instant retiredAt,
            Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("Signing Key ID 不能为空");
        }
        return new SigningKey(id, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                status, publishedAt, activatedAt, retireAfter, retiredAt, revokedAt);
    }

    public SigningKey identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("Signing Key ID 状态不合法");
        }
        return new SigningKey(generatedId, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                status, publishedAt, activatedAt, retireAfter, retiredAt, revokedAt);
    }

    public SigningKey activate(Instant at) {
        if (status != SigningKeyStatus.PUBLISHED || at == null || at.isBefore(publishedAt.plus(PUBLICATION_WINDOW))) {
            throw new IllegalStateException("Signing Key 必须发布满五分钟后才能激活");
        }
        return new SigningKey(id, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                SigningKeyStatus.ACTIVE, publishedAt, at, null, null, null);
    }

    public SigningKey beginRetirement(Instant at) {
        if (status != SigningKeyStatus.ACTIVE || at == null) {
            throw new IllegalStateException("只有 ACTIVE Signing Key 可以进入 RETIRING");
        }
        return new SigningKey(id, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                SigningKeyStatus.RETIRING, publishedAt, activatedAt, at.plus(RETIREMENT_GRACE_PERIOD), null, null);
    }

    public SigningKey retire(Instant at) {
        if (status != SigningKeyStatus.RETIRING || at == null || at.isBefore(retireAfter)) {
            throw new IllegalStateException("Signing Key 必须完成三十分钟宽限期后才能退役");
        }
        return new SigningKey(id, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                SigningKeyStatus.RETIRED, publishedAt, activatedAt, retireAfter, at, null);
    }

    public SigningKey revoke(Instant at) {
        if (status == SigningKeyStatus.REVOKED) {
            return this;
        }
        if (at == null) {
            throw new IllegalArgumentException("Signing Key 撤销时间不能为空");
        }
        return new SigningKey(id, kid, keyVersionReference, publicJwkModulus, publicJwkExponent,
                SigningKeyStatus.REVOKED, publishedAt, activatedAt, retireAfter, retiredAt, at);
    }

    public UUID id() { return id; }
    public String kid() { return kid; }
    public String keyVersionReference() { return keyVersionReference; }
    public String publicJwkModulus() { return publicJwkModulus; }
    public String publicJwkExponent() { return publicJwkExponent; }
    public SigningKeyStatus status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public Instant activatedAt() { return activatedAt; }
    public Instant retireAfter() { return retireAfter; }
    public Instant retiredAt() { return retiredAt; }
    public Instant revokedAt() { return revokedAt; }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
