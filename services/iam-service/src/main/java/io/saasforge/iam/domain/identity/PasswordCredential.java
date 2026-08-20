package io.saasforge.iam.domain.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 一个 Identity 的密码凭据及其不可逆失效状态。 */
public final class PasswordCredential {

    private static final Duration INITIAL_CREDENTIAL_LIFETIME = Duration.ofHours(24);

    private final UUID id;
    private final UUID identityId;
    private final CredentialType type;
    private final Argon2idPasswordHash passwordHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final Instant invalidatedAt;

    private PasswordCredential(
            UUID id,
            UUID identityId,
            CredentialType type,
            Argon2idPasswordHash passwordHash,
            Instant issuedAt,
            Instant expiresAt,
            Instant invalidatedAt) {
        if (identityId == null || type == null || passwordHash == null || issuedAt == null) {
            throw new IllegalArgumentException("密码凭据的必要字段不能为空");
        }
        if (type == CredentialType.INITIAL_PLATFORM_PASSWORD && expiresAt == null) {
            throw new IllegalArgumentException("初始密码凭据必须设置到期时间");
        }
        if (type == CredentialType.PASSWORD && expiresAt != null) {
            throw new IllegalArgumentException("常规密码凭据不能设置到期时间");
        }
        if (expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("密码凭据到期时间不能早于签发时间");
        }
        if (invalidatedAt != null && invalidatedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("密码凭据失效时间不能早于签发时间");
        }
        this.id = id;
        this.identityId = identityId;
        this.type = type;
        this.passwordHash = passwordHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.invalidatedAt = invalidatedAt;
    }

    public static PasswordCredential initial(UUID identityId, Argon2idPasswordHash hash, Instant issuedAt) {
        return new PasswordCredential(null, identityId, CredentialType.INITIAL_PLATFORM_PASSWORD, hash, issuedAt,
                issuedAt.plus(INITIAL_CREDENTIAL_LIFETIME), null);
    }

    public static PasswordCredential regular(UUID identityId, Argon2idPasswordHash hash, Instant issuedAt) {
        return new PasswordCredential(null, identityId, CredentialType.PASSWORD, hash, issuedAt, null, null);
    }

    public static PasswordCredential restore(
            UUID id,
            UUID identityId,
            CredentialType type,
            Argon2idPasswordHash hash,
            Instant issuedAt,
            Instant expiresAt,
            Instant invalidatedAt) {
        if (id == null) {
            throw new IllegalArgumentException("密码凭据 ID 不能为空");
        }
        return new PasswordCredential(id, identityId, type, hash, issuedAt, expiresAt, invalidatedAt);
    }

    public PasswordCredential identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("密码凭据 ID 状态不合法");
        }
        return new PasswordCredential(generatedId, identityId, type, passwordHash, issuedAt, expiresAt, invalidatedAt);
    }

    public PasswordCredential invalidate(Instant at) {
        if (at == null || at.isBefore(issuedAt)) {
            throw new IllegalArgumentException("密码凭据失效时间不合法");
        }
        if (invalidatedAt != null) {
            throw new IllegalStateException("密码凭据已永久失效");
        }
        return new PasswordCredential(id, identityId, type, passwordHash, issuedAt, expiresAt, at);
    }

    public boolean isValidAt(Instant at) {
        return invalidatedAt == null && (expiresAt == null || expiresAt.isAfter(at));
    }

    public UUID id() { return id; }
    public UUID identityId() { return identityId; }
    public CredentialType type() { return type; }
    public Argon2idPasswordHash passwordHash() { return passwordHash; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant invalidatedAt() { return invalidatedAt; }
}
