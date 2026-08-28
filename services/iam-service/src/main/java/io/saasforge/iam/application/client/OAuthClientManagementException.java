package io.saasforge.iam.application.client;

public final class OAuthClientManagementException extends RuntimeException {
    private final String code;

    private OAuthClientManagementException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }

    public static OAuthClientManagementException idempotencyInvalid() {
        return new OAuthClientManagementException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须是规范 UUIDv7");
    }

    public static OAuthClientManagementException inProgress() {
        return new OAuthClientManagementException("IDEMPOTENCY_REQUEST_IN_PROGRESS", "相同操作正在提交");
    }

    public static OAuthClientManagementException keyReused() {
        return new OAuthClientManagementException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key 已绑定不同请求");
    }

    public static OAuthClientManagementException secretAlreadyRevealed() {
        return new OAuthClientManagementException("CLIENT_SECRET_ALREADY_REVEALED", "Client Secret 已在首次响应中展示");
    }

    public static OAuthClientManagementException notFound() {
        return new OAuthClientManagementException("OAUTH_CLIENT_NOT_FOUND", "OAuth Client 不存在");
    }

    public static OAuthClientManagementException revoked() {
        return new OAuthClientManagementException("OAUTH_CLIENT_REVOKED", "OAuth Client 已被吊销");
    }

    public static OAuthClientManagementException rotationOverlapActive() {
        return new OAuthClientManagementException(
                "CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE", "Client Secret 重叠窗口尚未结束");
    }
}
