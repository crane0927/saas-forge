package io.saasforge.iam.application.client;

import java.util.UUID;

public final class OAuthClientManagementException extends RuntimeException {
    private final String code;
    private final UUID clientId;

    private OAuthClientManagementException(String code, String message, UUID clientId) {
        super(message);
        this.code = code;
        this.clientId = clientId;
    }

    public String code() { return code; }
    public UUID clientId() { return clientId; }

    public static OAuthClientManagementException idempotencyInvalid() {
        return new OAuthClientManagementException(
                "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须是规范 UUIDv7", null);
    }

    public static OAuthClientManagementException recoveryRequestInvalid() {
        return new OAuthClientManagementException(
                "CLIENT_SECRET_RECOVERY_REQUEST_INVALID", "Secret 恢复请求必须引用不同的原 UUIDv7 操作键", null);
    }

    public static OAuthClientManagementException inProgress() {
        return new OAuthClientManagementException("IDEMPOTENCY_REQUEST_IN_PROGRESS", "相同操作正在提交", null);
    }

    public static OAuthClientManagementException keyReused() {
        return new OAuthClientManagementException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key 已绑定不同请求", null);
    }

    public static OAuthClientManagementException secretAlreadyRevealed(UUID clientId) {
        return new OAuthClientManagementException(
                "CLIENT_SECRET_ALREADY_REVEALED", "Client Secret 已在首次响应中展示", clientId);
    }

    public static OAuthClientManagementException notFound() {
        return new OAuthClientManagementException("OAUTH_CLIENT_NOT_FOUND", "OAuth Client 不存在", null);
    }

    public static OAuthClientManagementException revoked() {
        return new OAuthClientManagementException("OAUTH_CLIENT_REVOKED", "OAuth Client 已被吊销", null);
    }

    public static OAuthClientManagementException rotationOverlapActive() {
        return new OAuthClientManagementException(
                "CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE", "Client Secret 重叠窗口尚未结束", null);
    }

    public static OAuthClientManagementException recoveryNotAllowed() {
        return new OAuthClientManagementException(
                "CLIENT_SECRET_RECOVERY_NOT_ALLOWED", "Client Secret 签发操作不允许恢复", null);
    }
}
