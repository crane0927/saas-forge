package io.saas.forge.iam.infrastructure.security;

import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKey;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKeyResolver;
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
