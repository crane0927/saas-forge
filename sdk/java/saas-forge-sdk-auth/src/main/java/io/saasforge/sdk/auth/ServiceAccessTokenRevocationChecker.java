package io.saasforge.sdk.auth;

import java.util.UUID;

/**
 * 同时检查 Revocation Index Ready、签名 kid 与 OAuth client_id；状态不可判定时必须抛出异常。
 * 调用边界只接收规范 Client ID 与 kid，不得接收 Token、Secret 或摘要。
 */
@FunctionalInterface
public interface ServiceAccessTokenRevocationChecker {
    boolean isRevoked(UUID clientId, String kid);
}
