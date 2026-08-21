package io.saasforge.iam.application.bootstrap;

public final class PlatformAdminCredentialResetConflictException extends RuntimeException {
    public static final String CODE = "PLATFORM_ADMIN_INITIAL_CREDENTIAL_RESET_NOT_ALLOWED";

    public PlatformAdminCredentialResetConflictException() {
        super("Default Platform Admin 状态不允许重置初始凭证");
    }
}
