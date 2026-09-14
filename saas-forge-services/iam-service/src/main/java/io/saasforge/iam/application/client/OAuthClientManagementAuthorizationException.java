package io.saasforge.iam.application.client;

public final class OAuthClientManagementAuthorizationException extends RuntimeException {
    private final String code;

    private OAuthClientManagementAuthorizationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }

    public static OAuthClientManagementAuthorizationException accessTokenInvalid(Throwable cause) {
        return new OAuthClientManagementAuthorizationException(
                "ACCESS_TOKEN_INVALID", "User Access Token 缺失、无效或已撤销", cause);
    }

    public static OAuthClientManagementAuthorizationException platformContextRequired() {
        return new OAuthClientManagementAuthorizationException(
                "PLATFORM_CONTEXT_REQUIRED", "OAuth Client 管理只接受 Platform Context User Access Token", null);
    }

    public static OAuthClientManagementAuthorizationException platformAdminRequired() {
        return new OAuthClientManagementAuthorizationException(
                "PLATFORM_ADMIN_REQUIRED", "当前 Identity 不具有 PLATFORM_ADMIN", null);
    }
}
