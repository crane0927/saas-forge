package io.saasforge.sdk.auth;

import java.util.Optional;

/** 按 kid 解析 IAM 已发布的 RS256 验证公钥。 */
@FunctionalInterface
public interface ServiceJwtVerificationKeyResolver {
    Optional<ServiceJwtVerificationKey> findByKid(String kid);
}
