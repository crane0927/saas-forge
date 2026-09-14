package io.saasforge.sdk.auth;

import java.util.UUID;

/** 同时检查 User Access Token 的 jti 与签名 kid 是否已撤销。 */
@FunctionalInterface
public interface UserAccessTokenRevocationChecker {
    boolean isRevoked(UUID jti, String kid);
}
