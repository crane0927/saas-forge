package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import java.util.Optional;

/** 将 IAM 当前可发布验证密钥映射为 SDK 的 JWT 验签端口。 */
public final class IamJwtVerificationKeyResolver implements ServiceJwtVerificationKeyResolver {
    private final SigningKeyRepository signingKeys;

    public IamJwtVerificationKeyResolver(SigningKeyRepository signingKeys) {
        this.signingKeys = signingKeys;
    }

    @Override
    public Optional<ServiceJwtVerificationKey> findByKid(String kid) {
        return signingKeys.findPublishedVerificationKeys().stream()
                .filter(key -> key.kid().equals(kid))
                .findFirst()
                .map(key -> new ServiceJwtVerificationKey(
                        key.kid(), key.publicJwkModulus(), key.publicJwkExponent()));
    }
}
